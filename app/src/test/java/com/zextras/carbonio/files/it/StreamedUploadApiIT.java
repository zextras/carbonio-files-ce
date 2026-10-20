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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Uploads whose body is still streaming while the server works: a client that disconnects mid-body
 * (legacy deleted the placeholder node), a placeholder renamed mid-body, and upload-versions whose
 * version number is reserved before the body is read (no transaction or lock while it streams).
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
  void givenARenameWhileAnUploadVersionStreamsThenTheRenameDoesNotWaitAndTheNewVersionIsCurrent()
      throws Exception {
    String folderId = seedFolder("streamed-upload-version-rename", LOCAL_ROOT, REQUESTER_COOKIE);
    String nodeId = seedFile("versioned.txt", folderId, CONTENT, REQUESTER_COOKIE);

    String statusLine;
    try (Socket socket = startUploadVersion(nodeId, false)) {
      eventually("version 2 reserved", () -> pendingReservations(nodeId).equals(List.of(2)));
      Assertions.assertThat(filesConnectionsIdleInTransaction()).isZero();

      Response rename =
          CompletableFuture.supplyAsync(
                  () ->
                      graphql(
                          GraphqlCommandBuilder.aMutationBuilder("updateNode")
                              .withString("node_id", nodeId)
                              .withString("name", "renamed")
                              .withWantedResultFormat("{ id }")
                              .build(),
                          REQUESTER_COOKIE))
              .get(10, TimeUnit.SECONDS);
      Assertions.assertThat(TestUtils.jsonResponseToErrors(rename.getBody().asString())).isEmpty();

      statusLine = finishUpload(socket);
    }

    Assertions.assertThat(statusLine).startsWith("HTTP/1.1 200");
    Assertions.assertThat(nodeColumn(nodeId, "name")).isEqualTo("renamed.txt");
    Assertions.assertThat(nodeColumn(nodeId, "current_version")).isEqualTo("2");
    Assertions.assertThat(versionRows(nodeId)).containsExactly(1, 2);
    Assertions.assertThat(pendingReservations(nodeId)).isEmpty();
  }

  @Test
  void givenTwoUploadVersionsStreamingTogetherThenTheyGetVersionsInReservationOrder()
      throws Exception {
    String nodeId = seedFile("concurrent.txt", LOCAL_ROOT, CONTENT, REQUESTER_COOKIE);

    try (Socket first = startUploadVersion(nodeId, false)) {
      eventually("version 2 reserved", () -> pendingReservations(nodeId).equals(List.of(2)));
      try (Socket second = startUploadVersion(nodeId, false)) {
        eventually("version 3 reserved", () -> pendingReservations(nodeId).equals(List.of(2, 3)));
        Assertions.assertThat(finishUpload(second)).startsWith("HTTP/1.1 200");
      }
      Assertions.assertThat(nodeColumn(nodeId, "current_version")).isEqualTo("3");
      Assertions.assertThat(finishUpload(first)).startsWith("HTTP/1.1 200");
    }

    Assertions.assertThat(nodeColumn(nodeId, "current_version")).isEqualTo("3");
    Assertions.assertThat(versionRows(nodeId)).containsExactly(1, 2, 3);
    Assertions.assertThat(versionTimestamp(nodeId, 2)).isLessThan(versionTimestamp(nodeId, 3));
    Assertions.assertThat(pendingReservations(nodeId)).isEmpty();
  }

  @Test
  void givenAnUploadVersionAbortedMidBodyThenTheNextOneGetsTheNextNumber() throws Exception {
    String nodeId = seedFile("aborted.txt", LOCAL_ROOT, CONTENT, REQUESTER_COOKIE);

    try (Socket socket = startUploadVersion(nodeId, false)) {
      eventually("version 2 reserved", () -> pendingReservations(nodeId).equals(List.of(2)));
    }

    Response next = uploadVersion(nodeId, CONTENT, "aborted.txt", false, REQUESTER_COOKIE);

    Assertions.assertThat(next.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(next.jsonPath().getInt("version")).isEqualTo(3);
    Assertions.assertThat(versionRows(nodeId)).containsExactly(1, 3);
    Assertions.assertThat(nodeColumn(nodeId, "current_version")).isEqualTo("3");
    Assertions.assertThat(pendingReservations(nodeId)).containsExactly(2);
  }

  @Test
  void givenABlobLeftAtTheNextVersionByAFailedSaveThenOnlyOneMoreSaveFails() throws Exception {
    String nodeId = seedFile("stuck.txt", LOCAL_ROOT, CONTENT, REQUESTER_COOKIE);
    FilesStackTestResource.getStoragesService().seed(nodeId, 2, CONTENT);

    Response blocked = uploadVersion(nodeId, CONTENT, "stuck.txt", false, REQUESTER_COOKIE);
    Response next = uploadVersion(nodeId, CONTENT, "stuck.txt", false, REQUESTER_COOKIE);

    Assertions.assertThat(blocked.getStatusCode()).isEqualTo(500);
    Assertions.assertThat(next.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(next.jsonPath().getInt("version")).isEqualTo(3);
    Assertions.assertThat(versionRows(nodeId)).containsExactly(1, 3);
  }

  @Test
  void givenTheReservationIsClaimedWhileTheBodyStreamsThenTheVersionIsNotCreated()
      throws Exception {
    String nodeId = seedFile("claimed.txt", LOCAL_ROOT, CONTENT, REQUESTER_COOKIE);

    String statusLine;
    try (Socket socket = startUploadVersion(nodeId, false)) {
      eventually("version 2 reserved", () -> pendingReservations(nodeId).equals(List.of(2)));
      try (Connection connection = jdbcConnection();
          PreparedStatement claim =
              connection.prepareStatement(
                  "UPDATE version_reservation SET state = 'PURGING' WHERE node_id = ?")) {
        claim.setString(1, nodeId);
        Assertions.assertThat(claim.executeUpdate()).isEqualTo(1);
      }
      statusLine = finishUpload(socket);
    }

    Assertions.assertThat(statusLine).startsWith("HTTP/1.1 500");
    Assertions.assertThat(versionRows(nodeId)).containsExactly(1);
    Assertions.assertThat(nodeColumn(nodeId, "current_version")).isEqualTo("1");
  }

  @Test
  void givenAnOverwriteStreamingThenNoTransactionIsHeldAndTheVersionIsRewritten() throws Exception {
    String nodeId = seedFile("overwritten.txt", LOCAL_ROOT, CONTENT, REQUESTER_COOKIE);

    String statusLine;
    try (Socket socket = startUploadVersion(nodeId, true)) {
      assertNoTransactionHeldWhileTheBodyStreams(nodeId);
      statusLine = finishUpload(socket);
    }

    Assertions.assertThat(statusLine).startsWith("HTTP/1.1 200");
    Assertions.assertThat(versionRows(nodeId)).containsExactly(1);
    Assertions.assertThat(nodeColumn(nodeId, "current_version")).isEqualTo("1");
    Assertions.assertThat(nodeColumn(nodeId, "size")).isEqualTo(String.valueOf(2 * CHUNK));
    Assertions.assertThat(pendingReservations(nodeId)).isEmpty();
  }

  @Test
  void givenANewerVersionPublishedDuringAnOverwriteThenTheOverwriteDoesNotMoveTheCurrentBack()
      throws Exception {
    String nodeId = seedFile("raced.txt", LOCAL_ROOT, CONTENT, REQUESTER_COOKIE);

    String statusLine;
    try (Socket socket = startUploadVersion(nodeId, true)) {
      assertNoTransactionHeldWhileTheBodyStreams(nodeId);
      Assertions.assertThat(seedVersion(nodeId, CONTENT, "raced.txt", REQUESTER_COOKIE))
          .isEqualTo(2);
      statusLine = finishUpload(socket);
    }

    Assertions.assertThat(statusLine).startsWith("HTTP/1.1 200");
    Assertions.assertThat(versionRows(nodeId)).containsExactly(1, 2);
    Assertions.assertThat(nodeColumn(nodeId, "current_version")).isEqualTo("2");
  }

  @Test
  void givenTheOverwrittenVersionIsDeletedMeanwhileThenTheOverwriteFailsInsteadOfRecreatingIt()
      throws Exception {
    String nodeId = seedFile("deleted-meanwhile.txt", LOCAL_ROOT, CONTENT, REQUESTER_COOKIE);

    String statusLine;
    try (Socket socket = startUploadVersion(nodeId, true)) {
      assertNoTransactionHeldWhileTheBodyStreams(nodeId);
      Assertions.assertThat(seedVersion(nodeId, CONTENT, "deleted-meanwhile.txt", REQUESTER_COOKIE))
          .isEqualTo(2);
      Response deleted =
          graphql(
              GraphqlCommandBuilder.aMutationBuilder("deleteVersions")
                  .withString("node_id", nodeId)
                  .withListOfIntegers("versions", new int[] {1})
                  .withWantedResultFormat("")
                  .build(),
              REQUESTER_COOKIE);
      Assertions.assertThat(TestUtils.jsonResponseToErrors(deleted.getBody().asString())).isEmpty();
      try (Connection connection = jdbcConnection();
          PreparedStatement purged =
              connection.prepareStatement("DELETE FROM tombstone WHERE node_id = ?")) {
        purged.setString(1, nodeId);
        purged.executeUpdate();
      }
      statusLine = finishUpload(socket);
    }

    Assertions.assertThat(statusLine).startsWith("HTTP/1.1 404");
    Assertions.assertThat(versionRows(nodeId)).containsExactly(2);
    Assertions.assertThat(nodeColumn(nodeId, "current_version")).isEqualTo("2");
    Assertions.assertThat(tombstoneRowsForNode(nodeId)).isEqualTo(1);
    Assertions.assertThat(overwriteMarks(nodeId)).isEmpty();
  }

  @Test
  void givenTwoSavesStreamingAtTheVersionCapThenBothSucceedAndEachTrimsOneVersion()
      throws Exception {
    setApplicationConfig("max-number-of-versions", "2");
    try {
      String nodeId = seedFile("capped.txt", LOCAL_ROOT, CONTENT, REQUESTER_COOKIE);
      seedVersion(nodeId, CONTENT, "capped.txt", REQUESTER_COOKIE);

      try (Socket first = startUploadVersion(nodeId, false)) {
        eventually("version 3 reserved", () -> pendingReservations(nodeId).equals(List.of(3)));
        try (Socket second = startUploadVersion(nodeId, false)) {
          eventually("version 4 reserved", () -> pendingReservations(nodeId).equals(List.of(3, 4)));
          Assertions.assertThat(finishUpload(first)).startsWith("HTTP/1.1 200");
          Assertions.assertThat(finishUpload(second)).startsWith("HTTP/1.1 200");
        }
      }

      Assertions.assertThat(versionRows(nodeId)).containsExactly(3, 4);
      Assertions.assertThat(nodeColumn(nodeId, "current_version")).isEqualTo("4");
    } finally {
      clearApplicationConfig("max-number-of-versions", "30");
    }
  }

  @Test
  void givenAnOverwriteStreamingThenAConcurrentOverwriteOfTheSameVersionSavesANewVersion()
      throws Exception {
    String nodeId = seedFile("overwritten-twice.txt", LOCAL_ROOT, CONTENT, REQUESTER_COOKIE);

    String statusLine;
    try (Socket socket = startUploadVersion(nodeId, true)) {
      eventually("overwrite marked", () -> overwriteMarks(nodeId).equals(List.of(1)));
      Response concurrent =
          uploadVersion(nodeId, CONTENT, "overwritten-twice.txt", true, REQUESTER_COOKIE);
      Assertions.assertThat(concurrent.getStatusCode()).isEqualTo(200);
      Assertions.assertThat(concurrent.jsonPath().getInt("version")).isEqualTo(2);
      statusLine = finishUpload(socket);
    }

    Assertions.assertThat(statusLine).startsWith("HTTP/1.1 200");
    Assertions.assertThat(versionRows(nodeId)).containsExactly(1, 2);
    Assertions.assertThat(nodeColumn(nodeId, "current_version")).isEqualTo("2");
    Assertions.assertThat(versionColumn(nodeId, 1, "size")).isEqualTo(String.valueOf(2 * CHUNK));
    Assertions.assertThat(versionColumn(nodeId, 2, "size"))
        .isEqualTo(String.valueOf(CONTENT.length));
    Assertions.assertThat(overwriteMarks(nodeId)).isEmpty();
    Assertions.assertThat(
            uploadVersion(nodeId, CONTENT, "overwritten-twice.txt", true, REQUESTER_COOKIE)
                .jsonPath()
                .getInt("version"))
        .isEqualTo(2);
    Assertions.assertThat(versionRows(nodeId)).containsExactly(1, 2);
  }

  @Test
  void givenAnOverwriteMarkLeftBehindThenTheOverwriteSavesANewVersionInsteadOfFailing()
      throws Exception {
    String nodeId = seedFile("left-mark.txt", LOCAL_ROOT, CONTENT, REQUESTER_COOKIE);
    try (Connection connection = jdbcConnection();
        PreparedStatement mark =
            connection.prepareStatement(
                "INSERT INTO version_reservation (node_id, version, owner_id, reserved_at, state,"
                    + " attempts) VALUES (?, 1, ?, 1, 'OVERWRITING', 0)")) {
      mark.setString(1, nodeId);
      mark.setString(2, REQUESTER_ID);
      mark.executeUpdate();
    }

    Response saved = uploadVersion(nodeId, CONTENT, "left-mark.txt", true, REQUESTER_COOKIE);

    Assertions.assertThat(saved.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(saved.jsonPath().getInt("version")).isEqualTo(2);
    Assertions.assertThat(versionRows(nodeId)).containsExactly(1, 2);
    Assertions.assertThat(nodeColumn(nodeId, "current_version")).isEqualTo("2");
  }

  @Test
  void givenAFailedOverwriteThenItsMarkIsReleasedAndTheNextOverwriteRewritesInPlace()
      throws Exception {
    String nodeId = seedFile("failed-overwrite.txt", LOCAL_ROOT, CONTENT, REQUESTER_COOKIE);

    FilesStackTestResource.getStoragesService().setUploadFails(true);
    Response failed =
        uploadVersion(nodeId, CONTENT, "failed-overwrite.txt", true, REQUESTER_COOKIE);
    FilesStackTestResource.getStoragesService().setUploadFails(false);

    Assertions.assertThat(failed.getStatusCode()).isGreaterThanOrEqualTo(400);
    Assertions.assertThat(overwriteMarks(nodeId)).isEmpty();
    Response next = uploadVersion(nodeId, CONTENT, "failed-overwrite.txt", true, REQUESTER_COOKIE);
    Assertions.assertThat(next.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(versionRows(nodeId)).containsExactly(1);
    Assertions.assertThat(nodeColumn(nodeId, "current_version")).isEqualTo("1");
  }

  @Test
  void givenAFailedSaveBeforeAnOverwriteThenTheOverwrittenVersionGetsAFreshDate() throws Exception {
    String nodeId = seedFile("gap-then-overwrite.txt", LOCAL_ROOT, CONTENT, REQUESTER_COOKIE);
    long originalDate = versionTimestamp(nodeId, 1);

    try (Socket socket = startUploadVersion(nodeId, false)) {
      eventually("version 2 reserved", () -> pendingReservations(nodeId).equals(List.of(2)));
    }
    tickClock();
    Response overwrite =
        uploadVersion(nodeId, CONTENT, "gap-then-overwrite.txt", true, REQUESTER_COOKIE);

    Assertions.assertThat(overwrite.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(versionRows(nodeId)).containsExactly(1);
    Assertions.assertThat(versionTimestamp(nodeId, 1)).isGreaterThan(originalDate);
    Assertions.assertThat(Long.parseLong(nodeColumn(nodeId, "updated_timestamp")))
        .isEqualTo(versionTimestamp(nodeId, 1));
  }

  @Test
  void givenAnOverwriteWhileANewVersionStreamsThenTheNewVersionIsDatedAfterIt() throws Exception {
    String nodeId = seedFile("overwrite-below.txt", LOCAL_ROOT, CONTENT, REQUESTER_COOKIE);

    try (Socket socket = startUploadVersion(nodeId, false)) {
      eventually("version 2 reserved", () -> pendingReservations(nodeId).equals(List.of(2)));
      tickClock();
      Assertions.assertThat(
              uploadVersion(nodeId, CONTENT, "overwrite-below.txt", true, REQUESTER_COOKIE)
                  .getStatusCode())
          .isEqualTo(200);
      Assertions.assertThat(versionRows(nodeId)).containsExactly(1);
      Assertions.assertThat(finishUpload(socket)).startsWith("HTTP/1.1 200");
    }

    Assertions.assertThat(versionRows(nodeId)).containsExactly(1, 2);
    Assertions.assertThat(versionTimestamp(nodeId, 2)).isGreaterThan(versionTimestamp(nodeId, 1));
  }

  @Test
  void givenTheOwnerChangedWhileAnUploadVersionStreamsThenNoVersionIsCreated() throws Exception {
    String nodeId = seedFile("transferred.txt", LOCAL_ROOT, CONTENT, REQUESTER_COOKIE);

    String statusLine;
    try (Socket socket = startUploadVersion(nodeId, false)) {
      eventually("version 2 reserved", () -> pendingReservations(nodeId).equals(List.of(2)));
      changeOwner(nodeId);
      statusLine = finishUpload(socket);
    }

    Assertions.assertThat(statusLine).startsWith("HTTP/1.1 500");
    Assertions.assertThat(versionRows(nodeId)).containsExactly(1);
    Assertions.assertThat(pendingReservations(nodeId)).containsExactly(2);
  }

  @Test
  void givenTheOwnerChangedWhileAnOverwriteStreamsThenTheVersionIsNotRewritten() throws Exception {
    String nodeId = seedFile("transferred-overwrite.txt", LOCAL_ROOT, CONTENT, REQUESTER_COOKIE);

    String statusLine;
    try (Socket socket = startUploadVersion(nodeId, true)) {
      eventually("overwrite marked", () -> overwriteMarks(nodeId).equals(List.of(1)));
      changeOwner(nodeId);
      statusLine = finishUpload(socket);
    }

    Assertions.assertThat(statusLine).startsWith("HTTP/1.1 500");
    Assertions.assertThat(versionColumn(nodeId, 1, "size"))
        .isEqualTo(String.valueOf(CONTENT.length));
    Assertions.assertThat(overwriteMarks(nodeId)).isEmpty();
  }

  @Test
  void givenTheNodeDeletedWhileAnUploadVersionStreamsThenNoVersionIsCreated() throws Exception {
    String nodeId = seedFile("deleted-node.txt", LOCAL_ROOT, CONTENT, REQUESTER_COOKIE);

    String statusLine;
    try (Socket socket = startUploadVersion(nodeId, false)) {
      eventually("version 2 reserved", () -> pendingReservations(nodeId).equals(List.of(2)));
      try (Connection connection = jdbcConnection();
          PreparedStatement delete =
              connection.prepareStatement("DELETE FROM node WHERE node_id = ?")) {
        delete.setString(1, nodeId);
        Assertions.assertThat(delete.executeUpdate()).isEqualTo(1);
      }
      statusLine = finishUpload(socket);
    }

    Assertions.assertThat(statusLine).startsWith("HTTP/1.1 404");
    Assertions.assertThat(versionRows(nodeId)).isEmpty();
    Assertions.assertThat(pendingReservations(nodeId)).containsExactly(2);
  }

  @Test
  void givenACloneWhileAnUploadVersionStreamsThenTheyGetVersionsInReservationOrder()
      throws Exception {
    String nodeId = seedFile("cloned.txt", LOCAL_ROOT, CONTENT, REQUESTER_COOKIE);

    try (Socket socket = startUploadVersion(nodeId, false)) {
      eventually("version 2 reserved", () -> pendingReservations(nodeId).equals(List.of(2)));
      Response clone =
          graphql(
              GraphqlCommandBuilder.aMutationBuilder("cloneVersion")
                  .withString("node_id", nodeId)
                  .withInteger("version", 1)
                  .withWantedResultFormat("{ version }")
                  .build(),
              REQUESTER_COOKIE);
      Assertions.assertThat(TestUtils.jsonResponseToMap(clone.getBody().asString(), "cloneVersion"))
          .containsEntry("version", 3);
      Assertions.assertThat(finishUpload(socket)).startsWith("HTTP/1.1 200");
    }

    Assertions.assertThat(versionRows(nodeId)).containsExactly(1, 2, 3);
    Assertions.assertThat(nodeColumn(nodeId, "current_version")).isEqualTo("3");
    Assertions.assertThat(versionTimestamp(nodeId, 2)).isLessThan(versionTimestamp(nodeId, 3));
  }

  private static final byte[] CONTENT = "content".getBytes(StandardCharsets.UTF_8);

  /** Opens an upload-version of 2 chunks and sends only the first one. */
  private static Socket startUploadVersion(String nodeId, boolean overwrite) throws Exception {
    Socket socket = new Socket("localhost", RestAssured.port);
    socket.setSoTimeout(30_000);
    OutputStream out = socket.getOutputStream();
    out.write(
        head(
                "/upload-version",
                "NodeId: " + nodeId + "\r\nOverwriteVersion: " + overwrite,
                "version.txt",
                2 * CHUNK)
            .getBytes(StandardCharsets.UTF_8));
    out.write(new byte[CHUNK]);
    out.flush();
    return socket;
  }

  /** Once the overwrite is marked (a short committed transaction), none stays open for a second. */
  private static void assertNoTransactionHeldWhileTheBodyStreams(String nodeId) throws Exception {
    eventually("overwrite marked", () -> overwriteMarks(nodeId).equals(List.of(1)));
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
    while (System.nanoTime() < deadline) {
      Assertions.assertThat(filesConnectionsIdleInTransaction()).isZero();
    }
  }

  /** Sends the second chunk and returns the response status line. */
  private static String finishUpload(Socket socket) throws Exception {
    OutputStream out = socket.getOutputStream();
    out.write(new byte[CHUNK]);
    out.flush();
    return new BufferedReader(
            new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
        .readLine();
  }

  private static String nodeColumn(String nodeId, String column) throws SQLException {
    try (Connection connection = jdbcConnection();
        PreparedStatement statement =
            connection.prepareStatement("SELECT " + column + " FROM node WHERE node_id = ?")) {
      statement.setString(1, nodeId);
      try (ResultSet resultSet = statement.executeQuery()) {
        Assertions.assertThat(resultSet.next()).isTrue();
        return resultSet.getString(1).trim();
      }
    }
  }

  private static String versionColumn(String nodeId, int version, String column)
      throws SQLException {
    try (Connection connection = jdbcConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT " + column + " FROM revision WHERE node_id = ? AND version = ?")) {
      statement.setString(1, nodeId);
      statement.setInt(2, version);
      try (ResultSet resultSet = statement.executeQuery()) {
        Assertions.assertThat(resultSet.next()).isTrue();
        return resultSet.getString(1).trim();
      }
    }
  }

  /** A transfer of ownership committed while the save streams. */
  private static void changeOwner(String nodeId) throws SQLException {
    try (Connection connection = jdbcConnection();
        PreparedStatement transfer =
            connection.prepareStatement(
                "UPDATE node SET owner_id = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'"
                    + " WHERE node_id = ?")) {
      transfer.setString(1, nodeId);
      Assertions.assertThat(transfer.executeUpdate()).isEqualTo(1);
    }
  }

  private static List<Integer> overwriteMarks(String nodeId) throws SQLException {
    try (Connection connection = jdbcConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT version FROM version_reservation WHERE node_id = ?"
                    + " AND state = 'OVERWRITING'")) {
      statement.setString(1, nodeId);
      try (ResultSet resultSet = statement.executeQuery()) {
        List<Integer> versions = new java.util.ArrayList<>();
        while (resultSet.next()) {
          versions.add(resultSet.getInt(1));
        }
        return versions;
      }
    }
  }

  private static long versionTimestamp(String nodeId, int version) throws SQLException {
    try (Connection connection = jdbcConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT timestamp FROM revision WHERE node_id = ? AND version = ?")) {
      statement.setString(1, nodeId);
      statement.setInt(2, version);
      try (ResultSet resultSet = statement.executeQuery()) {
        Assertions.assertThat(resultSet.next()).isTrue();
        return resultSet.getLong(1);
      }
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
