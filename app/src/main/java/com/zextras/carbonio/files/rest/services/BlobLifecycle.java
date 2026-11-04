// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.services;

import com.zextras.carbonio.files.dal.repositories.impl.Transactions;
import com.zextras.carbonio.files.dal.repositories.interfaces.TombstoneRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.VersionReservationRepository;
import com.zextras.carbonio.files.exceptions.DependencyException;
import com.zextras.carbonio.files.rest.services.VersionReservations.Reservation;
import com.zextras.filestore.api.Filestore;
import com.zextras.filestore.api.UploadResponse;
import com.zextras.filestore.model.BulkDeleteRequestItem;
import com.zextras.filestore.model.BulkDeleteResponseItem;
import com.zextras.filestore.model.FilesIdentifier;
import com.zextras.filestore.model.IdentifierType;
import io.vavr.control.Try;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import java.io.InputStream;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The one path that deletes blobs and clears their tombstones. */
@ApplicationScoped
public class BlobLifecycle {

  private static final Logger logger = LoggerFactory.getLogger(BlobLifecycle.class);

  private final Filestore fileStore;
  private final TombstoneRepository tombstoneRepository;
  private final VersionReservationRepository reservationRepository;
  private final Transactions transactions;

  @Inject
  public BlobLifecycle(
      Filestore fileStore,
      TombstoneRepository tombstoneRepository,
      VersionReservationRepository reservationRepository,
      Transactions transactions) {
    this.fileStore = fileStore;
    this.tombstoneRepository = tombstoneRepository;
    this.reservationRepository = reservationRepository;
    this.transactions = transactions;
  }

  /**
   * @return empty when the outcome is unknown (outage, null, malformed body), else the keys the
   *     store reported as not deleted
   */
  public Optional<Set<BlobKey>> delete(String ownerId, Collection<BlobKey> keys) {
    if (keys.isEmpty()) {
      return Optional.of(Set.of());
    }
    List<BulkDeleteResponseItem> failed;
    try {
      failed =
          fileStore.bulkDelete(
              IdentifierType.files,
              ownerId,
              keys.stream()
                  .map(k -> BulkDeleteRequestItem.filesItem(k.nodeId(), k.version()))
                  .toList());
    } catch (Exception e) {
      // NPE = malformed body: unknown outcome, not a per-blob failure.
      logger.warn("Bulk delete for owner {} failed: {}. Outcome unknown.", ownerId, e.getMessage());
      return Optional.empty();
    }
    if (failed == null) {
      logger.warn("Bulk delete for owner {} returned null. Outcome unknown.", ownerId);
      return Optional.empty();
    }
    return Optional.of(
        keys.stream()
            .filter(k -> failed.stream().anyMatch(i -> matches(i, k)))
            .collect(Collectors.toSet()));
  }

  /** Runs after the commit that wrote the tombstones; never throws. */
  public void deleteAndClear(String ownerId, Collection<BlobKey> keys) {
    try {
      delete(ownerId, keys)
          .ifPresent(
              failed ->
                  keys.stream()
                      .filter(k -> !failed.contains(k))
                      .forEach(
                          k ->
                              tombstoneRepository.deleteTombstonesByNodeAndVersion(
                                  k.nodeId(), k.version())));
    } catch (RuntimeException e) {
      logger.warn("Unable to clear the tombstones of owner {}: {}", ownerId, e.getMessage());
    }
  }

  /** Tombstones the keys inside the caller's transaction; their blobs are deleted after it. */
  @Transactional(TxType.MANDATORY)
  public void tombstone(String ownerId, Collection<BlobKey> keys) {
    keys.forEach(k -> tombstoneRepository.createNewTombstone(k.nodeId(), ownerId, k.version()));
  }

  /**
   * Copies without overriding, then checks that the destination can be read back: a copy can answer
   * success without storing anything.
   */
  public Try<UploadResponse> copyVerified(FilesIdentifier source, FilesIdentifier destination) {
    return Try.of(() -> fileStore.copy(source, destination, false))
        .flatMap(
            response ->
                exists(destination.getNode(), destination.getVersion(), destination.getAccountId())
                    ? Try.success(response)
                    : Try.failure(
                        new DependencyException(
                            "Copy verification failed: version "
                                + destination.getVersion()
                                + " of node "
                                + destination.getNode()
                                + " is not accessible")));
  }

  /**
   * The blob of this reservation will never be published: tombstone it and try to delete it now.
   */
  public void discard(Reservation reservation) {
    discard(List.of(reservation));
  }

  /** Never throws; a reservation the purge claimed first is left to the purge. */
  public void discard(List<Reservation> reservations) {
    List<Reservation> mine;
    try {
      mine =
          transactions.inNewTransaction(
              () ->
                  reservations.stream()
                      .filter(
                          r -> {
                            if (!reservationRepository.consume(r.nodeId(), r.version())) {
                              return false;
                            }
                            tombstoneRepository.createNewTombstone(
                                r.nodeId(), r.blobOwnerId(), r.version());
                            return true;
                          })
                      .toList());
    } catch (RuntimeException e) {
      logger.warn(
          "Unable to tombstone {} reservations: {}. The purge will reclaim them.",
          reservations.size(),
          e.getMessage());
      return;
    }
    mine.stream()
        .collect(Collectors.groupingBy(Reservation::blobOwnerId))
        .forEach(
            (owner, rs) ->
                deleteAndClear(
                    owner, rs.stream().map(r -> new BlobKey(r.nodeId(), r.version())).toList()));
  }

  // It seems that manual checking is sometimes necessary
  public boolean exists(String nodeId, int version, String ownerId) {
    try {
      InputStream blobStream = fileStore.download(FilesIdentifier.of(nodeId, version, ownerId));
      blobStream.close();
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  static boolean matches(BulkDeleteResponseItem item, BlobKey key) {
    return key.nodeId().equals(item.getNode())
        && item.getVersion().map(v -> v == key.version()).orElse(true);
  }
}
