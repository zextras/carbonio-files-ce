// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.tasks;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.TombstoneRepository;
import com.zextras.filestore.model.FilesIdentifier;
import com.zextras.storages.api.StoragesClient;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class PurgeService implements Runnable {

  private static final Logger logger = LoggerFactory.getLogger(PurgeService.class);

  private final TombstoneRepository      tombstoneRepository;
  private final NodeRepository           nodeRepository;
  private final FileVersionRepository    fileVersionRepository;
  private       ScheduledExecutorService scheduledExecutor;

  @Inject
  public PurgeService(
    TombstoneRepository tombstoneRepository,
    NodeRepository nodeRepository,
    FileVersionRepository fileVersionRepository
  ) {
    this.tombstoneRepository = tombstoneRepository;
    this.nodeRepository = nodeRepository;
    this.fileVersionRepository = fileVersionRepository;
  }

  private void purgeTombstones() {
    tombstoneRepository.getTombstones().forEach(tombstone -> {
      try {
        StoragesClient
          .atUrl("http://127.78.0.2:20002/")
          .delete(
            FilesIdentifier.of(
              tombstone.getNodeId(),
              tombstone.getVersion(),
              tombstone.getOwnerId()
            )
          );
      } catch (Exception e) {
        logger.debug("Blobs deletion failed: " + e);
      }
    });

    tombstoneRepository.deleteTombstones();
    logger.info("Deleted nodes");
  }

  private void purgeTrashedNodes(Long retentionDays) {

    /*
     * Compute retentionTimestamp: everything older must be deleted
     * retentionDays is expressed in days and should be converted into millis
     */
    long retentionTimestamp = System.currentTimeMillis() - (retentionDays * 86400 * 1000);

    List<Node> trashedNodesToDelete = nodeRepository.getAllTrashedNodes(retentionTimestamp);

    // Remove binaries associated with all the versions of trashed nodes
    trashedNodesToDelete.forEach(node -> {
      fileVersionRepository
        .getFileVersions(node.getId())
        .forEach(fileVersion -> {
          try {
            StoragesClient
              .atUrl("http://127.78.0.2:20002/")
              .delete(
                FilesIdentifier.of(node.getId(), fileVersion.getVersion(), node.getOwnerId())
              );
          } catch (Exception e) {
            logger.debug("Trashed blobs deletion failed: " + e);
          }
        });
    });

    // Delete from the database all Nodes that are older than t milliseconds
    nodeRepository.deleteTrashedNodesOlderThan(retentionTimestamp);
    logger.info("Deleted old trashed nodes");
  }

  @Override
  public void run() {
    purgeTombstones();
    purgeTrashedNodes(30L);
  }

  public void start() {
    scheduledExecutor = Executors.newScheduledThreadPool(1);
    scheduledExecutor.scheduleAtFixedRate(this, 1, 120, TimeUnit.MINUTES);
    logger.info("Purge Service started");
  }

  public void stop() {
    scheduledExecutor.shutdown();
    logger.info("Purge Service stopped");
  }
}
