// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.services;

import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.VersionReservationRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.SQLException;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.OptionalLong;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Version numbers for the saves that create a new version (upload-version, cloneVersion): reserved
 * in a short transaction before the blob is written, confirmed in the transaction that creates the
 * version. No transaction or lock is held while the blob is written.
 */
@ApplicationScoped
public class VersionReservations {

  private static final Logger logger = LoggerFactory.getLogger(VersionReservations.class);

  /**
   * A version number reserved for one save; {@code blobOwnerId} is the account the blob is written
   * for.
   */
  public record Reservation(String nodeId, int version, long reservedAt, String blobOwnerId) {}

  private final NodeRepository nodeRepository;
  private final FileVersionRepository fileVersionRepository;
  private final VersionReservationRepository reservationRepository;

  @Inject
  public VersionReservations(
      NodeRepository nodeRepository,
      FileVersionRepository fileVersionRepository,
      VersionReservationRepository reservationRepository) {
    this.nodeRepository = nodeRepository;
    this.fileVersionRepository = fileVersionRepository;
    this.reservationRepository = reservationRepository;
  }

  /**
   * @return empty when the node does not exist
   */
  public Optional<Reservation> reserve(String nodeId, String blobOwnerId) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                nodeRepository
                    .reserveNextVersion(nodeId, System.currentTimeMillis())
                    .map(
                        slot -> {
                          reservationRepository.reserve(
                              nodeId, slot.version(), blobOwnerId, slot.reservedAt());
                          return new Reservation(
                              nodeId, slot.version(), slot.reservedAt(), blobOwnerId);
                        }));
  }

  /**
   * Registers an overwrite of {@code version}, in its own short transaction, before its blob is
   * written in place. Saves are ordered by registration: a version is rewritten in place only while
   * nothing was registered after it, otherwise the save must take a new number.
   *
   * @return the date of the rewritten version, taken now from the node clock (the registration
   *     order), or empty when the version cannot be rewritten in place: it is no longer current, a
   *     save was registered after it (in flight, published or failed), or it is already marked
   *     (another overwrite in progress, or one that never ended: its mark waits for the purge)
   */
  public OptionalLong markOverwrite(String nodeId, int version, String blobOwnerId) {
    try {
      return QuarkusTransaction.requiringNew()
          .call(
              () -> {
                if (!nodeRepository.claimInPlaceOverwrite(nodeId, version)) {
                  return OptionalLong.empty();
                }
                long notBefore =
                    fileVersionRepository
                        .latestDateBelow(nodeId, version + 1)
                        .map(latest -> latest + 1)
                        .orElse(0L);
                long date =
                    nodeRepository
                        .reserveTimestamp(nodeId, Math.max(System.currentTimeMillis(), notBefore))
                        .orElseThrow(() -> new NoSuchElementException("Node not found: " + nodeId))
                        .reservedAt();
                return reservationRepository.markOverwrite(nodeId, version, blobOwnerId, date)
                    ? OptionalLong.of(date)
                    : OptionalLong.empty();
              });
    } catch (RuntimeException e) {
      if (ExceptionUtils.getThrowableList(e).stream()
          .anyMatch(t -> t instanceof SQLException sql && "23505".equals(sql.getSQLState()))) {
        return OptionalLong.empty();
      }
      throw e;
    }
  }

  /**
   * Ends the overwrite inside the transaction that rewrites the version.
   *
   * @throws IllegalStateException when the mark is gone (removed by the purge as stale)
   */
  public void confirmOverwrite(String nodeId, int version) {
    if (!reservationRepository.releaseOverwrite(nodeId, version)) {
      throw new IllegalStateException(
          "Overwrite of version " + version + " of node " + nodeId + " is no longer marked");
    }
  }

  /** Releases the mark of an overwrite that failed; a release that fails waits for the purge. */
  public void releaseOverwrite(String nodeId, int version) {
    try {
      reservationRepository.releaseOverwrite(nodeId, version);
    } catch (RuntimeException e) {
      logger.warn(
          "Unable to release the overwrite mark of version {} of node {}: {}",
          version,
          nodeId,
          e.getMessage());
    }
  }

  /**
   * Consumes the reservation inside the transaction that creates the version, which then holds the
   * node row until it ends, so confirmations of one node never interleave.
   *
   * @return the version date: the reservation date, later than every version numbered below it
   *     (only a date written before the reservations, by another instance clock, can be later)
   * @throws IllegalStateException when the purge already claimed it: the version must not be
   *     created, its blob may be deleted at any moment
   * @throws NoSuchElementException when the node was deleted meanwhile
   */
  public long confirm(Reservation reservation) {
    String nodeId = reservation.nodeId();
    if (!reservationRepository.consume(nodeId, reservation.version())) {
      throw new IllegalStateException(
          "Reservation of version "
              + reservation.version()
              + " of node "
              + nodeId
              + " is no longer pending");
    }
    nodeRepository
        .reserveTimestamp(nodeId, reservation.reservedAt())
        .orElseThrow(() -> new NoSuchElementException("Node not found: " + nodeId));
    return fileVersionRepository
        .latestDateBelow(nodeId, reservation.version())
        .map(latest -> Math.max(reservation.reservedAt(), latest + 1))
        .orElse(reservation.reservedAt());
  }
}
