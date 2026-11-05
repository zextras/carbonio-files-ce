// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.tasks;

import com.zextras.carbonio.files.Constants.Config;
import com.zextras.carbonio.files.Constants.Db.RootId;
import com.zextras.carbonio.files.dal.dao.ebean.FileVersion;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.dao.ebean.Tombstone;
import com.zextras.carbonio.files.dal.dao.ebean.VersionReservation;
import com.zextras.carbonio.files.dal.repositories.impl.Transactions;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.FileVersionSort;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.OverwriteLeaseRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.TombstoneRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.VersionReservationRepository;
import com.zextras.carbonio.files.rest.services.BlobKey;
import com.zextras.carbonio.files.rest.services.BlobLifecycle;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
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
 * {@link #purgeTrashedNodes}, {@link #purgeTombstones} and {@link #scheduledRun} are deliberately
 * NOT {@code @Transactional}: they orchestrate, interleaving short DB transactions with the
 * blocking storages calls. Each trashed tree is removed in one transaction that also writes the
 * tombstones of its blobs; the blobs of the whole run are deleted after those commits, one bulk
 * delete per owner. A crash or a failed delete leaves tombstones, which {@link #purgeTombstones}
 * retries.
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

  @Inject NodeRepository nodeRepository;
  @Inject FileVersionRepository fileVersionRepository;
  @Inject TombstoneRepository tombstoneRepository;
  @Inject VersionReservationRepository versionReservationRepository;
  @Inject OverwriteLeaseRepository overwriteLeaseRepository;
  @Inject BlobLifecycle blobLifecycle;
  @Inject Transactions transactions;

  @ConfigProperty(name = "carbonio.files.reservation-purge.expiry", defaultValue = "24h")
  Duration reservationExpiry;

  void purgeTrashedNodes(long retentionDays) {
    long retentionTimestamp = System.currentTimeMillis() - (retentionDays * 86400 * 1000);

    Map<String, List<BlobKey>> keysByOwner = new HashMap<>();
    for (Node top : nodeRepository.getTrashedTopNodes(retentionTimestamp)) {
      try {
        transactions
            .inNewTransaction(() -> purgeTrashedTree(top.getId(), retentionTimestamp))
            .forEach(
                (ownerId, keys) ->
                    keysByOwner.computeIfAbsent(ownerId, k -> new ArrayList<>()).addAll(keys));
      } catch (RuntimeException e) {
        logger.warn("Unable to purge trashed node {}: {}", top.getId(), e.getMessage());
      }
    }
    keysByOwner.forEach(blobLifecycle::deleteAndClear);
  }

  /**
   * Deletes the rows of a trashed tree and tombstones its blobs, unless the tree was restored or
   * trashed again since the run started.
   *
   * @return the tombstoned blob keys by owner
   */
  private Map<String, List<BlobKey>> purgeTrashedTree(String topId, long retentionTimestamp) {
    Optional<Node> top =
        nodeRepository
            .getNode(topId)
            .filter(node -> node.getParentId().filter(RootId.TRASH_ROOT::equals).isPresent())
            .filter(node -> node.getUpdatedAt() < retentionTimestamp);
    if (top.isEmpty()) {
      return Map.of();
    }
    List<Node> tree = nodeRepository.getSubtree(top.get());
    Map<String, List<BlobKey>> keysByOwner = new HashMap<>();
    tree.stream()
        .filter(node -> !node.getNodeType().equals(NodeType.FOLDER))
        .forEach(
            node -> {
              List<FileVersion> versions =
                  fileVersionRepository.getFileVersions(
                      node.getId(), List.of(FileVersionSort.VERSION_ASC));
              tombstoneRepository.createTombstonesBulk(versions, node.getOwnerId());
              keysByOwner
                  .computeIfAbsent(node.getOwnerId(), k -> new ArrayList<>())
                  .addAll(versions.stream().map(BlobKey::of).toList());
            });
    nodeRepository.deleteNodes(tree.stream().map(Node::getId).toList());
    logger.info("Purged {} trashed nodes from database", tree.size());
    return keysByOwner;
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

      Optional<Set<BlobKey>> failedKeys =
          blobLifecycle.delete(
              ownerId,
              ownerTombstones.stream()
                  .map(t -> new BlobKey(t.getNodeId(), t.getVersion()))
                  .toList());
      if (failedKeys.isEmpty()) {
        logger.warn(
            "purgeTombstones: unknown outcome for owner {}. Tombstones kept for next cycle.",
            ownerId);
        continue;
      }

      // Remove confirmed-deleted tombstones; apply retry-cap to failed ones.
      for (Tombstone t : ownerTombstones) {
        if (!failedKeys.get().contains(new BlobKey(t.getNodeId(), t.getVersion()))) {
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
   * Turns the version reservations never confirmed within {@link #reservationExpiry} of the
   * database clock (their save failed) into tombstones, which {@link #purgeTombstones} deletes from
   * the store. Claiming them first (PENDING → PURGING) makes a late confirmation fail instead of
   * creating a version whose blob is being deleted. A reservation whose version exists is only
   * removed: its blob is live.
   */
  void purgeExpiredReservations() {
    versionReservationRepository.claimExpired(reservationExpiry);

    transactions.inNewTransaction(
        () -> {
          for (VersionReservation reservation : versionReservationRepository.getPurging()) {
            if (fileVersionRepository
                .getFileVersion(reservation.getNodeId(), reservation.getVersion())
                .isPresent()) {
              logger.warn(
                  "purgeExpiredReservations: version {} of node {} exists, its blob is kept",
                  reservation.getVersion(),
                  reservation.getNodeId());
            } else {
              tombstoneRepository.createNewTombstone(
                  reservation.getNodeId(), reservation.getOwnerId(), reservation.getVersion());
            }
            versionReservationRepository.remove(reservation);
          }
          return null;
        });
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
