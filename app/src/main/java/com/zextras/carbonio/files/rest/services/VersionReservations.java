// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.services;

import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.VersionReservationRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.SQLException;
import java.util.Optional;
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
  private final VersionReservationRepository reservationRepository;

  @Inject
  public VersionReservations(
      NodeRepository nodeRepository, VersionReservationRepository reservationRepository) {
    this.nodeRepository = nodeRepository;
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
   * Marks an overwrite of {@code version} as in progress, in its own short transaction, before its
   * blob is written in place.
   *
   * @return false when another overwrite of that version holds the mark
   */
  public boolean markOverwrite(String nodeId, int version, String blobOwnerId) {
    try {
      QuarkusTransaction.requiringNew()
          .run(
              () ->
                  reservationRepository.markOverwrite(
                      nodeId, version, blobOwnerId, System.currentTimeMillis()));
      return true;
    } catch (RuntimeException e) {
      if (ExceptionUtils.getThrowableList(e).stream()
          .anyMatch(t -> t instanceof SQLException sql && "23505".equals(sql.getSQLState()))) {
        return false;
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
   * Consumes the reservation inside the transaction that creates the version.
   *
   * @throws IllegalStateException when the purge already claimed it: the version must not be
   *     created, its blob may be deleted at any moment
   */
  public void confirm(Reservation reservation) {
    if (!reservationRepository.consume(reservation.nodeId(), reservation.version())) {
      throw new IllegalStateException(
          "Reservation of version "
              + reservation.version()
              + " of node "
              + reservation.nodeId()
              + " is no longer pending");
    }
  }
}
