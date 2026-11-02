// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.locks;

import java.sql.SQLException;
import java.util.Set;

public final class LockConflicts {

  public static final int MAX_RETRIES = 2;

  private static final Set<String> RETRYABLE_STATES = Set.of("55P03", "40P01");

  private LockConflicts() {}

  public static boolean isRetryable(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof LockSetChangedException) {
        return true;
      }
      if (cause instanceof SQLException sql) {
        if (RETRYABLE_STATES.contains(sql.getSQLState())) {
          return true;
        }
        SQLException next = sql.getNextException();
        if (next != null && next != sql && isRetryable(next)) {
          return true;
        }
      }
      if (cause.getCause() == cause) {
        return false;
      }
    }
    return false;
  }
}
