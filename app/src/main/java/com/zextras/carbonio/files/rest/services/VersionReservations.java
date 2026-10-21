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
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Version numbers for the saves that create a new version (upload-version, cloneVersion): reserved
 * in a short transaction before the blob is written, confirmed in the transaction that creates the
 * version. No transaction or lock is held while the blob is written.
 */
@ApplicationScoped
public class VersionReservations {

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
