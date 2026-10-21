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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
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

    try (Socket first = startUploadVersion(nodeId, false, (byte) 'a')) {
      eventually("version 2 reserved", () -> pendingReservations(nodeId).equals(List.of(2)));
      try (Socket second = startUploadVersion(nodeId, false, (byte) 'b')) {
        eventually("version 3 reserved", () -> pendingReservations(nodeId).equals(List.of(2, 3)));
        Assertions.assertThat(finishUpload(second, (byte) 'b')).startsWith("HTTP/1.1 200");
      }
      Assertions.assertThat(nodeColumn(nodeId, "current_version")).isEqualTo("3");
      Assertions.assertThat(finishUpload(first, (byte) 'a')).startsWith("HTTP/1.1 200");
    }

    Assertions.assertThat(nodeColumn(nodeId, "current_version")).isEqualTo("3");
    Assertions.assertThat(versionRows(nodeId)).containsExactly(1, 2, 3);
    Assertions.assertThat(versionContent(nodeId, 3)).containsOnly('b');
    Assertions.assertThat(versionContent(nodeId, 2)).containsOnly('a');
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
  void givenAnOverwriteStreamingThenNoTransactionIsHeldAndARenameDoesNotWait() throws Exception {
    String nodeId = seedFile("overwritten.txt", LOCAL_ROOT, CONTENT, REQUESTER_COOKIE);

    String statusLine;
    try (Socket socket = startUploadVersion(nodeId, true, (byte) 'o')) {
      eventually("lease taken", () -> leasedVersion(nodeId).equals(List.of(1)));
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
      while (System.nanoTime() < deadline) {
        Assertions.assertThat(filesConnectionsIdleInTransaction()).isZero();
      }
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
      statusLine = finishUpload(socket, (byte) 'o');
    }

    Assertions.assertThat(statusLine).startsWith("HTTP/1.1 200");
    Assertions.assertThat(versionRows(nodeId)).containsExactly(1);
    Assertions.assertThat(nodeColumn(nodeId, "size")).isEqualTo(String.valueOf(2 * CHUNK));
    Assertions.assertThat(nodeColumn(nodeId, "name")).isEqualTo("renamed.txt");
    Assertions.assertThat(versionContent(nodeId, 1)).containsOnly('o');
    Assertions.assertThat(leasedVersion(nodeId)).isEmpty();
  }

  @Test
  void givenAnOverwriteStreamingThenASecondOverwriteIsRefusedAndTheNextOneAfterItWorks()
      throws Exception {
    String nodeId = seedFile("overwritten-twice.txt", LOCAL_ROOT, CONTENT, REQUESTER_COOKIE);

    String statusLine;
    try (Socket socket = startUploadVersion(nodeId, true, (byte) 'a')) {
      eventually("lease taken", () -> leasedVersion(nodeId).equals(List.of(1)));
      Response refused =
          uploadVersion(nodeId, CONTENT, "overwritten-twice.txt", true, REQUESTER_COOKIE);
      Assertions.assertThat(refused.getStatusCode()).isEqualTo(423);
      statusLine = finishUpload(socket, (byte) 'a');
    }

    Assertions.assertThat(statusLine).startsWith("HTTP/1.1 200");
    Assertions.assertThat(versionContent(nodeId, 1)).containsOnly('a');
    Assertions.assertThat(
            uploadVersion(nodeId, CONTENT, "overwritten-twice.txt", true, REQUESTER_COOKIE)
                .getStatusCode())
        .isEqualTo(200);
    Assertions.assertThat(versionRows(nodeId)).containsExactly(1);
    Assertions.assertThat(versionContent(nodeId, 1)).isEqualTo(CONTENT);
  }

  @Test
  void givenTheLeaseExpiredWhileAnOverwriteStreamsThenANewOneProceedsAndTheLateOneLeavesItsRow()
      throws Exception {
    String nodeId = seedFile("expired-lease.txt", LOCAL_ROOT, CONTENT, REQUESTER_COOKIE);
    byte[] newer = "the newer overwrite".getBytes(StandardCharsets.UTF_8);

    String statusLine;
    try (Socket late = startUploadVersion(nodeId, true, (byte) 'l')) {
      eventually("lease taken", () -> leasedVersion(nodeId).equals(List.of(1)));
      expireLeases(nodeId);
      Assertions.assertThat(
              uploadVersion(nodeId, newer, "expired-lease.txt", true, REQUESTER_COOKIE)
                  .getStatusCode())
          .isEqualTo(200);
      statusLine = finishUpload(late, (byte) 'l');
    }

    Assertions.assertThat(statusLine).startsWith("HTTP/1.1 200");
    Assertions.assertThat(versionRows(nodeId)).containsExactly(1);
    Assertions.assertThat(nodeColumn(nodeId, "size")).isEqualTo(String.valueOf(newer.length));
    Assertions.assertThat(leasedVersion(nodeId)).isEmpty();
  }

  @Test
  void givenAFailedOverwriteThenItsLeaseIsReleasedAndTheNextOneIsNotRefused() throws Exception {
    String nodeId = seedFile("failed-overwrite.txt", LOCAL_ROOT, CONTENT, REQUESTER_COOKIE);

    FilesStackTestResource.getStoragesService().setUploadFails(true);
    Response failed =
        uploadVersion(nodeId, CONTENT, "failed-overwrite.txt", true, REQUESTER_COOKIE);
    FilesStackTestResource.getStoragesService().setUploadFails(false);

    Assertions.assertThat(failed.getStatusCode()).isGreaterThanOrEqualTo(500);
    Assertions.assertThat(leasedVersion(nodeId)).isEmpty();
    Assertions.assertThat(
            uploadVersion(nodeId, CONTENT, "failed-overwrite.txt", true, REQUESTER_COOKIE)
                .getStatusCode())
        .isEqualTo(200);
  }

  @Test
  void givenALeaseLeftByACrashThenOverwritesAreRefusedUntilItExpires() throws Exception {
    String nodeId = seedFile("crashed-lease.txt", LOCAL_ROOT, CONTENT, REQUESTER_COOKIE);
    try (Connection connection = jdbcConnection();
        PreparedStatement lease =
            connection.prepareStatement(
                "INSERT INTO overwrite_lease (node_id, version, token, registered_at, expires_at)"
                    + " VALUES (?, 1, ?, 1, ?)")) {
      lease.setString(1, nodeId);
      lease.setString(2, UUID.randomUUID().toString());
      lease.setLong(3, System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(10));
      lease.executeUpdate();
    }

    Response refused = uploadVersion(nodeId, CONTENT, "crashed-lease.txt", true, REQUESTER_COOKIE);
    expireLeases(nodeId);
    Response accepted = uploadVersion(nodeId, CONTENT, "crashed-lease.txt", true, REQUESTER_COOKIE);

    Assertions.assertThat(refused.getStatusCode()).isEqualTo(423);
    Assertions.assertThat(accepted.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(leasedVersion(nodeId)).isEmpty();
  }

  @Test
  void givenAManualSaveDuringAnOverwriteThenItDoesNotWaitAndBecomesTheLatestVersion()
      throws Exception {
    String nodeId = seedFile("manual-during.txt", LOCAL_ROOT, CONTENT, REQUESTER_COOKIE);

    String statusLine;
    try (Socket overwrite = startUploadVersion(nodeId, true, (byte) 'o')) {
      eventually("lease taken", () -> leasedVersion(nodeId).equals(List.of(1)));
      Response manual =
          CompletableFuture.supplyAsync(
                  () ->
                      uploadVersion(nodeId, CONTENT, "manual-during.txt", false, REQUESTER_COOKIE))
              .get(10, TimeUnit.SECONDS);
      Assertions.assertThat(manual.getStatusCode()).isEqualTo(200);
      Assertions.assertThat(manual.jsonPath().getInt("version")).isEqualTo(2);
      statusLine = finishUpload(overwrite, (byte) 'o');
    }

    Assertions.assertThat(statusLine).startsWith("HTTP/1.1 200");
    Assertions.assertThat(versionRows(nodeId)).containsExactly(1, 2);
    Assertions.assertThat(nodeColumn(nodeId, "current_version")).isEqualTo("2");
    Assertions.assertThat(versionContent(nodeId, 1)).containsOnly('o');
    Assertions.assertThat(versionContent(nodeId, 2)).isEqualTo(CONTENT);
  }

  @Test
  void givenAnAutosaveWhileAManualSaveStreamsThenBothSucceedAndTheManualSaveIsTheLatestVersion()
      throws Exception {
    String nodeId = seedFile("autosave-during.txt", LOCAL_ROOT, CONTENT, REQUESTER_COOKIE);
    byte[] autosaved = "autosaved".getBytes(StandardCharsets.UTF_8);

    try (Socket manual = startUploadVersion(nodeId, false, (byte) 'm')) {
      eventually("version 2 reserved", () -> pendingReservations(nodeId).equals(List.of(2)));
      Assertions.assertThat(
              uploadVersion(nodeId, autosaved, "autosave-during.txt", true, REQUESTER_COOKIE)
                  .getStatusCode())
          .isEqualTo(200);
      Assertions.assertThat(finishUpload(manual, (byte) 'm')).startsWith("HTTP/1.1 200");
    }

    Assertions.assertThat(versionRows(nodeId)).containsExactly(1, 2);
    Assertions.assertThat(nodeColumn(nodeId, "current_version")).isEqualTo("2");
    Assertions.assertThat(versionContent(nodeId, 1)).isEqualTo(autosaved);
    Assertions.assertThat(versionContent(nodeId, 2)).containsOnly('m');
    Assertions.assertThat(versionTimestamp(nodeId, 1)).isLessThan(versionTimestamp(nodeId, 2));
  }

  /**
   * Manual saves registered one after the other while earlier ones finish in a random order: the
   * contents follow the reservation order across the version numbers (and so do the dates), the
   * latest version holds the last registered save and no blob mixes two saves.
   */
  @Test
  void givenManualSavesFinishingInAnyOrderThenTheLastReservedIsTheLatestVersion() throws Exception {
    int saves = 8;
    for (long seed = 1; seed <= 12; seed++) {
      Random random = new Random(seed);
      String nodeId = seedFile("spam-" + seed + ".txt", LOCAL_ROOT, CONTENT, REQUESTER_COOKIE);
      Map<Integer, Socket> inFlight = new LinkedHashMap<>();
      try {
        int registered = 0;
        while (registered < saves || !inFlight.isEmpty()) {
          if (registered < saves && (inFlight.isEmpty() || random.nextBoolean())) {
            int save = ++registered;
            inFlight.put(save, startUploadVersion(nodeId, false, (byte) save));
            int expected = inFlight.size();
            eventually("save reserved", () -> pendingReservations(nodeId).size() == expected);
          } else {
            List<Integer> open = new ArrayList<>(inFlight.keySet());
            int save = open.get(random.nextInt(open.size()));
            Assertions.assertThat(finishUpload(inFlight.remove(save), (byte) save))
                .as("seed %d save %d", seed, save)
                .startsWith("HTTP/1.1 200");
          }
        }
      } finally {
        for (Socket socket : inFlight.values()) {
          socket.close();
        }
      }

      List<Integer> versions = versionRows(nodeId);
      Assertions.assertThat(versions).as("seed %d", seed).hasSize(1 + saves);
      Assertions.assertThat(nodeColumn(nodeId, "current_version"))
          .as("seed %d", seed)
          .isEqualTo(String.valueOf(versions.getLast()));
      Assertions.assertThat(versionContent(nodeId, versions.getLast()))
          .as("seed %d", seed)
          .containsOnly(saves);
      List<Integer> fills = new ArrayList<>();
      List<Long> dates = new ArrayList<>();
      for (int version : versions) {
        byte[] content = versionContent(nodeId, version);
        if (version > 1) {
          Assertions.assertThat(content)
              .as("seed %d version %d", seed, version)
              .containsOnly(content[0]);
          fills.add((int) content[0]);
        }
        dates.add(versionTimestamp(nodeId, version));
      }
      Assertions.assertThat(fills).as("seed %d", seed).isSorted().doesNotHaveDuplicates();
      Assertions.assertThat(dates).as("seed %d", seed).isSorted().doesNotHaveDuplicates();
      Assertions.assertThat(pendingReservations(nodeId)).as("seed %d", seed).isEmpty();
    }
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
    return startUploadVersion(nodeId, overwrite, (byte) 0);
  }

  /** As {@link #startUploadVersion(String, boolean)}, every byte of the body being {@code fill}. */
  private static Socket startUploadVersion(String nodeId, boolean overwrite, byte fill)
      throws Exception {
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
    out.write(chunkOf(fill));
    out.flush();
    return socket;
  }

  private static byte[] chunkOf(byte fill) {
    byte[] chunk = new byte[CHUNK];
    Arrays.fill(chunk, fill);
    return chunk;
  }

  /** Once the overwrite is marked (a short committed transaction), none stays open for a second. */
  /** Sends the second chunk and returns the response status line. */
  private static String finishUpload(Socket socket) throws Exception {
    return finishUpload(socket, (byte) 0);
  }

  private static String finishUpload(Socket socket, byte fill) throws Exception {
    OutputStream out = socket.getOutputStream();
    out.write(chunkOf(fill));
    out.flush();
    return new BufferedReader(
            new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
        .readLine();
  }

  private static List<Integer> leasedVersion(String nodeId) throws SQLException {
    try (Connection connection = jdbcConnection();
        PreparedStatement statement =
            connection.prepareStatement("SELECT version FROM overwrite_lease WHERE node_id = ?")) {
      statement.setString(1, nodeId);
      try (ResultSet resultSet = statement.executeQuery()) {
        List<Integer> versions = new ArrayList<>();
        while (resultSet.next()) {
          versions.add(resultSet.getInt(1));
        }
        return versions;
      }
    }
  }

  private static void expireLeases(String nodeId) throws SQLException {
    try (Connection connection = jdbcConnection();
        PreparedStatement expire =
            connection.prepareStatement(
                "UPDATE overwrite_lease SET expires_at = 0 WHERE node_id = ?")) {
      expire.setString(1, nodeId);
      expire.executeUpdate();
    }
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

  private static byte[] versionContent(String nodeId, int version) {
    Response response = download(nodeId, version, REQUESTER_COOKIE);
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    return response.asByteArray();
  }

  /** Saves registered and not finished yet: reservations and overwrite marks. */
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
