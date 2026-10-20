// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal;

import static org.assertj.core.api.Assertions.assertThat;

import com.zextras.carbonio.files.FilesStackTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/** Runs the real V12 migration on a scratch schema seeded at V11 with pre-existing revisions. */
@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class VersionReservationMigrationDalTest {

  private static final String FILE = "aaaaaaaa-0000-0000-0000-000000000001";
  private static final String FILE_WITH_STRAY_REVISION = "aaaaaaaa-0000-0000-0000-000000000002";
  private static final String FOLDER = "aaaaaaaa-0000-0000-0000-000000000003";

  @Test
  void v12StartsTheVersionCounterAndTheReservationClockFromTheExistingRevisions() throws Exception {
    String schema = "mig_v12_" + UUID.randomUUID().toString().replace("-", "");
    try (Connection connection = connect()) {
      try {
        migrate(schema, "11");
        insertNode(connection, schema, FILE, "TEXT", 3);
        insertRevisions(connection, schema, FILE, Map.of(1, 100L, 2, 200L, 3, 300L));
        insertNode(connection, schema, FILE_WITH_STRAY_REVISION, "TEXT", 2);
        insertRevisions(
            connection, schema, FILE_WITH_STRAY_REVISION, Map.of(1, 10L, 2, 20L, 5, 50L));
        insertNode(connection, schema, FOLDER, "FOLDER", null);

        migrate(schema, "12");

        assertThat(counters(connection, schema))
            .containsEntry(FILE, List.of(3L, 300L))
            .containsEntry(FILE_WITH_STRAY_REVISION, List.of(5L, 50L))
            .containsEntry(FOLDER, List.of(0L, 0L));
        assertThat(count(connection, schema + ".version_reservation")).isZero();
      } finally {
        try (Statement statement = connection.createStatement()) {
          statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
      }
    }
  }

  private static Connection connect() throws SQLException {
    return DriverManager.getConnection(FilesStackTestResource.POSTGRES_JDBC_URL, "test", "test");
  }

  private static void migrate(String schema, String target) {
    Flyway.configure()
        .dataSource(FilesStackTestResource.POSTGRES_JDBC_URL, "test", "test")
        .schemas(schema)
        .locations("classpath:db/migration")
        .target(target)
        .load()
        .migrate();
  }

  private static void insertNode(
      Connection connection, String schema, String nodeId, String type, Integer currentVersion)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO "
                + schema
                + ".node (owner_id, node_id, folder_id, name, node_type, node_category,"
                + " description, creation_timestamp, updated_timestamp, current_version,"
                + " ancestor_ids, size) VALUES ('owner', ?, 'LOCAL_ROOT', ?, ?, 1, '', 1, 1, ?,"
                + " 'LOCAL_ROOT', 0)")) {
      statement.setString(1, nodeId);
      statement.setString(2, nodeId);
      statement.setString(3, type);
      statement.setObject(4, currentVersion);
      statement.executeUpdate();
    }
  }

  private static void insertRevisions(
      Connection connection, String schema, String nodeId, Map<Integer, Long> timestampByVersion)
      throws SQLException {
    for (Map.Entry<Integer, Long> revision : timestampByVersion.entrySet()) {
      try (PreparedStatement statement =
          connection.prepareStatement(
              "INSERT INTO "
                  + schema
                  + ".revision (node_id, version, mime_type, size, digest, editor_id, timestamp)"
                  + " VALUES (?, ?, 'text/plain', 1, 'digest', 'owner', ?)")) {
        statement.setString(1, nodeId);
        statement.setInt(2, revision.getKey());
        statement.setLong(3, revision.getValue());
        statement.executeUpdate();
      }
    }
  }

  private static Map<String, List<Long>> counters(Connection connection, String schema)
      throws SQLException {
    Map<String, List<Long>> counters = new HashMap<>();
    try (Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT node_id, last_version, last_reserved_at FROM " + schema + ".node")) {
      while (rows.next()) {
        counters.put(rows.getString(1).trim(), List.of(rows.getLong(2), rows.getLong(3)));
      }
    }
    return counters;
  }

  private static long count(Connection connection, String table) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
      rows.next();
      return rows.getLong(1);
    }
  }
}
