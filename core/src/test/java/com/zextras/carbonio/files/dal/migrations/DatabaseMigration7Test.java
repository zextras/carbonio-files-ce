// SPDX-FileCopyrightText: 2025 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.migrations;

import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Test the database migration #7 using a real PostgreSQL instance initialized up to version 6 of the scripts
 */
class DatabaseMigration7Test {
  static PostgreSQLContainer<?> postgreSQLContainer;

  @BeforeAll
  static void init() {
    postgreSQLContainer = new PostgreSQLContainer<>("postgres:16.6");
    postgreSQLContainer.start();
  }

  @BeforeEach
  void setUp() {
    IntStream.range(1, 7).forEach(this::migrateDatabase);
  }

  @AfterEach
  void cleanUp() throws SQLException {
    String dropSchemaStatement = "DROP SCHEMA public CASCADE;";
    String createEmptySchemaStatement = "CREATE SCHEMA public;";
    try (Statement statement = getDatabaseConnection().createStatement()) {
      statement.execute(dropSchemaStatement);
      statement.execute(createEmptySchemaStatement);
    }
  }

  void migrateDatabase(int dbVersion) {

    ClassLoader classLoader = getClass().getClassLoader();
    String sqlFilesToExecute = String.format("sql/postgresql_%d.sql", dbVersion);

    String data = new BufferedReader(new InputStreamReader(
        Objects.requireNonNull(classLoader.getResourceAsStream(sqlFilesToExecute)),
        StandardCharsets.UTF_8
    ))
        .lines()
        .collect(Collectors.joining("\n"));

    try (Statement statement = getDatabaseConnection().createStatement()) {
      statement.execute(data);
    } catch (Exception exception) {
      throw new RuntimeException(exception);
    }
  }

  Connection getDatabaseConnection() throws SQLException {
    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setUrl(postgreSQLContainer.getJdbcUrl());
    dataSource.setUser(postgreSQLContainer.getUsername());
    dataSource.setPassword(postgreSQLContainer.getPassword());
    return dataSource.getConnection();
  }

  void checkDatabaseVersion(int databaseVersion) throws SQLException {
    try (Statement statement = getDatabaseConnection().createStatement()) {
      try (ResultSet dbInfo = statement.executeQuery("SELECT * FROM DB_INFO;")) {
        dbInfo.next();
        Assertions.assertThat(dbInfo.getInt("version")).isEqualTo(databaseVersion);
      }
    }
  }

