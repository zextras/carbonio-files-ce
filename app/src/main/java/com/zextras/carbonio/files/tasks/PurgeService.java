// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.tasks;

import com.zextras.carbonio.files.Constants.Config;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.dao.ebean.Tombstone;
import com.zextras.carbonio.files.dal.dao.ebean.VersionReservation;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.FileVersionSort;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.TombstoneRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.VersionReservationRepository;
import com.zextras.filestore.api.Filestore;
import com.zextras.filestore.model.BulkDeleteRequestItem;
import com.zextras.filestore.model.BulkDeleteResponseItem;
import com.zextras.filestore.model.IdentifierType;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * P5: Quarkus/{@code quarkus-scheduler} port of the legacy Guice {@code PurgeService}, which used a
 * raw {@link java.util.concurrent.ScheduledExecutorService}.
 *
 * <p><b>No DB transaction ever wraps the {@code fileStore.bulkDelete} storages round-trips.</b>
 * {@link #purgeTrashedNodes}, {@link #purgeTombstones} and {@link #scheduledRun} are deliberately
 * NOT {@code @Transactional}: they orchestrate, interleaving short DB operations with the blocking
 * storages calls. Each DB read/write is transactional at the repository layer (e.g. {@code
 * getAllTrashedNodes}/{@code getFileVersions}/{@code getTombstones} and {@code deleteNodes}/{@code
 * deleteTombstonesByNodeAndVersion}/{@code updateTombstone}), so a connection is acquired and
 * released per operation and NONE is held (idle-in-transaction) across a bulkDelete. This restores
 * the legacy Ebean transaction-per-query behaviour: the tradeoff is that a mid-run crash leaves a
 * partially-purged state, which the next cycle finishes (the deletes are idempotent and the
 * tombstone retry scheme self-heals).
 *
 * <p>{@link #scheduledRun} uses {@code concurrentExecution = SKIP} so a purge that outlives the
 * interval is never overlapped by the next tick (the legacy single-thread {@code
 * ScheduledExecutorService.scheduleAtFixedRate} serialised runs the same way).
 *
 * <p>The purge methods stay package-private so unit tests can invoke either directly; {@code
 * quarkus.scheduler.enabled=false} in {@code %test} (see {@code application.properties}) keeps the
 * scheduler from firing during the DAL/GraphQL/REST/purge tests.
 */
@ApplicationScoped
public class PurgeService {

  private static final Logger logger = LoggerFactory.getLogger(PurgeService.class);

  static final int MAX_TOMBSTONE_RETRIES = 3;
  static final int MAX_RESERVATION_PURGE_ATTEMPTS = 3;

  @Inject NodeRepository nodeRepository;
  @Inject FileVersionRepository fileVersionRepository;
  @Inject TombstoneRepository tombstoneRepository;
  @Inject VersionReservationRepository versionReservationRepository;
  @Inject Filestore fileStore;

  @ConfigProperty(name = "carbonio.files.reservation-purge.expiry", defaultValue = "24h")
  Duration reservationExpiry;

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
   * Deletes the blobs of the version reservations never confirmed within {@link #reservationExpiry}
   * (their save failed: no revision references them, which is checked again), then the
   * reservations. Claiming them first (PENDING → PURGING) makes a late confirmation fail instead of
   * creating a version whose blob is being deleted. A whole-call failure is an outage and does not
   * count; a reservation reported as not deleted {@link #MAX_RESERVATION_PURGE_ATTEMPTS} times
   * (never written, or a persistent storage error) is dropped, leaving at most an orphan blob.
   */
  void purgeExpiredReservations(long now) {
    long expiredBefore = now - reservationExpiry.toMillis();
    versionReservationRepository.claimExpired(expiredBefore);

    List<VersionReservation> purging = new ArrayList<>();
    for (VersionReservation reservation : versionReservationRepository.getPurging()) {
      if (fileVersionRepository
          .getFileVersion(reservation.getNodeId(), reservation.getVersion())
          .isPresent()) {
        logger.warn(
            "purgeExpiredReservations: version {} of node {} exists, its blob is kept",
            reservation.getVersion(),
            reservation.getNodeId());
        versionReservationRepository.remove(reservation);
      } else {
        purging.add(reservation);
      }
    }

    Map<String, List<VersionReservation>> byOwner =
        purging.stream().collect(Collectors.groupingBy(VersionReservation::getOwnerId));

    byOwner.forEach(this::purgeReservationsOf);
  }

  private void purgeReservationsOf(String ownerId, List<VersionReservation> reservations) {
    List<BulkDeleteResponseItem> failedItems;
    try {
      failedItems =
          fileStore.bulkDelete(
              IdentifierType.files,
              ownerId,
              reservations.stream()
                  .map(r -> BulkDeleteRequestItem.filesItem(r.getNodeId(), r.getVersion()))
                  .toList());
    } catch (Exception e) {
      logger.warn(
          "purgeExpiredReservations: bulk delete failed for owner {}: {}. Kept for the next run.",
          ownerId,
          e.getMessage());
      return;
    }
    if (failedItems == null) {
      logger.warn(
          "purgeExpiredReservations: bulk delete returned null for owner {}. Kept for the next"
              + " run.",
          ownerId);
      return;
    }
    reservations.forEach(reservation -> settle(reservation, failedItems));
  }

  private void settle(VersionReservation reservation, List<BulkDeleteResponseItem> failedItems) {
    boolean failed =
        failedItems.stream()
            .anyMatch(
                item ->
                    reservation.getNodeId().equals(item.getNode())
                        && item.getVersion()
                            .map(version -> version == reservation.getVersion())
                            .orElse(true));
    if (!failed) {
      versionReservationRepository.remove(reservation);
    } else if (reservation.getAttempts() + 1 >= MAX_RESERVATION_PURGE_ATTEMPTS) {
      logger.warn(
          "purgeExpiredReservations: giving up on blob nodeId={} version={} after {} attempts",
          reservation.getNodeId(),
          reservation.getVersion(),
          reservation.getAttempts() + 1);
      versionReservationRepository.remove(reservation);
    } else {
      versionReservationRepository.recordFailedAttempt(reservation);
    }
  }

  @Scheduled(
      every = "${carbonio.files.reservation-purge.interval:1h}",
      delayed = "${carbonio.files.reservation-purge.delay:1m}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void scheduledReservationPurge() {
    purgeExpiredReservations(System.currentTimeMillis());
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
      delayUnit = TimeUnit.MINUTES,
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void scheduledRun() {
    purgeTombstones();
    purgeTrashedNodes(Config.PurgeService.RETENTION_TRASHED_ITEMS_IN_DAYS);
  }
}
