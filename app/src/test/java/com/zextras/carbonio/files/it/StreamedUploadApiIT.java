// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Uploads whose body is still streaming while the server works: a client that disconnects mid-body
 * (legacy deleted the placeholder node) and a placeholder renamed mid-body.
 */
class StreamedUploadApiIT extends AbstractFilesIT {

  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String REQUESTER_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final int CHUNK = 32 * 1024;

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", REQUESTER_ID);
  }

  @Test
  void givenTheClientDisconnectsMidBodyThenThePlaceholderNodeIsRemoved() throws Exception {
    String folderId = seedFolder("streamed-upload", LOCAL_ROOT, REQUESTER_COOKIE);

    try (Socket socket = new Socket("localhost", RestAssured.port)) {
      OutputStream out = socket.getOutputStream();
      out.write(
          head("/upload", "ParentId: " + folderId, "disconnect.bin", 64 * CHUNK)
              .getBytes(StandardCharsets.UTF_8));
      for (int i = 0; i < 8; i++) {
        out.write(new byte[CHUNK]);
      }
      out.flush();
      eventually("placeholder created", () -> children(folderId) == 1);
    }

    eventually("placeholder removed", () -> children(folderId) == 0);
  }

  @Test
  void givenThePlaceholderIsRenamedWhileTheBodyStreamsThenTheUploadKeepsTheNewName()
      throws Exception {
    String folderId = seedFolder("streamed-upload-rename", LOCAL_ROOT, REQUESTER_COOKIE);

    String renamed;
    String statusLine;
    try (Socket socket = new Socket("localhost", RestAssured.port)) {
      socket.setSoTimeout(30_000);
      OutputStream out = socket.getOutputStream();
      out.write(
          head("/upload", "ParentId: " + folderId, "placeholder.bin", 2 * CHUNK)
              .getBytes(StandardCharsets.UTF_8));
      out.write(new byte[CHUNK]);
      out.flush();
      eventually("placeholder created", () -> children(folderId) == 1);
      graphql(
          GraphqlCommandBuilder.aMutationBuilder("updateNode")
              .withString("node_id", onlyChild(folderId, "node_id"))
              .withString("name", "renamed")
              .withWantedResultFormat("{ id }")
              .build(),
          REQUESTER_COOKIE);
      renamed = onlyChild(folderId, "name");
      Assertions.assertThat(renamed).isNotEqualTo("placeholder.bin");

      out.write(new byte[CHUNK]);
      out.flush();
      statusLine =
          new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
              .readLine();
    }

    Assertions.assertThat(statusLine).startsWith("HTTP/1.1 200");
    Assertions.assertThat(onlyChild(folderId, "name")).isEqualTo(renamed);
  }

  @Test
  void givenARenameWhileAnUploadVersionStreamsThenTheNewVersionStaysCurrent() throws Exception {
    String folderId = seedFolder("streamed-upload-version-rename", LOCAL_ROOT, REQUESTER_COOKIE);
    String nodeId =
        seedFile(
            "versioned.txt",
            folderId,
            "content".getBytes(StandardCharsets.UTF_8),
            REQUESTER_COOKIE);

    CompletableFuture<Response> rename;
    String statusLine;
    try (Socket socket = new Socket("localhost", RestAssured.port)) {
      socket.setSoTimeout(30_000);
      OutputStream out = socket.getOutputStream();
      out.write(
          head(
                  "/upload-version",
                  "NodeId: " + nodeId + "\r\nOverwriteVersion: false",
                  "versioned.txt",
                  2 * CHUNK)
              .getBytes(StandardCharsets.UTF_8));
      out.write(new byte[CHUNK]);
      out.flush();
      eventually("upload-version holds the node row", () -> nodeRowLocked(nodeId));

      rename =
          CompletableFuture.supplyAsync(
              () ->
                  graphql(
                      GraphqlCommandBuilder.aMutationBuilder("updateNode")
                          .withString("node_id", nodeId)
                          .withString("name", "renamed")
                          .withWantedResultFormat("{ id }")
                          .build(),
                      REQUESTER_COOKIE));
      eventually("rename waits for the node row", () -> lockWaiters() >= 1);

      out.write(new byte[CHUNK]);
      out.flush();
      statusLine =
          new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
              .readLine();
    }

    Assertions.assertThat(statusLine).startsWith("HTTP/1.1 200");
    Response renameResponse = rename.get(60, TimeUnit.SECONDS);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(renameResponse.getBody().asString()))
        .isEmpty();
    try (Connection connection = jdbcConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT name, current_version FROM node WHERE node_id = ?")) {
      statement.setString(1, nodeId);
      try (ResultSet row = statement.executeQuery()) {
        Assertions.assertThat(row.next()).isTrue();
        Assertions.assertThat(row.getString("name")).startsWith("renamed");
        Assertions.assertThat(row.getInt("current_version")).isEqualTo(2);
      }
    }
    Assertions.assertThat(versionRows(nodeId)).containsExactly(1, 2);
  }

  private static boolean nodeRowLocked(String nodeId) throws SQLException {
    try (Connection connection = jdbcConnection()) {
      connection.setAutoCommit(false);
      try (PreparedStatement statement =
          connection.prepareStatement(
              "SELECT 1 FROM node WHERE node_id = ? FOR NO KEY UPDATE NOWAIT")) {
        statement.setString(1, nodeId);
        statement.executeQuery().close();
        connection.rollback();
        return false;
      } catch (SQLException e) {
        if ("55P03".equals(e.getSQLState())) {
          return true;
        }
        throw e;
      }
    }
  }

  private static int lockWaiters() throws SQLException {
    try (Connection connection = jdbcConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT count(*) FROM pg_stat_activity WHERE cardinality(pg_blocking_pids(pid)) >"
                    + " 0");
        ResultSet resultSet = statement.executeQuery()) {
      resultSet.next();
      return resultSet.getInt(1);
    }
  }

  private static String head(String path, String extraHeaders, String filename, int length) {
    return "POST "
        + path
        + " HTTP/1.1\r\nHost: localhost\r\nCookie: "
        + REQUESTER_COOKIE
        + "\r\nFilename: "
        + Base64.getEncoder().encodeToString(filename.getBytes(StandardCharsets.UTF_8))
        + "\r\n"
        + extraHeaders
        + "\r\nContent-Type: application/octet-stream\r\nContent-Length: "
        + length
        + "\r\n\r\n";
  }

  private static String onlyChild(String folderId, String column) throws SQLException {
    try (Connection connection = jdbcConnection();
        PreparedStatement statement =
            connection.prepareStatement("SELECT " + column + " FROM node WHERE folder_id = ?")) {
      statement.setString(1, folderId);
      try (ResultSet resultSet = statement.executeQuery()) {
        Assertions.assertThat(resultSet.next()).isTrue();
        String value = resultSet.getString(1).trim();
        Assertions.assertThat(resultSet.next()).isFalse();
        return value;
      }
    }
  }

  private static int children(String folderId) throws SQLException {
    try (Connection connection = jdbcConnection();
        PreparedStatement statement =
            connection.prepareStatement("SELECT COUNT(*) FROM node WHERE folder_id = ?")) {
      statement.setString(1, folderId);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getInt(1);
      }
    }
  }
}
