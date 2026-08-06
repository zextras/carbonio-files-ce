// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.services;

/**
 * CE extension seam for account quota enforcement on the upload path.
 *
 * <p>CE ships a behaviour-preserving {@link NoOpQuotaChecker} default. The Advanced edition
 * supplies an {@code @Alternative @Priority(1)} implementation (same
 * {@code @Alternative @Priority(1)} precedent used by docs-connector-ce / -Advanced) that actually
 * enforces per-account quota.
 */
public interface QuotaChecker {

  /**
   * Verifies that storing {@code incomingBytes} more bytes for {@code accountId} would not exceed
   * the account quota. Implementations must throw if the quota would be exceeded; CE's default does
   * nothing.
   *
   * @param accountId the account whose quota the incoming blob is charged against (the node owner)
   * @param incomingBytes the size in bytes of the blob about to be stored
   */
  void ensureNotOverQuota(String accountId, long incomingBytes);
}
