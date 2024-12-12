// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.message_broker.consumers;
import com.google.inject.Inject;
import com.zextras.carbonio.files.dal.dao.ebean.FileVersion;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.FileVersionSort;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.TombstoneRepository;
import com.zextras.carbonio.message_broker.MessageBrokerClient;
import com.zextras.carbonio.message_broker.consumer.exceptions.FailedToConsumeEventException;
import com.zextras.carbonio.message_broker.events.services.files.DeletedUserFiles;
import com.zextras.filestore.api.Filestore;
import com.zextras.carbonio.message_broker.config.EventConfig;
import com.zextras.carbonio.message_broker.consumer.BaseConsumer;
import com.zextras.carbonio.message_broker.events.generic.BaseEvent;
import com.zextras.carbonio.message_broker.events.services.mailbox.DeleteUserRequested;
import com.zextras.filestore.model.BulkDeleteRequestItem;
import com.zextras.filestore.model.IdentifierType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class DeleteUserRequestConsumer extends BaseConsumer {

  private static final Logger logger = LoggerFactory.getLogger(DeleteUserRequestConsumer.class);

  private final MessageBrokerClient messageBrokerClient;
  private final NodeRepository nodeRepository;
  private final Filestore fileStore;
  private final FileVersionRepository fileVersionRepository;
  private final TombstoneRepository tombstoneRepository;

  @Inject
  public DeleteUserRequestConsumer(
      MessageBrokerClient messageBrokerClient,
      Filestore fileStore,
      NodeRepository nodeRepository,
      FileVersionRepository fileVersionRepository,
      TombstoneRepository tombstoneRepository) {
    this.messageBrokerClient = messageBrokerClient;
    this.nodeRepository = nodeRepository;
    this.fileVersionRepository = fileVersionRepository;
    this.fileStore = fileStore;
    this.tombstoneRepository = tombstoneRepository;
  }

  @Override
  protected EventConfig getEventConfig() {
    return EventConfig.DELETE_USER_REQUESTED;
  }

  @Override
  public void doHandle(BaseEvent baseMessageBrokerEvent) throws FailedToConsumeEventException {
    DeleteUserRequested deleteUserRequested = (DeleteUserRequested) baseMessageBrokerEvent;
    logger.info("Received DeleteUserRequestConsumer({})", deleteUserRequested.getUserId());

    // Delete blobs of nodes from Storages
    // I wish there was a prettier way to do this
    List<Node> listNodesToDelete = nodeRepository.findNodesByOwner(deleteUserRequested.getUserId());
    List<BulkDeleteRequestItem> deleteRequests = new ArrayList<>();

    listNodesToDelete.forEach(node -> {
      List<FileVersion> fileVersionsToDelete = fileVersionRepository.getFileVersions(node.getId(), List.of(FileVersionSort.VERSION_ASC));
      fileVersionsToDelete.forEach(fileVersion ->
          deleteRequests.add(BulkDeleteRequestItem.filesItem(node.getId(), fileVersion.getVersion()))
      );
    });

    try {
      logger.info("Deleting {} nodes from storages", listNodesToDelete.size());
      fileStore.bulkDelete(IdentifierType.files, deleteUserRequested.getUserId(), deleteRequests);
    } catch (Exception e) {
      // If storages call fails we don't delete the nodes, and we return a nack to the message broker
      // so the event will be reprocessed in the future.
      logger.error("Can't perform bulk delete on storages: {}", e.getMessage());
      throw new FailedToConsumeEventException("Can't perform bulk delete on storages", e);
    }

    // Delete nodes from Files
    nodeRepository.deleteNodes(listNodesToDelete.stream().map(Node::getId).toList());

    // Delete tombstones if any (do not wait for job)
    tombstoneRepository.deleteTombstonesFromOwner(deleteUserRequested.getUserId());

    // Send event to notify that files and blobs have been deleted
    messageBrokerClient.publish(new DeletedUserFiles(deleteUserRequested.getUserId()));
  }
}
