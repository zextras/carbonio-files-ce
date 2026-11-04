// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.message_broker.consumers;

import com.zextras.carbonio.files.dal.dao.ebean.FileVersion;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.repositories.impl.Transactions;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.FileVersionSort;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.TombstoneRepository;
import com.zextras.carbonio.files.rest.services.BlobKey;
import com.zextras.carbonio.files.rest.services.BlobLifecycle;
import com.zextras.carbonio.message_broker.config.EventConfig;
import com.zextras.carbonio.message_broker.consumer.BaseConsumer;
import com.zextras.carbonio.message_broker.events.generic.BaseEvent;
import com.zextras.carbonio.message_broker.events.services.service_discover.KeyValueChanged;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This consumer listens to key-value change events that are emitted thanks to a service that uses
 * Consul watches to catch when a key value pair changes. It catches every key value emitted, so a
 * switch on the key is needed to execute the correct behaviour. The consul watch services will be
 * one for each project that needs to emit consul events, since they start just after the KV has
 * been created (for example max version is created by files so files should include a watch service
 * during its installation). The consul watch service should publish an event on Message Broker
 * using the message-broker-sdk syntax (<eventname>_EXCHANGE) so that every service can listen to
 * it.
 *
 * <p>P5: this is the LIVE-config bridge kept from the legacy stack (max-number-of-versions KV
 * changes still prune {@link FileVersion}s live, as opposed to the other KV consumers that a
 * config-migration batch job could otherwise absorb).
 *
 * <p>Transaction model: {@code doHandle} does NOT wrap the whole prune in one transaction. Each
 * node is trimmed in its own transaction (version rows and tombstones together) and its blobs are
 * deleted after the commit, so a DB connection is never held for the whole (potentially large) trim
 * nor across the storage calls. The RabbitMQ delivery stays unacked until {@code doHandle} returns
 * — that ack is {@link BaseConsumer}'s responsibility and is inherent (it must reflect whether
 * processing succeeded); only the DB-connection hold was shortened. The trim is idempotent, so a
 * mid-run failure is safely retried on redelivery.
 */
@ApplicationScoped
public class KeyValueChangedConsumer extends BaseConsumer {

  private static final Logger logger = LoggerFactory.getLogger(KeyValueChangedConsumer.class);

  private final FileVersionRepository fileVersionRepository;
  private final NodeRepository nodeRepository;
  private final TombstoneRepository tombstoneRepository;
  private final Transactions transactions;
  private final BlobLifecycle blobLifecycle;

  @Inject
  public KeyValueChangedConsumer(
      FileVersionRepository fileVersionRepository,
      NodeRepository nodeRepository,
      TombstoneRepository tombstoneRepository,
      Transactions transactions,
      BlobLifecycle blobLifecycle) {
    this.fileVersionRepository = fileVersionRepository;
    this.nodeRepository = nodeRepository;
    this.tombstoneRepository = tombstoneRepository;
    this.transactions = transactions;
    this.blobLifecycle = blobLifecycle;
  }

  @Override
  protected EventConfig getEventConfig() {
    return EventConfig.KV_CHANGED;
  }

  @Override
  public void doHandle(BaseEvent baseMessageBrokerEvent) {
    KeyValueChanged kvChanged = (KeyValueChanged) baseMessageBrokerEvent;
    logger.info("Received KvChanged({}, {})", kvChanged.getKey(), kvChanged.getValue());

    // switch for future use cases with KVs
    switch (kvChanged.getKey()) {
      case "carbonio-files/max-number-of-versions" ->
          handleMaxVersionNumberChanged(Integer.parseInt(kvChanged.getValue()));
      default -> logger.info("Event was ignored");
    }
  }

  /**
   * Check all nodes' versions that exceed new max and deletes the least recent ones to match the
   * new max version number. There are a few exceptions: if a file version is marked as keep forever
   * it does not delete it (this of course can cause a situation where there are more versions that
   * new max version number); it also does not delete the current (last) version of a node ever,
   * possibly resulting in the same scenario of having more versions than maximum.
   */
  private void handleMaxVersionNumberChanged(int newMaxVersionNumber) {
    logger.info("Handling new max version number");
    Map<String, List<FileVersion>> mapToProcess =
        fileVersionRepository.getFileVersionsRelatedToNodesHavingVersionsGreaterThan(
            newMaxVersionNumber);

    mapToProcess
        .keySet()
        .forEach(key -> trimFileVersions(mapToProcess.get(key), newMaxVersionNumber));
  }

  private void trimFileVersions(List<FileVersion> fileVersions, Integer newMaxVersionNumber) {
    String nodeId = fileVersions.get(0).getNodeId();

    Optional<Node> node = nodeRepository.getNode(nodeId);
    if (node.isEmpty()) {
      logger.warn("Node {} no longer exists, its versions are not trimmed", nodeId);
      return;
    }
    String ownerId = node.get().getOwnerId();

    List<BlobKey> removed =
        transactions.inNewTransaction(
            () -> {
              List<FileVersion> versions =
                  fileVersionRepository.getFileVersions(
                      nodeId, List.of(FileVersionSort.VERSION_ASC));
              if (versions.isEmpty()) {
                return List.<BlobKey>of();
              }
              int remaining = versions.size() - newMaxVersionNumber;
              FileVersion currentVersion = versions.get(versions.size() - 1);
              List<BlobKey> removedKeys = new ArrayList<>();
              for (FileVersion fileVersion : versions) {
                if (remaining <= 0)
                  break; // break early if there are no more fileversions to delete

                // Delete only if not keep forever and if not current version
                if (!fileVersion.isKeptForever() && !fileVersion.equals(currentVersion)) {
                  logger.info("Removing version {}", fileVersion.getVersion());
                  if (fileVersionRepository.removeVersionNotKeptForever(
                      nodeId, fileVersion.getVersion())) {
                    tombstoneRepository.createNewTombstone(
                        nodeId, ownerId, fileVersion.getVersion());
                    removedKeys.add(new BlobKey(nodeId, fileVersion.getVersion()));
                  }
                  remaining--;
                }
              }
              if (remaining > 0) {
                logger.warn(
                    "Can't remove enough versions to match the new max version number as some are"
                        + " either marked as keepForever or deleting the current version is"
                        + " required to match the new max version number and we can't do that");
              }
              return removedKeys;
            });

    blobLifecycle.deleteAndClear(ownerId, removed);
  }
}
