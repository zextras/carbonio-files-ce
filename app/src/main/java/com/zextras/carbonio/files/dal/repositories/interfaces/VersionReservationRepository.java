// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.interfaces;

import com.zextras.carbonio.files.dal.dao.ebean.VersionReservation;
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

  /**
   * Marks an overwrite of an existing version as in progress; joins the caller's transaction, whose
   * commit fails on the primary key if another overwrite of that version holds the mark.
   */
  void markOverwrite(String nodeId, int version, String ownerId, long reservedAt);

  /**
   * Removes the overwrite mark.
   *
   * @return false when it does not exist (released, or removed by the purge as stale)
   */
  boolean releaseOverwrite(String nodeId, int version);

  /** Hands every PENDING reservation older than {@code reservedBefore} to the purge. */
  int claimExpired(long reservedBefore);

  /** Removes the overwrite marks left by saves that never ended (their blob stays). */
  int removeStaleOverwrites(long reservedBefore);

  List<VersionReservation> getPurging();

  void remove(VersionReservation reservation);

  void recordFailedAttempt(VersionReservation reservation);
}
