// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.response.Response;
import java.net.Socket;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RaceVersionApiIT extends AbstractFilesIT {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final String OWNER_ID = "61111111-1111-1111-1111-111111111111";
  private static final String OWNER_COOKIE = "ZM_AUTH_TOKEN=race-version-owner";
  private static final String SHAREE_ID = "62222222-2222-2222-2222-222222222222";
  private static final String SHAREE_COOKIE = "ZM_AUTH_TOKEN=race-version-sharee";
  private static final String NEW_OWNER_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final int CHUNK = 32 * 1024;
  private static final byte[] CONTENT = "v".getBytes();

  private static final String NODE_ROW_SHARE = "SELECT 1 FROM node WHERE node_id = ? FOR SHARE";
  private static final String NODE_ROW_LOCK = "SELECT 1 FROM node WHERE node_id = ? FOR UPDATE";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("race-version-owner", OWNER_ID);
    FilesStackTestResource.getUserManagementService()
        .registerToken("race-version-sharee", SHAREE_ID);
  }

  private static Response deleteVersions(String nodeId, int... versions) {
    GraphqlCommandBuilder builder =
        GraphqlCommandBuilder.aMutationBuilder("deleteVersions").withString("node_id", nodeId);
    if (versions != null) {
      builder = builder.withListOfIntegers("versions", versions);
    }
    return graphql(builder.withWantedResultFormat("").build(), OWNER_COOKIE);
  }

  private static Response keepVersions(String nodeId, int... versions) {
    return graphql(
        GraphqlCommandBuilder.aMutationBuilder("keepVersions")
            .withString("node_id", nodeId)
            .withListOfIntegers("versions", versions)
            .withBoolean("keep_forever", true)
            .withWantedResultFormat("")
            .build(),
        OWNER_COOKIE);
  }

  private static Response cloneVersion(String nodeId, int version, String cookie) {
    return graphql(
        GraphqlCommandBuilder.aMutationBuilder("cloneVersion")
            .withString("node_id", nodeId)
            .withInteger("version", version)
            .withWantedResultFormat("{ id version }")
            .build(),
        cookie);
  }

  private static Response deleteShares(String nodeId) {
    return graphql(
        GraphqlCommandBuilder.aMutationBuilder("deleteShares")
            .withString("node_id", nodeId)
            .withListOfStrings("share_target_ids", new String[] {SHAREE_ID})
            .withWantedResultFormat("")
            .build(),
        OWNER_COOKIE);
  }

  private static List<String> errorCodes(Response response) throws Exception {
    JsonNode root = OBJECT_MAPPER.readTree(response.getBody().asString());
    List<String> codes = new ArrayList<>();
    if (root.has("errors")) {
      for (JsonNode error : root.get("errors")) {
        codes.add(error.get("extensions").get("errorCode").asText());
      }
    }
    return codes;
  }

  @SuppressWarnings("unchecked")
  private static List<Integer> keptReported(Response response) {
    return (List<Integer>)
        TestUtils.jsonResponseToValue(response.getBody().asString(), "keepVersions")
            .orElse(List.of());
  }

  private static boolean keptForever(String nodeId, int version) throws SQLException {
    try (Connection connection = jdbcConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT keep_forever FROM revision WHERE node_id = ? AND version = ?")) {
      statement.setString(1, nodeId);
      statement.setInt(2, version);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() && rows.getBoolean(1);
      }
    }
  }

  private static int currentVersion(String nodeId) throws SQLException {
    try (Connection connection = jdbcConnection();
        PreparedStatement statement =
            connection.prepareStatement("SELECT current_version FROM node WHERE node_id = ?")) {
      statement.setString(1, nodeId);
      try (ResultSet rows = statement.executeQuery()) {
        rows.next();
        return rows.getInt(1);
      }
    }
  }

  private static Socket startUploadVersion(String nodeId, String cookie) throws Exception {
    return startUploadVersion(nodeId, false, cookie);
  }

  private static Socket startUploadVersion(String nodeId, boolean overwrite, String cookie)
      throws Exception {
    return startRawUpload(
        "/upload-version",
        "NodeId: "
            + nodeId
            + "\r\nOverwriteVersion: "
            + overwrite
            + "\r\n"
            + filenameHeader("race.txt"),
        cookie,
        CHUNK);
  }

  private static Socket startHeldUploadVersion(String nodeId, String cookie) throws Exception {
    var storages = FilesStackTestResource.getStoragesService();
    storages.holdUploads();
    try {
      Socket socket = startUploadVersion(nodeId, cookie);
      socket.getOutputStream().write(new byte[CHUNK]);
      socket.getOutputStream().flush();
      storages.awaitHeldUpload();
      return socket;
    } catch (Exception | Error e) {
      storages.releaseUploads();
      throw e;
    }
  }

  private static void awaitAll(List<CompletableFuture<Response>> pending) {
    for (CompletableFuture<Response> future : pending) {
      try {
        future.get(30, TimeUnit.SECONDS);
      } catch (Exception ignored) {
      }
    }
  }

  @Test
  void givenADeleteVersionsRacingAKeepVersionsThenAVersionReportedKeptStillExists()
      throws Exception {
    String file = seedFile("race-version-g.txt", LOCAL_ROOT, CONTENT, OWNER_COOKIE);
    seedVersion(file, CONTENT, "race-version-g.txt", OWNER_COOKIE);
    seedVersion(file, CONTENT, "race-version-g.txt", OWNER_COOKIE);

    List<CompletableFuture<Response>> pending = new ArrayList<>();
    Response keep;
    try (Connection holder = jdbcConnection()) {
      holder.setAutoCommit(false);
      try (PreparedStatement insert =
          holder.prepareStatement(
              "INSERT INTO tombstone (node_id, owner_id, timestamp, version, attempts)"
                  + " VALUES (?, ?, 0, 1, 0)")) {
        insert.setString(1, file);
        insert.setString(2, OWNER_ID);
        insert.executeUpdate();
      }
      try {
        CompletableFuture<Response> delete =
            CompletableFuture.supplyAsync(() -> deleteVersions(file, 1), RACE_EXECUTOR);
        pending.add(delete);
        awaitBlockedBy(holder, 1);
        CompletableFuture<Response> keepPending =
            CompletableFuture.supplyAsync(() -> keepVersions(file, 1), RACE_EXECUTOR);
        pending.add(keepPending);
        awaitBlockedOrDone(holder, 2, delete, keepPending);
      } finally {
        holder.rollback();
      }
      for (CompletableFuture<Response> future : pending) {
        future.get(30, TimeUnit.SECONDS);
      }
      keep = pending.get(1).get();
    } finally {
      awaitAll(pending);
    }

    if (keptReported(keep).contains(1)) {
      Assertions.assertThat(versionRows(file)).contains(1);
      Assertions.assertThat(keptForever(file, 1)).isTrue();
    } else {
      Assertions.assertThat(versionRows(file)).doesNotContain(1);
    }
    Assertions.assertThat(versionRows(file)).contains(2, 3);
  }

  @Test
  void givenADeleteAllVersionsRacingAnUploadVersionThenTheCurrentVersionSurvives()
      throws Exception {
    String file = seedFile("race-version-f15.txt", LOCAL_ROOT, CONTENT, OWNER_COOKIE);
    seedVersion(file, CONTENT, "race-version-f15.txt", OWNER_COOKIE);

    List<CompletableFuture<Response>> pending = new ArrayList<>();
    var storages = FilesStackTestResource.getStoragesService();
    String status;
    try (Socket upload = startHeldUploadVersion(file, OWNER_COOKIE)) {
      try (Connection holder = holdRow(NODE_ROW_LOCK, file)) {
        try {
          storages.releaseUploads();
          awaitBlockedBy(holder, 1);
          CompletableFuture<Response> delete =
              CompletableFuture.supplyAsync(() -> deleteVersions(file, null), RACE_EXECUTOR);
          pending.add(delete);
          awaitBlockedOrDone(holder, 2, delete);
        } finally {
          holder.rollback();
        }
      }
      status = readStatusLine(upload);
      for (CompletableFuture<Response> future : pending) {
        future.get(30, TimeUnit.SECONDS);
      }
    } finally {
      storages.releaseUploads();
      awaitAll(pending);
    }

    Assertions.assertThat(status).isEqualTo("HTTP/1.1 200 OK");
    List<Integer> rows = versionRows(file);
    Assertions.assertThat(rows).contains(currentVersion(file)).contains(3);
  }

  @Test
  void givenTwoUploadVersionsReservingTogetherThenBothSucceedWithConsecutiveVersions()
      throws Exception {
    String file = seedFile("race-version-r.txt", LOCAL_ROOT, CONTENT, OWNER_COOKIE);
    String first;
    String second;
    try (Connection holder = holdRow(NODE_ROW_SHARE, file)) {
      try (Socket a = startUploadVersion(file, OWNER_COOKIE);
          Socket b = startUploadVersion(file, OWNER_COOKIE)) {
        awaitBlockedBy(holder, 2);
        holder.rollback();
        first = finishRawUpload(a, CHUNK);
        second = finishRawUpload(b, CHUNK);
      }
    }

    Assertions.assertThat(first).isEqualTo("HTTP/1.1 200 OK");
    Assertions.assertThat(second).isEqualTo("HTTP/1.1 200 OK");
    Assertions.assertThat(versionRows(file)).containsExactly(1, 2, 3);
  }

  @Test
  void givenTheWritePermissionRevokedDuringAnUploadVersionThenItIsRefused() throws Exception {
    String file = seedFile("race-version-revoke-upload.txt", LOCAL_ROOT, CONTENT, OWNER_COOKIE);
    seedShare(file, SHAREE_ID, SharePermission.READ_AND_WRITE, OWNER_COOKIE);

    var storages = FilesStackTestResource.getStoragesService();
    String status;
    try (Socket upload = startHeldUploadVersion(file, SHAREE_COOKIE)) {
      deleteShares(file);
      storages.releaseUploads();
      status = readStatusLine(upload);
    } finally {
      storages.releaseUploads();
    }

    Assertions.assertThat(status).contains("404");
    Assertions.assertThat(versionRows(file)).containsExactly(1);
  }

  @Test
  void givenTheWritePermissionRevokedDuringACloneThenItIsRefused() throws Exception {
    String file = seedFile("race-version-revoke-clone.txt", LOCAL_ROOT, CONTENT, OWNER_COOKIE);
    seedShare(file, SHAREE_ID, SharePermission.READ_AND_WRITE, OWNER_COOKIE);

    var storages = FilesStackTestResource.getStoragesService();
    storages.holdCopies();
    CompletableFuture<Response> clone = null;
    Response response;
    try {
      clone =
          CompletableFuture.supplyAsync(() -> cloneVersion(file, 1, SHAREE_COOKIE), RACE_EXECUTOR);
      storages.awaitHeldCopy();
      deleteShares(file);
      storages.releaseCopies();
      response = clone.get(30, TimeUnit.SECONDS);
    } finally {
      storages.releaseCopies();
      if (clone != null) {
        try {
          clone.get(30, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
      }
    }

    Assertions.assertThat(errorCodes(response)).containsExactly("NODE_WRITE_ERROR");
    Assertions.assertThat(versionRows(file)).containsExactly(1);
  }

  @Test
  void givenTheOwnerChangedDuringACloneThenItIsRefusedAndNoVersionIsCreated() throws Exception {
    String file = seedFile("race-version-owner-clone.txt", LOCAL_ROOT, CONTENT, OWNER_COOKIE);
    seedShare(file, SHAREE_ID, SharePermission.READ_AND_WRITE, OWNER_COOKIE);

    var storages = FilesStackTestResource.getStoragesService();
    storages.holdCopies();
    CompletableFuture<Response> clone = null;
    Response response;
    try {
      clone =
          CompletableFuture.supplyAsync(() -> cloneVersion(file, 1, SHAREE_COOKIE), RACE_EXECUTOR);
      storages.awaitHeldCopy();
      changeOwner(file);
      storages.releaseCopies();
      response = clone.get(30, TimeUnit.SECONDS);
    } finally {
      storages.releaseCopies();
      if (clone != null) {
        try {
          clone.get(30, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
      }
    }

    Assertions.assertThat(errorCodes(response)).containsExactly("NODE_WRITE_ERROR");
    Assertions.assertThat(versionRows(file)).containsExactly(1);
    Assertions.assertThat(currentVersion(file)).isEqualTo(1);
  }

  @Test
  void givenTheOwnerChangedWhileAnOverwriteWaitsForTheNodeRowThenTheOverwriteStillCompletes()
      throws Exception {
    String file = seedFile("race-version-owner-overwrite.txt", LOCAL_ROOT, CONTENT, OWNER_COOKIE);
    seedShare(file, SHAREE_ID, SharePermission.READ_AND_WRITE, OWNER_COOKIE);
    String rowBefore = versionRow(file, 1);

    String status;
    try (Connection holder = jdbcConnection()) {
      holder.setAutoCommit(false);
      try (PreparedStatement ownerChange =
          holder.prepareStatement("UPDATE node SET owner_id = ? WHERE node_id = ?")) {
        ownerChange.setString(1, NEW_OWNER_ID);
        ownerChange.setString(2, file);
        ownerChange.executeUpdate();
      }
      try (Socket overwrite = startUploadVersion(file, true, SHAREE_COOKIE)) {
        awaitBlockedBy(holder);
        holder.commit();
        status = finishRawUpload(overwrite, CHUNK);
      } finally {
        holder.rollback();
      }
    }

    Assertions.assertThat(status).isEqualTo("HTTP/1.1 200 OK");
    Assertions.assertThat(versionRows(file)).containsExactly(1);
    Assertions.assertThat(versionRow(file, 1)).isNotEqualTo(rowBefore);
  }

  private static String versionRow(String nodeId, int version) throws SQLException {
    try (Connection connection = jdbcConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT size || '/' || digest FROM revision WHERE node_id = ? AND version = ?")) {
      statement.setString(1, nodeId);
      statement.setInt(2, version);
      try (ResultSet rows = statement.executeQuery()) {
        Assertions.assertThat(rows.next()).isTrue();
        return rows.getString(1);
      }
    }
  }

  private static void changeOwner(String nodeId) throws SQLException {
    try (Connection connection = jdbcConnection();
        PreparedStatement ownerChange =
            connection.prepareStatement("UPDATE node SET owner_id = ? WHERE node_id = ?")) {
      ownerChange.setString(1, NEW_OWNER_ID);
      ownerChange.setString(2, nodeId);
      Assertions.assertThat(ownerChange.executeUpdate()).isEqualTo(1);
    }
  }
}
