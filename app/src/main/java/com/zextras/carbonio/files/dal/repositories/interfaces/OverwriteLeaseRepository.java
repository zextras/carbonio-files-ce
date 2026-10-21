// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.interfaces;

import com.zextras.carbonio.files.dal.dao.ebean.OverwriteLease;
import java.time.Duration;
import java.util.Optional;

/** Leases of the overwrites in progress, one per node; expiry by the database clock. */
public interface OverwriteLeaseRepository {

  /**
   * Takes the lease of the node when nobody holds it or it expired; joins the caller's transaction,
   * which must hold the node row so that two callers never take it together.
   *
   * @return false when another overwrite holds a lease not expired yet
   */
  boolean take(String nodeId, int version, String token, long registeredAt, Duration duration);

  /**
   * Releases the lease when it still carries {@code token}; joins the caller's transaction.
   *
   * @return false when the lease expired and another overwrite took it (or it is gone)
   */
  boolean release(String nodeId, String token);

  /** Removes the leases expired for longer than {@code expiredFor}. */
  int removeExpired(Duration expiredFor);

  Optional<OverwriteLease> find(String nodeId);
}
