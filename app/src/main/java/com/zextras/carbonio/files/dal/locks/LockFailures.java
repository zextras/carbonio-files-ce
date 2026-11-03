// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.locks;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.Set;

public final class LockFailures {

  private static final String LOCK_NOT_AVAILABLE = "55P03";
  private static final String DEADLOCK_DETECTED = "40P01";

  private LockFailures() {}

  public static boolean isBusy(Throwable failure) {
    Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Throwable current = failure;
        current != null && visited.add(current);
        current = current.getCause()) {
      if (current instanceof NodeBusyException || isLockSqlFailure(current)) {
        return true;
      }
    }
    return false;
  }

  public static Optional<String> busyNodeId(Throwable failure) {
    Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Throwable current = failure;
        current != null && visited.add(current);
        current = current.getCause()) {
      if (current instanceof NodeBusyException busy) {
        return Optional.ofNullable(busy.getNodeId());
      }
    }
    return Optional.empty();
  }

  private static boolean isLockSqlFailure(Throwable throwable) {
    if (!(throwable instanceof SQLException)) {
      return false;
    }
    Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    for (SQLException sql = (SQLException) throwable;
        sql != null && visited.add(sql);
        sql = sql.getNextException()) {
      if (LOCK_NOT_AVAILABLE.equals(sql.getSQLState())
          || DEADLOCK_DETECTED.equals(sql.getSQLState())) {
        return true;
      }
    }
    return false;
  }
}
