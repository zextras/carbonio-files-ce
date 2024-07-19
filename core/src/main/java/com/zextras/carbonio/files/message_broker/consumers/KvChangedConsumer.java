// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.message_broker.consumers;

import com.zextras.carbonio.files.dal.dao.ebean.FileVersion;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.message_broker.config.EventConfig;
import com.zextras.carbonio.message_broker.consumer.BaseConsumer;
import com.zextras.carbonio.message_broker.events.generic.BaseEvent;
import com.zextras.carbonio.message_broker.events.services.service_discover.KvChanged;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class KvChangedConsumer extends BaseConsumer {

  private static final Logger logger = LoggerFactory.getLogger(KvChangedConsumer.class);

  private final NodeRepository nodeRepository;
  private final FileVersionRepository fileVersionRepository;

  public KvChangedConsumer(NodeRepository nodeRepository, FileVersionRepository fileVersionRepository) {
    this.nodeRepository = nodeRepository;
    this.fileVersionRepository = fileVersionRepository;
  }

  @Override
  protected EventConfig getEventConfig() {
    return EventConfig.KV_CHANGED;
  }

  @Override
  public void doHandle(BaseEvent baseMessageBrokerEvent) {
    KvChanged kvChanged = (KvChanged) baseMessageBrokerEvent;
    logger.info("Received KvChanged({}, {})", kvChanged.getKey(), kvChanged.getValue());

    switch (kvChanged.getKey()) {
      case "carbonio-files/max-number-of-versions":
        handleMaxVersionNumerChanged(Integer.parseInt(kvChanged.getValue()));
        break;
      default:
        logger.info("Event was ignored");
        break;
    }
  }

  /**
   * Check all nodes' versions and deletes the least recent ones to match the new max version number.
   * There are a few exceptions: if a file version is marked as keep forever it does not delete it (this of course
   * can cause a situation where there are more versions that new max version number); it also does not delete the current
   * (last) version of a node ever, possibly resulting in the same scenario of having more versions than maximum.
   */
  private void handleMaxVersionNumerChanged(int newMaxVersionNumber) {
    logger.info("Handling new max version number");
    List<Node> allFiles = nodeRepository.findAllNodesFiles();

    /*
     I don't know how to make this more efficient (at least just a query once retrieved all files) without
     writing an unreadable mess of a query with subqueries that essentially do the same thing;
     since it's a rare operation I think it can stay like this for readability
    */
    for (Node node : allFiles) {
      logger.info("Checking node {}", node.getFullName());
      List<FileVersion> filesToCheck = fileVersionRepository.getFileVersions(node.getId(), true);

      if (filesToCheck.size() > newMaxVersionNumber) {
        trimFileVersions(filesToCheck, newMaxVersionNumber);
      }
    }
  }

  private void trimFileVersions(List<FileVersion> fileVersions, Integer newMaxVersionNumber) {
    int toDelete = fileVersions.size() - newMaxVersionNumber;
    FileVersion currentVersion = fileVersions.get(fileVersions.size() - 1);

    for (FileVersion fileVersion : fileVersions) {
      if (toDelete == 0) break; // break early if there are no more fileversions to delete

      // Delete only if not keep forever and if not current version
      if (!fileVersion.isKeptForever() && !fileVersion.equals(currentVersion)) {
        logger.info("Removing version {}", fileVersion.getVersion());
        fileVersionRepository.deleteFileVersion(fileVersion);
        toDelete--;
      }
    }

    if(toDelete > 0) logger.warn("Can't remove enough versions to match the new max version number as some are either " +
        "marked as keepForever or deleting the current version is required to match the new max version number and we " +
        "can't do that");
  }
}
