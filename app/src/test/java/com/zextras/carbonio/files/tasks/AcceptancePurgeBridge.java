// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.tasks;

/**
 * Same-package bridge that lets the acceptance seam ({@code QuarkusTestDataAccess#runPurge()},
 * which lives in a different package) trigger one full purge cycle. {@link
 * PurgeService#scheduledRun()} is package-private (and {@code @Transactional}); calling it on the
 * Arc client proxy from here keeps the transaction interceptor active — the sanctioned way to run
 * the same behaviour the {@code @Scheduled} timer would, which is disabled in {@code %test}.
 */
public final class AcceptancePurgeBridge {

  private AcceptancePurgeBridge() {}

  public static void run(PurgeService purgeService) {
    purgeService.scheduledRun();
  }
}
