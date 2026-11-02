// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal;

import com.zextras.carbonio.files.FilesStackTestResource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class LockWaits {

  private LockWaits() {}

  public static int backendPid(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery("SELECT pg_backend_pid()")) {
      rs.next();
      return rs.getInt(1);
    }
  }

  public static void awaitBlockedBy(int blockedCount, Integer... holderPids) throws Exception {
    awaitBlockedOrDone(blockedCount, List.of(holderPids));
  }

  public static void awaitBlockedOrDone(
      int blockedCount, List<Integer> holderPids, CompletableFuture<?>... futures)
      throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    try (Connection observer =
            DriverManager.getConnection(FilesStackTestResource.POSTGRES_JDBC_URL, "test", "test");
        PreparedStatement blocked =
            observer.prepareStatement(
                "SELECT pid FROM pg_stat_activity WHERE pg_blocking_pids(pid) && ?::int[]")) {
      while (true) {
        Set<Integer> blockers = new LinkedHashSet<>(holderPids);
        Set<Integer> waiting = new LinkedHashSet<>();
        boolean grew = true;
        while (grew) {
          blocked.setArray(1, observer.createArrayOf("integer", blockers.toArray()));
          grew = false;
          try (ResultSet pids = blocked.executeQuery()) {
            while (pids.next()) {
              grew |= waiting.add(pids.getInt(1));
            }
          }
          blockers.addAll(waiting);
        }
        if (waiting.size() >= blockedCount) {
          return;
        }
        long done = Arrays.stream(futures).filter(CompletableFuture::isDone).count();
        if (futures.length > 0 && waiting.size() + done >= futures.length) {
          return;
        }
        if (System.nanoTime() > deadline) {
          throw new AssertionError(
              "Fewer than " + blockedCount + " backends waited for " + holderPids);
        }
      }
    }
  }
}
