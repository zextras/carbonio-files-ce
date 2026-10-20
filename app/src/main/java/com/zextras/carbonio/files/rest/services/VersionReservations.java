// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.services;

import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.VersionReservationRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
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
