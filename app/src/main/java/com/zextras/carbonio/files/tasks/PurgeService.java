// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.tasks;

import com.zextras.carbonio.files.Constants.Config;
import com.zextras.carbonio.files.Constants.Db.RootId;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.dao.ebean.Tombstone;
import com.zextras.carbonio.files.dal.dao.ebean.VersionReservation;
import com.zextras.carbonio.files.dal.locks.LockKey;
import com.zextras.carbonio.files.dal.locks.LockedTree;
import com.zextras.carbonio.files.dal.locks.NodeLocks;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.FileVersionSort;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.OverwriteLeaseRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.ShareRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.TombstoneRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.VersionReservationRepository;
import com.zextras.filestore.api.Filestore;
import com.zextras.filestore.model.BulkDeleteRequestItem;
import com.zextras.filestore.model.BulkDeleteResponseItem;
import com.zextras.filestore.model.IdentifierType;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * {@link #purgeTrashedNodes} claims each expired trashed subtree in one short locked transaction
 * (rows deleted, tombstones created, re-checked under the lock), then {@link #purgeTombstones}
 * deletes the blobs outside any transaction; a crash in between leaves tombstones that the next
 * cycle purges. Tombstone deletes are idempotent and the retry counter is incremented in the
 * database, so two instances running together cannot re-create a deleted tombstone.
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
  @Inject OverwriteLeaseRepository overwriteLeaseRepository;
  @Inject ShareRepository shareRepository;
  @Inject NodeLocks nodeLocks;
  @Inject LockedTree lockedTree;
  @Inject Filestore fileStore;

  @ConfigProperty(name = "carbonio.files.reservation-purge.expiry", defaultValue = "24h")
  Duration reservationExpiry;

  void purgeTrashedNodes(long retentionDays) {
    long retentionTimestamp = System.currentTimeMillis() - (retentionDays * 86400 * 1000);

    for (String topId : nodeRepository.getTrashedTopNodeIds(retentionTimestamp)) {
      try {
        QuarkusTransaction.requiringNew().run(() -> claimTrashedSubtree(topId, retentionTimestamp));
      } catch (Exception e) {
        logger.warn(
            "Purge of trashed node {} failed, retried next cycle: {}", topId, e.getMessage());
      }
    }

    purgeTombstones();
  }

  private void claimTrashedSubtree(String topId, long retentionTimestamp) {
    if (nodeLocks.lock(List.of(LockKey.node(topId))).isEmpty()) {
      return;
    }
    Optional<Node> top = nodeRepository.getNode(topId);
    if (top.isEmpty()
        || !nodeRepository.isTrashed(topId)
        || !top.get().getParentId().map(RootId.TRASH_ROOT::equals).orElse(false)
        || top.get().getUpdatedAt() >= retentionTimestamp) {
      return;
    }
    List<Node> subtree = lockedTree.collect(List.of(top.get()));
    subtree.stream()
        .filter(node -> !NodeType.FOLDER.equals(node.getNodeType()))
        .forEach(
            node ->
                tombstoneRepository.createTombstonesBulk(
                    fileVersionRepository.getFileVersions(
                        node.getId(), List.of(FileVersionSort.VERSION_ASC)),
                    node.getOwnerId()));
    List<String> ids = subtree.stream().map(Node::getId).toList();
    shareRepository.deleteSharesBulk(ids);
    nodeRepository.deleteNodes(ids);
    logger.info("Claimed {} trashed nodes of {} for purge", ids.size(), topId);
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
            tombstoneRepository.incrementAttempts(t.getNodeId(), t.getVersion());
          }
        }
      }
    }
  }

  /**
   * Deletes the blobs of the version reservations never confirmed within {@link #reservationExpiry}
   * of the database clock (their save failed: no revision references them, which is checked again),
   * then the reservations. Claiming them first (PENDING → PURGING) makes a late confirmation fail
   * instead of creating a version whose blob is being deleted. A whole-call failure is an outage
   * and does not count; a reservation reported as not deleted {@link
   * #MAX_RESERVATION_PURGE_ATTEMPTS} times (never written, or a persistent storage error) is
   * dropped, leaving at most an orphan blob.
   */
  void purgeExpiredReservations() {
    versionReservationRepository.claimExpired(reservationExpiry);

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
    try {
      purgeExpiredReservations();
    } finally {
      purgeExpiredOverwriteLeases();
    }
  }

  /**
   * Removes the rows of the overwrite leases expired for over an hour; the blobs are live versions.
   */
  void purgeExpiredOverwriteLeases() {
    overwriteLeaseRepository.removeExpired(Duration.ofHours(1));
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
