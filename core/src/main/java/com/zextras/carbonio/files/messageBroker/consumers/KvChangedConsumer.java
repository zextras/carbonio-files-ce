// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.messageBroker.consumers;
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
  protected void doHandle(BaseEvent baseMessageBrokerEvent) {
    KvChanged kvChanged = (KvChanged) baseMessageBrokerEvent;
    logger.info("Received KvChanged({}, {})", kvChanged.getKey(), kvChanged.getValue());

    switch (kvChanged.getKey()){
      case "carbonio-files/max-number-of-versions":
        handleMaxVersionNumerChanged(Integer.parseInt(kvChanged.getValue()));
        break;
      default:
        logger.info("Event was ignored");
        break;
    }
  }

  // TODO waiting for feedback for keepforever's behaviour
  /**
   * Check all nodes' versions and deletes the least recent ones to match the new max version number.
   * There are a few exceptions: if a file version is marked as keep forever it does not delete it (this of course
   * can cause a situation where there are more versions that new max version number); it does not delete the current
   * (last) version of a node ever, possibly resulting in the same scenario of having more versions than maximum.
   */
  private void handleMaxVersionNumerChanged(int newMaxVersionNumber){
    logger.info("Handling new max version numer");
    List<Node> allFiles = nodeRepository.findAllNodesFiles();

    // I don't know how to make this more efficient (at least just a query once retrieved all files) without
    // writing an unreadable mess of a query with subqueries that essentially do the same thing;
    // since it's a rare operation I think it can stay like this
    for (Node node : allFiles) {
      logger.info("Checking node {}", node.getFullName());
      // Already in ascending order
      List<FileVersion> filesToCheck = fileVersionRepository.getFileVersions(node.getId(), true);
      // Remove last file version (current one) because it can't be good to delete the current version
      filesToCheck.remove(filesToCheck.size() - 1);

      if(filesToCheck.size() > newMaxVersionNumber){
        int toDelete = filesToCheck.size() - newMaxVersionNumber + 1; // add 1 since the current version has been removed from list
        for(FileVersion fileVersion : filesToCheck){
          // Delete only if not keep forever and if didn't already delete enough versions
          if(!fileVersion.isKeptForever() && toDelete > 0){
            logger.info("Removing version {}", fileVersion.getVersion());
            fileVersionRepository.deleteFileVersion(fileVersion);
            toDelete--;
          }
        }
      }
    }
  }
}
