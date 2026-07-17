// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.services;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * CE default {@link QuotaChecker}: a no-op. CE does not enforce account quota on upload, so this
 * preserves the current behaviour exactly. The Advanced edition overrides it with an
 * {@code @Alternative @Priority(1)} bean that performs the real check.
 */
@ApplicationScoped
public class NoOpQuotaChecker implements QuotaChecker {

  @Override
  public void ensureNotOverQuota(String accountId, long incomingBytes) {
    // no-op: CE does not enforce quota.
  }
}
