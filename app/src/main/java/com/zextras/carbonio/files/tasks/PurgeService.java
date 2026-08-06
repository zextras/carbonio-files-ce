// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.tasks;

import com.zextras.carbonio.files.Constants.Config;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.dao.ebean.Tombstone;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.FileVersionSort;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.TombstoneRepository;
import com.zextras.filestore.api.Filestore;
import com.zextras.filestore.model.BulkDeleteRequestItem;
import com.zextras.filestore.model.BulkDeleteResponseItem;
import com.zextras.filestore.model.IdentifierType;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * P5: Quarkus/{@code quarkus-scheduler} port of the legacy Guice {@code PurgeService}, which used a
 * raw {@link java.util.concurrent.ScheduledExecutorService}.
 *
 * <p>{@link #purgeTrashedNodes} and {@link #purgeTombstones} are individually
 * {@code @Transactional} (and package-private, like the legacy version) so integration tests can
 * invoke either directly, without going through the scheduler, inside its own transaction/{@code
 * EntityManager} scope. {@link #scheduledRun} is the {@code @Scheduled} entry point the real
 * runtime uses; it is ALSO {@code @Transactional} so that its two self-invoked calls (which, being
 * plain {@code this.foo()} calls rather than calls through the CDI proxy, would otherwise bypass
 * their own {@code @Transactional} interceptor) still execute under an already-active ambient
 * transaction.
 *
 * <p>{@code quarkus.scheduler.enabled=false} in {@code %test} (see {@code application.properties})
 * keeps the scheduler from firing during the DAL/GraphQL/REST/purge integration tests, which invoke
 * {@link #purgeTrashedNodes}/{@link #purgeTombstones} directly.
 */
@ApplicationScoped
public class PurgeService {

  private static final Logger logger = LoggerFactory.getLogger(PurgeService.class);

  static final int MAX_TOMBSTONE_RETRIES = 3;

  @Inject NodeRepository nodeRepository;
  @Inject FileVersionRepository fileVersionRepository;
  @Inject TombstoneRepository tombstoneRepository;
  @Inject Filestore fileStore;

  @Transactional
  void purgeTrashedNodes(long retentionDays) {
    long retentionTimestamp = System.currentTimeMillis() - (retentionDays * 86400 * 1000);

    List<Node> trashedNodesToDelete = nodeRepository.getAllTrashedNodes(retentionTimestamp);
    if (trashedNodesToDelete.isEmpty()) {
      return;
    }

    List<Node> fileNodes =
        trashedNodesToDelete.stream()
            .filter(node -> !node.getNodeType().equals(NodeType.FOLDER))
            .toList();

    // Build bulk delete requests grouped by owner (bulkDelete requires a userId).
    Map<String, List<BulkDeleteRequestItem>> requestsByOwner =
        fileNodes.stream()
            .collect(
                Collectors.groupingBy(
                    Node::getOwnerId,
                    Collectors.flatMapping(
                        node ->
                            fileVersionRepository
                                .getFileVersions(node.getId(), List.of(FileVersionSort.VERSION_ASC))
                                .stream()
                                .map(
                                    fv ->
                                        BulkDeleteRequestItem.filesItem(
                                            node.getId(), fv.getVersion())),
                        Collectors.toList())));

    Set<String> failedFileNodeIds = new java.util.HashSet<>();

    for (Map.Entry<String, List<BulkDeleteRequestItem>> entry : requestsByOwner.entrySet()) {
      String ownerId = entry.getKey();
      List<BulkDeleteRequestItem> deleteRequests = entry.getValue();

      if (deleteRequests.isEmpty()) {
        continue;
      }

      try {
        List<BulkDeleteResponseItem> failedItems =
            fileStore.bulkDelete(IdentifierType.files, ownerId, deleteRequests);
        if (failedItems == null) {
          failedItems = List.of();
        }
        failedItems.stream().map(BulkDeleteResponseItem::getNode).forEach(failedFileNodeIds::add);
      } catch (NullPointerException e) {
        logger.debug("PowerStore returned null ids (all deletes succeeded): {}", e.getMessage());
      } catch (Exception e) {
        logger.warn(
            "Bulk delete failed for owner {}: {}. Nodes will be retried next cycle.",
            ownerId,
            e.getMessage());
        deleteRequests.stream().map(BulkDeleteRequestItem::getNode).forEach(failedFileNodeIds::add);
      }
    }

    // Only delete from DB nodes whose blobs were all successfully deleted.
    // For folders: only delete if they contain no failed file nodes (simple approach:
    // delete all folders since PowerStore doesn't know about them, but only if no file children
    // failed).
    List<String> confirmedNodeIds =
        trashedNodesToDelete.stream()
            .filter(node -> !failedFileNodeIds.contains(node.getId()))
            .map(Node::getId)
            .collect(Collectors.toList());

    // Remove folders that still contain failed file nodes.
    // A folder should stay if any of its descendant files failed to delete from PowerStore.
    Set<String> confirmedSet = new java.util.HashSet<>(confirmedNodeIds);
    trashedNodesToDelete.stream()
        .filter(node -> node.getNodeType().equals(NodeType.FOLDER))
        .forEach(
            folder -> {
              boolean hasFailedChild =
                  trashedNodesToDelete.stream()
                      .filter(n -> !n.getNodeType().equals(NodeType.FOLDER))
                      .filter(n -> failedFileNodeIds.contains(n.getId()))
                      .anyMatch(n -> n.getAncestorIds().contains(folder.getId()));
              if (hasFailedChild) {
                confirmedSet.remove(folder.getId());
              }
            });

    if (!confirmedSet.isEmpty()) {
      nodeRepository.deleteNodes(new ArrayList<>(confirmedSet));
      logger.info("Purged {} trashed nodes from database", confirmedSet.size());
    }

    if (!failedFileNodeIds.isEmpty()) {
      logger.warn(
          "Failed to delete blobs for {} nodes, they will be retried next cycle: {}",
          failedFileNodeIds.size(),
          failedFileNodeIds);
    }
  }

  @Transactional
  void purgeTombstones() {
    List<Tombstone> tombstones = tombstoneRepository.getTombstones();
    if (tombstones.isEmpty()) {
      return;
    }

    // Group by ownerId — bulkDelete requires userId to select host.
    Map<String, List<Tombstone>> byOwner =
        tombstones.stream().collect(Collectors.groupingBy(Tombstone::getOwnerId));

    for (Map.Entry<String, List<Tombstone>> entry : byOwner.entrySet()) {
      String ownerId = entry.getKey();
      List<Tombstone> ownerTombstones = entry.getValue();

      List<BulkDeleteRequestItem> requests =
          ownerTombstones.stream()
              .map(t -> BulkDeleteRequestItem.filesItem(t.getNodeId(), t.getVersion()))
              .collect(Collectors.toList());

      List<BulkDeleteResponseItem> failedItems;
      try {
        failedItems = fileStore.bulkDelete(IdentifierType.files, ownerId, requests);
      } catch (Exception e) {
        // ANY exception (incl. NullPointerException) = outage/connection failure.
        // NOT a per-blob failure: keep all tombstones, do NOT increment attempts.
        logger.warn(
            "purgeTombstones: bulk delete failed for owner {}: {}. Tombstones kept for next cycle.",
            ownerId,
            e.getMessage());
        continue;
      }

      if (failedItems == null) {
        // null return is NOT a success signal (happens on connection failure) -> keep all
        // tombstones
        logger.warn(
            "purgeTombstones: bulkDelete returned null for owner {} (treated as failure)."
                + " Tombstones kept for next cycle.",
            ownerId);
        continue;
      }

      // non-null list: empty = all deleted; partial = listed ids failed.
      Set<String> failedNodeIds =
          failedItems.stream().map(BulkDeleteResponseItem::getNode).collect(Collectors.toSet());

      // Remove confirmed-deleted tombstones; apply retry-cap to failed ones.
      for (Tombstone t : ownerTombstones) {
        if (!failedNodeIds.contains(t.getNodeId())) {
          tombstoneRepository.deleteTombstonesByNodeAndVersion(t.getNodeId(), t.getVersion());
        } else {
          // Genuine per-blob PowerStore rejection — increment attempts toward retry cap.
          if (t.getAttempts() + 1 >= MAX_TOMBSTONE_RETRIES) {
            logger.warn(
                "purgeTombstones: giving up on blob nodeId={} version={} after {} attempts; "
                    + "accepting orphan and removing tombstone.",
                t.getNodeId(),
                t.getVersion(),
                t.getAttempts() + 1);
            tombstoneRepository.deleteTombstonesByNodeAndVersion(t.getNodeId(), t.getVersion());
          } else {
            tombstoneRepository.updateTombstone(t.setAttempts(t.getAttempts() + 1));
          }
        }
      }
    }
  }

  /**
   * Real scheduled entry point. {@code every} defaults to the legacy 30-minute interval ({@link
   * Config.PurgeService#JOB_EXECUTION_INTERVAL_IN_MINUTES}) and can be overridden via the {@code
   * carbonio.files.purge.interval} property; disabled entirely in {@code %test} via {@code
   * quarkus.scheduler.enabled=false}.
   */
  @Scheduled(
      every = "${carbonio.files.purge.interval:30m}",
      delay = 1,
      delayUnit = TimeUnit.MINUTES)
  @Transactional
  void scheduledRun() {
    purgeTombstones();
    purgeTrashedNodes(Config.PurgeService.RETENTION_TRASHED_ITEMS_IN_DAYS);
  }
}
