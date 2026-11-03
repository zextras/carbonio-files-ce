// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.message_broker.consumers;

import com.zextras.carbonio.files.dal.dao.ebean.FileVersion;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.locks.LockKey;
import com.zextras.carbonio.files.dal.locks.NodeLocks;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.FileVersionSort;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.TombstoneRepository;
import com.zextras.carbonio.message_broker.config.EventConfig;
import com.zextras.carbonio.message_broker.consumer.BaseConsumer;
import com.zextras.carbonio.message_broker.events.generic.BaseEvent;
import com.zextras.carbonio.message_broker.events.services.service_discover.KeyValueChanged;
import com.zextras.filestore.api.Filestore;
import com.zextras.filestore.model.BulkDeleteRequestItem;
import com.zextras.filestore.model.BulkDeleteResponseItem;
import com.zextras.filestore.model.IdentifierType;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
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
 * <p>Transaction model: the candidate nodes are read once, then each one is trimmed in its own
 * short transaction under its node lock, re-reading its versions from zero, so a version kept
 * forever or a new current version set meanwhile is honoured. The tombstones of the trimmed
 * versions are created in the same transaction; the blobs are deleted after the commit and their
 * tombstones removed once the storage confirms. The trim is idempotent, so a mid-run failure is
 * safely retried on redelivery.
 */
@ApplicationScoped
public class KeyValueChangedConsumer extends BaseConsumer {

  private static final Logger logger = LoggerFactory.getLogger(KeyValueChangedConsumer.class);

  private final FileVersionRepository fileVersionRepository;
  private final NodeRepository nodeRepository;
  private final TombstoneRepository tombstoneRepository;
  private final NodeLocks nodeLocks;
  private final Filestore fileStore;

  private record Trimmed(String ownerId, List<FileVersion> versions) {}

  @Inject
  public KeyValueChangedConsumer(
      FileVersionRepository fileVersionRepository,
      NodeRepository nodeRepository,
      TombstoneRepository tombstoneRepository,
      NodeLocks nodeLocks,
      Filestore fileStore) {
    this.fileVersionRepository = fileVersionRepository;
    this.nodeRepository = nodeRepository;
    this.tombstoneRepository = tombstoneRepository;
    this.nodeLocks = nodeLocks;
    this.fileStore = fileStore;
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
   * new max version number); it also does not delete the current version of a node ever, possibly
   * resulting in the same scenario of having more versions than maximum.
   */
  private void handleMaxVersionNumberChanged(int newMaxVersionNumber) {
    logger.info("Handling new max version number");
    Set<String> candidateNodeIds =
        fileVersionRepository
            .getFileVersionsRelatedToNodesHavingVersionsGreaterThan(newMaxVersionNumber)
            .keySet();

    for (String nodeId : candidateNodeIds) {
      try {
        Trimmed trimmed =
            QuarkusTransaction.requiringNew().call(() -> trimLocked(nodeId, newMaxVersionNumber));
        deleteBlobs(nodeId, trimmed);
      } catch (Exception e) {
        logger.warn("Version trim of node {} failed, skipping: {}", nodeId, e.getMessage());
      }
    }
  }

  private Trimmed trimLocked(String nodeId, int maxVersions) {
    nodeLocks.lock(List.of(LockKey.node(nodeId)));
    Optional<Node> node = nodeRepository.getNode(nodeId);
    if (node.isEmpty()) {
      return new Trimmed(null, List.of());
    }
    List<FileVersion> versions =
        fileVersionRepository.getFileVersions(nodeId, List.of(FileVersionSort.VERSION_ASC));
    int toDelete = versions.size() - maxVersions;
    List<FileVersion> trimmed = new ArrayList<>();
    for (FileVersion version : versions) {
      if (trimmed.size() >= toDelete) {
        break;
      }
      if (!version.isKeptForever()
          && !Objects.equals(version.getVersion(), node.get().getCurrentVersion())) {
        trimmed.add(version);
      }
    }

    String ownerId = node.get().getOwnerId();
    if (!trimmed.isEmpty()) {
      logger.info("Removing {} versions of node {}", trimmed.size(), nodeId);
      tombstoneRepository.createTombstonesBulk(trimmed, ownerId);
      fileVersionRepository.deleteFileVersions(
          nodeId, trimmed.stream().map(FileVersion::getVersion).toList());
    }
    if (trimmed.size() < toDelete) {
      logger.warn(
          "Can't remove enough versions of node {} to match the new max version number as some are"
              + " either marked as keepForever or deleting the current version is required",
          nodeId);
    }
    return new Trimmed(ownerId, trimmed);
  }

  private void deleteBlobs(String nodeId, Trimmed trimmed) {
    if (trimmed.versions().isEmpty()) {
      return;
    }
    List<BulkDeleteResponseItem> failedItems;
    try {
      failedItems =
          fileStore.bulkDelete(
              IdentifierType.files,
              trimmed.ownerId(),
              trimmed.versions().stream()
                  .map(version -> BulkDeleteRequestItem.filesItem(nodeId, version.getVersion()))
                  .toList());
    } catch (Exception e) {
      logger.warn("Bulk delete failed for node {}: {}. Tombstones remain.", nodeId, e.getMessage());
      return;
    }
    if (failedItems == null) {
      logger.warn("Bulk delete returned null for node {} (treated as failure).", nodeId);
      return;
    }
    Set<Integer> failedVersions =
        failedItems.stream()
            .filter(item -> nodeId.equals(item.getNode()))
            .map(BulkDeleteResponseItem::getVersion)
            .flatMap(Optional::stream)
            .collect(Collectors.toSet());
    trimmed.versions().stream()
        .filter(version -> !failedVersions.contains(version.getVersion()))
        .forEach(
            version ->
                tombstoneRepository.deleteTombstonesByNodeAndVersion(
                    version.getNodeId(), version.getVersion()));
  }
}
