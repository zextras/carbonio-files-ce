// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.restassured.RestAssured;
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
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Uploads whose body is still streaming while the server works: a client that disconnects mid-body
 * (legacy deleted the placeholder node), a placeholder renamed mid-body and an upload-version
 * slower than the JTA default timeout (legacy held the same transaction and row lock with no
 * timeout).
 */
@WithTestResource(
    value = StreamedUploadApiIT.ShortTransactionTimeout.class,
    scope = TestResourceScope.RESTRICTED_TO_CLASS)
class StreamedUploadApiIT extends AbstractFilesIT {

  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String REQUESTER_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final int CHUNK = 32 * 1024;

  public static class ShortTransactionTimeout implements QuarkusTestResourceLifecycleManager {
    @Override
    public Map<String, String> start() {
      return Map.of("quarkus.transaction-manager.default-transaction-timeout", "5s");
    }

    @Override
    public void stop() {}
  }

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
      Assertions.assertThat(awaitChildren(folderId, 1)).isEqualTo(1);
    }

    Assertions.assertThat(awaitChildren(folderId, 0)).isZero();
  }

  @Test
  void givenAnUploadVersionSlowerThanTheDefaultTransactionTimeoutThenTheVersionIsCommitted()
      throws Exception {
    String nodeId =
        seedFile("slow.txt", LOCAL_ROOT, "v1".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    int chunks = 8;

    String statusLine;
    try (Socket socket = new Socket("localhost", RestAssured.port)) {
      socket.setSoTimeout(30_000);
      OutputStream out = socket.getOutputStream();
      out.write(
          head(
                  "/upload-version",
                  "NodeId: " + nodeId + "\r\nOverwriteVersion: false",
                  "slow.txt",
                  (chunks + 2) * CHUNK)
              .getBytes(StandardCharsets.UTF_8));
      out.write(new byte[2 * CHUNK]);
      out.flush();
      for (int i = 0; i < chunks; i++) {
        Thread.sleep(900);
        out.write(new byte[CHUNK]);
        out.flush();
      }
      statusLine =
          new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
              .readLine();
    }

    Assertions.assertThat(statusLine).startsWith("HTTP/1.1 200");
    Assertions.assertThat(versionRows(nodeId)).containsExactly(1, 2);
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
      Assertions.assertThat(awaitChildren(folderId, 1)).isEqualTo(1);
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

  private static int awaitChildren(String folderId, int expected) throws Exception {
    long deadline = System.currentTimeMillis() + 15_000;
    int count = children(folderId);
    while (count != expected && System.currentTimeMillis() < deadline) {
      Thread.sleep(100);
      count = children(folderId);
    }
    return count;
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
