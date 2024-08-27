// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.message_broker.consumers;
import com.google.inject.Inject;
import com.zextras.carbonio.files.config.FilesConfig;
import com.zextras.carbonio.files.dal.dao.ebean.FileVersion;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.FileVersionSort;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.TombstoneRepository;
import com.zextras.carbonio.message_broker.config.EventConfig;
import com.zextras.carbonio.message_broker.consumer.BaseConsumer;
import com.zextras.carbonio.message_broker.events.generic.BaseEvent;
import com.zextras.carbonio.message_broker.events.services.mailbox.UserDeleted;
import com.zextras.filestore.model.BulkDeleteRequestItem;
import com.zextras.filestore.model.IdentifierType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class UserDeletedConsumer extends BaseConsumer {

  private static final Logger logger = LoggerFactory.getLogger(UserDeletedConsumer.class);

  private final NodeRepository nodeRepository;
  private final FilesConfig filesConfig;
  private final FileVersionRepository fileVersionRepository;
  private final TombstoneRepository tombstoneRepository;

  @Inject
  public UserDeletedConsumer(
      FilesConfig filesConfig,
      NodeRepository nodeRepository,
      FileVersionRepository fileVersionRepository,
      TombstoneRepository tombstoneRepository) {
    this.nodeRepository = nodeRepository;
    this.fileVersionRepository = fileVersionRepository;
    this.filesConfig = filesConfig;
    this.tombstoneRepository = tombstoneRepository;
  }

  @Override
  protected EventConfig getEventConfig() {
    return EventConfig.USER_DELETED;
  }

  @Override
  public void doHandle(BaseEvent baseMessageBrokerEvent) {
    UserDeleted userDeleted = (UserDeleted) baseMessageBrokerEvent;
    logger.info("Received UserDeleted({})", userDeleted.getUserId());

    // I wish there was a prettier way to do this

    // Delete blobs of nodes from Storages
    List<Node> listNodesToDelete = nodeRepository.findNodesByOwner(userDeleted.getUserId());
    List<BulkDeleteRequestItem> deleteRequests = new ArrayList<>();

    listNodesToDelete.forEach(node -> {
      List<FileVersion> fileVersionsToDelete = fileVersionRepository.getFileVersions(node.getId(), List.of(FileVersionSort.VERSION_ASC));
      fileVersionsToDelete.forEach(fileVersion ->
          deleteRequests.add(BulkDeleteRequestItem.filesItem(node.getId(), fileVersion.getVersion()))
      );
    });

    try {
      filesConfig.getFileStoreClient().bulkDelete(IdentifierType.files, userDeleted.getUserId(), deleteRequests);
    } catch (Exception e) {
      logger.error("Can't perform bulk delete on storages: {}", e.getMessage());
    }

    // Delete nodes from Files
    nodeRepository.deleteNodes(listNodesToDelete.stream().map(Node::getId).toList());

    // Delete tombstones if any (do not wait for job)
    tombstoneRepository.deleteTombstonesFromOwner(userDeleted.getUserId());
  }
}
