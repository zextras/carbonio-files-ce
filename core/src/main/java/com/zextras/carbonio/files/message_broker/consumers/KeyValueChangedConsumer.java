// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.message_broker.consumers;

import com.zextras.carbonio.files.dal.dao.ebean.FileVersion;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.message_broker.config.EventConfig;
import com.zextras.carbonio.message_broker.consumer.BaseConsumer;
import com.zextras.carbonio.message_broker.events.generic.BaseEvent;
import com.zextras.carbonio.message_broker.events.services.service_discover.KeyValueChanged;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * This consumer listens to key-value change events that are emitted thanks to a service that uses
 * Consul watches to catch when a key value pair changes. It catches every key value emitted, so a switch
 * on the key is needed to execute the correct behaviour.
 * The consul watch services will be one for each project that needs to emit consul events, since they start
 * just after the KV has been created (for example max version is created by files so files should include
 * a watch service during its installation).
 * The consul watch service should publish an event on Message Broker using the message-broker-sdk syntax
 * (<eventname>_EXCHANGE) so that every service can listen to it.
 */
public class KeyValueChangedConsumer extends BaseConsumer {

  private static final Logger logger = LoggerFactory.getLogger(KeyValueChangedConsumer.class);

  private final FileVersionRepository fileVersionRepository;

  public KeyValueChangedConsumer(FileVersionRepository fileVersionRepository) {
    this.fileVersionRepository = fileVersionRepository;
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
          handleMaxVersionNumerChanged(Integer.parseInt(kvChanged.getValue()));
      default -> logger.info("Event was ignored");
    }
  }

  /**
   * Check all nodes' versions that exceed new max and deletes the least recent ones to match the new max version number.
   * There are a few exceptions: if a file version is marked as keep forever it does not delete it (this of course
   * can cause a situation where there are more versions that new max version number); it also does not delete the current
   * (last) version of a node ever, possibly resulting in the same scenario of having more versions than maximum.
   */
  private void handleMaxVersionNumerChanged(int newMaxVersionNumber) {
    logger.info("Handling new max version number");
    Map<String, List<FileVersion>> mapToProcess =
        fileVersionRepository.getFileVersionsRelatedToNodesHavingVersionsGreaterThan(newMaxVersionNumber);

    mapToProcess.keySet().forEach(key -> trimFileVersions(mapToProcess.get(key), newMaxVersionNumber));
  }

  private void trimFileVersions(List<FileVersion> fileVersions, Integer newMaxVersionNumber) {
    int toDelete = fileVersions.size() - newMaxVersionNumber;
    FileVersion currentVersion = fileVersions.get(fileVersions.size() - 1);

    for (FileVersion fileVersion : fileVersions) {
      if (toDelete == 0) return; // break early if there are no more fileversions to delete

      // Delete only if not keep forever and if not current version
      if (!fileVersion.isKeptForever() && !fileVersion.equals(currentVersion)) {
        logger.info("Removing version {}", fileVersion.getVersion());
        fileVersionRepository.deleteFileVersion(fileVersion);
        toDelete--;
      }
    }

    if (toDelete > 0) {
      logger.warn("Can't remove enough versions to match the new max version number as some are either " +
          "marked as keepForever or deleting the current version is required to match the new max version number and we " +
          "can't do that");
    }
  }
}
