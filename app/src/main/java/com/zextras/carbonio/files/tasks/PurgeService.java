// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.tasks;

import com.google.common.collect.Lists;
import com.zextras.carbonio.files.Constants.Config;
import com.zextras.carbonio.files.Constants.Db.RootId;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.Tombstone;
import com.zextras.carbonio.files.dal.dao.ebean.TombstonePK;
import com.zextras.carbonio.files.dal.dao.ebean.VersionReservation;
import com.zextras.carbonio.files.dal.repositories.impl.Transactions;
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
  static final int PURGE_BATCH_SIZE = 500;

  private final NodeRepository nodeRepository;
  private final FileVersionRepository fileVersionRepository;
  private final TombstoneRepository tombstoneRepository;
  private final VersionReservationRepository versionReservationRepository;
  private final OverwriteLeaseRepository overwriteLeaseRepository;
  private final BlobLifecycle blobLifecycle;
  private final Transactions transactions;
  private final Duration reservationExpiry;

  @Inject
  public PurgeService(
      NodeRepository nodeRepository,
      FileVersionRepository fileVersionRepository,
      TombstoneRepository tombstoneRepository,
      VersionReservationRepository versionReservationRepository,
      OverwriteLeaseRepository overwriteLeaseRepository,
      BlobLifecycle blobLifecycle,
      Transactions transactions,
      @ConfigProperty(name = "carbonio.files.reservation-purge.expiry", defaultValue = "24h")
          Duration reservationExpiry) {
    this.nodeRepository = nodeRepository;
    this.fileVersionRepository = fileVersionRepository;
    this.tombstoneRepository = tombstoneRepository;
    this.versionReservationRepository = versionReservationRepository;
    this.overwriteLeaseRepository = overwriteLeaseRepository;
    this.blobLifecycle = blobLifecycle;
    this.transactions = transactions;
    this.reservationExpiry = reservationExpiry;
  }

  void purgeTrashedNodes(long retentionDays) {
    long retentionTimestamp = System.currentTimeMillis() - (retentionDays * 86400 * 1000);

    Map<String, List<BlobKey>> keysByOwner = new HashMap<>();
    for (Node top : nodeRepository.getTrashedTopNodes(retentionTimestamp)) {
      try {
        Optional<Node> expiredTop = expiredTrashedTop(top.getId(), retentionTimestamp);
        if (expiredTop.isEmpty()) {
          continue;
        }
        List<String> fileIds = nodeRepository.getFileIdsInSubtree(top.getId());
        for (List<String> batch : Lists.partition(fileIds, PURGE_BATCH_SIZE)) {
          collect(
              keysByOwner,
              transactions.inNewTransaction(
                  () -> purgeTrashedFiles(top.getId(), retentionTimestamp, batch)));
        }
        collect(
            keysByOwner,
            transactions.inNewTransaction(() -> purgeTrashedTree(top.getId(), retentionTimestamp)));
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
    Optional<Node> top = expiredTrashedTop(topId, retentionTimestamp);
    if (top.isEmpty()) {
      return Map.of();
    }
    Map<String, List<BlobKey>> keysByOwner = nodeRepository.deleteSubtrees(List.of(topId));
    logger.info(
        "Purged trashed tree {} from database: {} revisions tombstoned",
        topId,
        keysByOwner.values().stream().mapToInt(List::size).sum());
    return keysByOwner;
  }

  /** Purges a batch of files of a trashed tree, so that a large tree never needs one long tx. */
  private Map<String, List<BlobKey>> purgeTrashedFiles(
      String topId, long retentionTimestamp, List<String> fileIds) {
    if (expiredTrashedTop(topId, retentionTimestamp).isEmpty()) {
      return Map.of();
    }
    return nodeRepository.deleteSubtrees(nodeRepository.filterIdsUnderAncestor(topId, fileIds));
  }

  private Optional<Node> expiredTrashedTop(String topId, long retentionTimestamp) {
    return nodeRepository
        .getNode(topId)
        .filter(node -> node.getParentId().filter(RootId.TRASH_ROOT::equals).isPresent())
        .filter(node -> node.getUpdatedAt() < retentionTimestamp);
  }

  private static void collect(
      Map<String, List<BlobKey>> keysByOwner, Map<String, List<BlobKey>> purged) {
    purged.forEach(
        (ownerId, keys) ->
            keysByOwner.computeIfAbsent(ownerId, k -> new ArrayList<>()).addAll(keys));
  }

  void purgeTombstones() {
    List<Tombstone> tombstones = tombstoneRepository.getTombstones();
    if (tombstones.isEmpty()) {
      return;
    }

    // Group by ownerId — bulkDelete requires userId to select host.
    Map<String, List<Tombstone>> byOwner =
        tombstones.stream().collect(Collectors.groupingBy(Tombstone::getOwnerId));

    byOwner.forEach(
        (ownerId, ownerTombstones) ->
            Lists.partition(ownerTombstones, BlobLifecycle.DELETE_BATCH_SIZE)
                .forEach(batch -> purgeTombstoneBatch(ownerId, batch)));
  }

  private void purgeTombstoneBatch(String ownerId, List<Tombstone> ownerTombstones) {
    Optional<Set<BlobKey>> failedKeys =
        blobLifecycle.delete(
            ownerId,
            ownerTombstones.stream().map(t -> new BlobKey(t.getNodeId(), t.getVersion())).toList());
    if (failedKeys.isEmpty()) {
      logger.warn(
          "purgeTombstones: unknown outcome for owner {}. Tombstones kept for next cycle.",
          ownerId);
      return;
    }

    // Remove confirmed-deleted tombstones; apply retry-cap to failed ones.
    List<TombstonePK> toDelete = new ArrayList<>();
    List<TombstonePK> toCount = new ArrayList<>();
    for (Tombstone t : ownerTombstones) {
      TombstonePK id = new TombstonePK(t.getNodeId(), t.getVersion());
      if (!failedKeys.get().contains(new BlobKey(t.getNodeId(), t.getVersion()))) {
        toDelete.add(id);
      } else if (t.getAttempts() + 1 >= MAX_TOMBSTONE_RETRIES) {
        // Genuine per-blob PowerStore rejection on the last allowed attempt: drop the tombstone.
        logger.warn(
            "purgeTombstones: giving up on blob nodeId={} version={} after {} attempts; "
                + "accepting orphan and removing tombstone.",
            t.getNodeId(),
            t.getVersion(),
            t.getAttempts() + 1);
        toDelete.add(id);
      } else {
        // Genuine per-blob PowerStore rejection: count an attempt toward the retry cap.
        toCount.add(id);
      }
    }
    if (!toDelete.isEmpty()) {
      tombstoneRepository.deleteTombstones(toDelete);
    }
    if (!toCount.isEmpty()) {
      tombstoneRepository.incrementAttempts(toCount);
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
