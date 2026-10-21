// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.interfaces;

import com.zextras.carbonio.files.dal.dao.ebean.VersionReservation;
import java.time.Duration;
import java.util.List;

/** Version numbers handed out to saves that are not confirmed yet. */
public interface VersionReservationRepository {

  /** Records a reservation as PENDING; joins the caller's transaction. */
  void reserve(String nodeId, int version, String ownerId, long reservedAt);

  /**
   * Removes a PENDING reservation; joins the caller's transaction, which must also create the
   * version.
   *
   * @return false when the reservation does not exist or the purge already claimed it
   */
  boolean consume(String nodeId, int version);

  /** Hands every PENDING reservation older than {@code expiry} (database clock) to the purge. */
  int claimExpired(Duration expiry);

  List<VersionReservation> getPurging();

  void remove(VersionReservation reservation);

  void recordFailedAttempt(VersionReservation reservation);
}
