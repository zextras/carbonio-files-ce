// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.locks;

import com.zextras.carbonio.files.FilesStackTestResource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;

final class LockProbe {

  private LockProbe() {}

  static Connection holder() throws SQLException {
    Connection c =
        DriverManager.getConnection(FilesStackTestResource.POSTGRES_JDBC_URL, "test", "test");
    c.setAutoCommit(false);
    return c;
  }

  static void hold(Connection c, String nodeId, String mode) throws SQLException {
    try (PreparedStatement s = c.prepareStatement("SELECT 1 FROM node WHERE node_id = ? " + mode)) {
      s.setString(1, nodeId);
      s.executeQuery().close();
    }
  }

  static boolean canLockNow(String nodeId, String mode) throws SQLException {
    try (Connection c = holder()) {
      try (PreparedStatement s =
          c.prepareStatement("SELECT 1 FROM node WHERE node_id = ? " + mode + " NOWAIT")) {
        s.setString(1, nodeId);
        s.executeQuery().close();
        return true;
      } catch (SQLException e) {
        if ("55P03".equals(e.getSQLState())) {
          return false;
        }
        throw e;
      } finally {
        c.rollback();
      }
    }
  }

  static int backendPid(Connection c) throws SQLException {
    try (PreparedStatement s = c.prepareStatement("SELECT pg_backend_pid()");
        ResultSet r = s.executeQuery()) {
      r.next();
      return r.getInt(1);
    }
  }

  static void awaitBlockedBy(Connection holder) throws Exception {
    int holderPid = backendPid(holder);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    try (Connection observer = holder();
        PreparedStatement blocked =
            observer.prepareStatement(
                "SELECT pid FROM pg_stat_activity WHERE pg_blocking_pids(pid) && ?::int[]")) {
      while (true) {
        blocked.setArray(1, observer.createArrayOf("integer", new Object[] {holderPid}));
        try (ResultSet pids = blocked.executeQuery()) {
          if (pids.next()) {
            return;
          }
        }
        observer.rollback();
        if (System.nanoTime() > deadline) {
          throw new AssertionError("No backend waited for " + holderPid);
        }
      }
    }
  }
}
