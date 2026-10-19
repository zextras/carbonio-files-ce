// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
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
