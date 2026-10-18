// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.RestAssured;
import io.restassured.response.Response;
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
 * A client that disconnects mid-body fails the storages upload while its request body is still
 * being written: the storages connection it used must not be reused by the next upload.
 */
class StoragesConnectionReuseApiIT extends AbstractFilesIT {

  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String REQUESTER_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final int CHUNK = 32 * 1024;

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", REQUESTER_ID);
  }

  @Test
  void givenTheClientDisconnectsMidBodyThenTheNextUploadSucceeds() throws Exception {
    String folderId = seedFolder("streamed-upload-next", LOCAL_ROOT, REQUESTER_COOKIE);
    abortUploadMidBody(folderId);
    Assertions.assertThat(awaitChildren(folderId, 0)).isZero();

    Response response =
        upload(
            folderId,
            null,
            "after".getBytes(StandardCharsets.UTF_8),
            "after.txt",
            REQUESTER_COOKIE);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
  }

  private static void abortUploadMidBody(String folderId) throws Exception {
    try (Socket socket = new Socket("localhost", RestAssured.port)) {
      OutputStream out = socket.getOutputStream();
      out.write(head(folderId, "disconnect.bin", 64 * CHUNK).getBytes(StandardCharsets.UTF_8));
      for (int i = 0; i < 8; i++) {
        out.write(new byte[CHUNK]);
      }
      out.flush();
      Assertions.assertThat(awaitChildren(folderId, 1)).isEqualTo(1);
    }
  }

  private static String head(String folderId, String filename, int length) {
    return "POST /upload HTTP/1.1\r\nHost: localhost\r\nCookie: "
        + REQUESTER_COOKIE
        + "\r\nFilename: "
        + Base64.getEncoder().encodeToString(filename.getBytes(StandardCharsets.UTF_8))
        + "\r\nParentId: "
        + folderId
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