  void insertNode(String nodeId, String nodeName, NodeType nodeType) throws SQLException {
    String insertNodeStatementTemplate = "INSERT INTO node VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?);";

    try (PreparedStatement ps = getDatabaseConnection().prepareStatement(insertNodeStatementTemplate)) {
      ps.setString(1, "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
      ps.setString(2, nodeId);
      ps.setString(3, "LOCAL_ROOT");
      ps.setString(4, nodeName);
      ps.setString(5, nodeType.toString());
      ps.setInt(6, 1);
      ps.setString(7, "");
      ps.setInt(8, 0);
      ps.setLong(9, 1L);
      ps.setLong(10, 1L);
      ps.setString(11, "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
      ps.setString(12, "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
      ps.setInt(13, 1);
      ps.setString(14, "LOCAL_ROOT");
      ps.setLong(15, 1L);

      ps.execute();
    }
  }

  void insertNodeVersion(String nodeId, String mimeType, int version) throws SQLException {
    String insertNodeStatementTemplate = "INSERT INTO revision VALUES (?,?,?,?,?,?,?);";

    try (PreparedStatement ps = getDatabaseConnection().prepareStatement(insertNodeStatementTemplate)) {
      ps.setString(1, nodeId);
      ps.setInt(2, version);
      ps.setString(3, mimeType);
      ps.setLong(4, 1L);
      ps.setString(5, "");
      ps.setString(6, "");
      ps.setLong(7, 1L);

      ps.execute();
    }
  }

  @Test
  void givenADbVersion6AndANodeWithMsgExtensionTheMigrationShouldUpdateTheNodeWithTheRightNodeTypeAndMimeType()
      throws SQLException {
    // Given
    checkDatabaseVersion(6);

    insertNode("00000000-0000-0000-0000-000000000000", "test0.msg", NodeType.OTHER);
    insertNodeVersion("00000000-0000-0000-0000-000000000000", "application/octet-stream", 1);
    insertNodeVersion("00000000-0000-0000-0000-000000000000", "application/octet-stream", 2);

    insertNode("00000000-0000-0000-0000-000000000001", "test1.kkk", NodeType.OTHER);
    insertNodeVersion("00000000-0000-0000-0000-000000000001", "application/octet-stream", 1);
    insertNodeVersion("00000000-0000-0000-0000-000000000001", "application/octet-stream", 2);

    insertNode("00000000-0000-0000-0000-000000000002", "test2.txt", NodeType.TEXT);
    insertNodeVersion("00000000-0000-0000-0000-000000000002", "text/plain", 1);
    insertNodeVersion("00000000-0000-0000-0000-000000000002", "text/plain", 2);

    insertNode("00000000-0000-0000-0000-000000000003", "test3.eml", NodeType.OTHER);
    insertNodeVersion("00000000-0000-0000-0000-000000000003", "message/rfc822", 1);
    insertNodeVersion("00000000-0000-0000-0000-000000000003", "message/rfc822", 2);

    // When
    migrateDatabase(7);

    // Then
    checkDatabaseVersion(7);

    try (Statement statement = getDatabaseConnection().createStatement()) {
      String selectNodeStatement = "SELECT * FROM node WHERE node_type = 'MESSAGE' ORDER BY node_id";
      try (ResultSet node = statement.executeQuery(selectNodeStatement)) {
        // test0.msg
        node.next();
        Assertions
            .assertThat(node.getString("node_id"))
            .isEqualTo("00000000-0000-0000-0000-000000000000");
        Assertions.assertThat(node.getString("name")).isEqualTo("test0.msg");
        Assertions.assertThat(node.getString("node_type")).isEqualTo("MESSAGE");
        Assertions.assertThat(node.getString("node_type")).isEqualTo("MESSAGE");

        // test3.eml
        node.next();
        Assertions
            .assertThat(node.getString("node_id"))
            .isEqualTo("00000000-0000-0000-0000-000000000003");
        Assertions.assertThat(node.getString("name")).isEqualTo("test3.eml");
        Assertions.assertThat(node.getString("node_type")).isEqualTo("MESSAGE");

        // No more results
        node.next();
        Assertions.assertThat(node.next()).isFalse();
      }
    }

    try (Statement statement = getDatabaseConnection().createStatement()) {
      String selectNodeStatement =
          "SELECT * FROM revision WHERE mime_type = 'application/vnd.ms-outlook' ORDER BY version;";

      try (ResultSet fileVersion = statement.executeQuery(selectNodeStatement)) {
        // test0.msg version 1
        fileVersion.next();
        Assertions
            .assertThat(fileVersion.getString("node_id"))
            .isEqualTo("00000000-0000-0000-0000-000000000000");
        Assertions.assertThat(fileVersion.getInt("version")).isEqualTo(1);
        Assertions
            .assertThat(fileVersion.getString("mime_type"))
            .isEqualTo("application/vnd.ms-outlook");

        // test0.msg version 2
        fileVersion.next();
        Assertions
            .assertThat(fileVersion.getString("node_id"))
            .isEqualTo("00000000-0000-0000-0000-000000000000");
        Assertions.assertThat(fileVersion.getInt("version")).isEqualTo(2);
        Assertions
            .assertThat(fileVersion.getString("mime_type"))
            .isEqualTo("application/vnd.ms-outlook");

        // No more results
        fileVersion.next();
        Assertions.assertThat(fileVersion.next()).isFalse();
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"u8msg", "u8dsn", "u8mdn", "u8hdr", "eml", "mail", "art", "msg"})
  void givenADbVersion6AndANodeWithASpecificExtensionsTheMigrationShouldUpdateTheNodeWithTheMessageNodeType(
      String nodeExtension
  ) throws SQLException {
    // Given
    checkDatabaseVersion(6);

    insertNode("00000000-0000-0000-0000-000000000000", "test0." + nodeExtension, NodeType.OTHER);
    insertNode("00000000-0000-0000-0000-000000000001", "test1.kkk", NodeType.OTHER);
    insertNode("00000000-0000-0000-0000-000000000002", "test2.txt", NodeType.TEXT);

    // When
    migrateDatabase(7);

    // Then
    checkDatabaseVersion(7);

    try (Statement statement = getDatabaseConnection().createStatement()) {
      try (ResultSet node = statement.executeQuery("SELECT * FROM NODE WHERE node_type = 'MESSAGE';")) {
        node.next();

        Assertions
            .assertThat(node.getString("node_id"))
            .isEqualTo("00000000-0000-0000-0000-000000000000");
        Assertions.assertThat(node.getString("name")).isEqualTo("test0." + nodeExtension);
        Assertions.assertThat(node.getString("node_type")).isEqualTo("MESSAGE");

        Assertions.assertThat(node.next()).isFalse();
      }
    }
  }
}
