// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import java.net.Socket;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RaceUploadApiIT extends AbstractFilesIT {

  private static final String OWNER_ID = "31111111-1111-1111-1111-111111111111";
  private static final String OWNER_COOKIE = "ZM_AUTH_TOKEN=race-upload-owner";
  private static final String SHAREE_ID = "32222222-2222-2222-2222-222222222222";
  private static final String SHAREE_COOKIE = "ZM_AUTH_TOKEN=race-upload-sharee";
  private static final int CHUNK = 32 * 1024;

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("race-upload-owner", OWNER_ID);
    FilesStackTestResource.getUserManagementService()
        .registerToken("race-upload-sharee", SHAREE_ID);
  }

  private static Socket startUpload(String folderId, String name) throws Exception {
    return startRawUpload(
        "/upload", "ParentId: " + folderId + "\r\n" + filenameHeader(name), OWNER_COOKIE, CHUNK);
  }

  private static Socket startHeldUpload(String folderId, String name) throws Exception {
    var storages = FilesStackTestResource.getStoragesService();
    storages.holdUploads();
    try {
      Socket socket = startUpload(folderId, name);
      socket.getOutputStream().write(new byte[CHUNK]);
      socket.getOutputStream().flush();
      storages.awaitHeldUpload();
      return socket;
    } catch (Exception | Error e) {
      storages.releaseUploads();
      throw e;
    }
  }

  private static String finishHeldUpload(Socket socket) throws Exception {
    FilesStackTestResource.getStoragesService().releaseUploads();
    return new java.io.BufferedReader(
            new java.io.InputStreamReader(
                socket.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))
        .readLine();
  }

  @Test
  void givenAnUploadStreamingThenNoNodeExistsInTheFolder() throws Exception {
    String folder = seedFolder("race-upload-streaming", LOCAL_ROOT, OWNER_COOKIE);

    String status;
    try (Socket socket = startHeldUpload(folder, "streaming.bin")) {
      Assertions.assertThat(children(folder)).isZero();
      status = finishHeldUpload(socket);
    }

    Assertions.assertThat(status).startsWith("HTTP/1.1 200");
    Assertions.assertThat(children(folder)).isEqualTo(1);
    Assertions.assertThat(revisionRows(onlyChildId(folder))).isEqualTo(1);
  }

  @Test
  void givenTheFolderMovedIntoAPrivateFolderMidUploadThenTheSharesAreTheCurrentOnes()
      throws Exception {
    String shared = seedFolder("race-upload-shared", LOCAL_ROOT, OWNER_COOKIE);
    seedShare(shared, SHAREE_ID, SharePermission.READ_ONLY, OWNER_COOKIE);
    String folder = seedFolder("race-upload-moved", shared, OWNER_COOKIE);
    String privateFolder = seedFolder("race-upload-private", LOCAL_ROOT, OWNER_COOKIE);

    String status;
    try (Socket socket = startHeldUpload(folder, "secret.bin")) {
      graphql(
          GraphqlCommandBuilder.aMutationBuilder("moveNodes")
              .withListOfStrings("node_ids", new String[] {folder})
              .withString("destination_id", privateFolder)
              .withWantedResultFormat("{ id }")
              .build(),
          OWNER_COOKIE);
      status = finishHeldUpload(socket);
    }

    String node = onlyChildId(folder);
    Assertions.assertThat(status).startsWith("HTTP/1.1 200");
    Assertions.assertThat(
            rowExists("SELECT 1 FROM share WHERE node_id = ? AND target_uuid = ?", node, SHAREE_ID))
        .isFalse();
    Assertions.assertThat(nodeExists(node, SHAREE_COOKIE)).isFalse();
  }

  @Test
  void givenTheFolderDeletedMidUploadThenTheUploadIs404AndNoBlobRemains() throws Exception {
    String folder = seedFolder("race-upload-deleted", LOCAL_ROOT, OWNER_COOKIE);
    int blobsBefore = FilesStackTestResource.getStoragesService().blobCount();

    String status;
    try (Socket socket = startHeldUpload(folder, "doomed-race.bin")) {
      graphql(
          GraphqlCommandBuilder.aMutationBuilder("deleteNodes")
              .withListOfStrings("node_ids", new String[] {folder})
              .withWantedResultFormat("")
              .build(),
          OWNER_COOKIE);
      status = finishHeldUpload(socket);
    }

    Assertions.assertThat(status).startsWith("HTTP/1.1 404");
    Assertions.assertThat(rowExists("SELECT 1 FROM node WHERE name = ?", "doomed-race.bin"))
        .isFalse();
    Assertions.assertThat(FilesStackTestResource.getStoragesService().blobCount())
        .isEqualTo(blobsBefore);
  }

  @Test
  void givenTheFolderTrashedMidUploadThenTheNodeLivesInTheTrashedFolder() throws Exception {
    String folder = seedFolder("race-upload-trashed", LOCAL_ROOT, OWNER_COOKIE);

    String status;
    try (Socket socket = startHeldUpload(folder, "trashed-race.bin")) {
      graphql(
          GraphqlCommandBuilder.aMutationBuilder("trashNodes")
              .withListOfStrings("node_ids", new String[] {folder})
              .withWantedResultFormat("")
              .build(),
          OWNER_COOKIE);
      status = finishHeldUpload(socket);
    }

    String node = onlyChildId(folder);
    Assertions.assertThat(status).startsWith("HTTP/1.1 200");
    Assertions.assertThat(nodeColumnRaw(node, "folder_id")).isEqualTo(folder);
    Assertions.assertThat(nodeColumnRaw(node, "ancestor_ids")).startsWith("TRASH_ROOT");
  }

  @Test
  void givenTheShareRevokedMidUploadThenTheRevokedUserGetsNoShare() throws Exception {
    String folder = seedFolder("race-upload-revoked", LOCAL_ROOT, OWNER_COOKIE);
    seedShare(folder, SHAREE_ID, SharePermission.READ_ONLY, OWNER_COOKIE);

    String status;
    try (Socket socket = startHeldUpload(folder, "revoked-race.bin")) {
      graphql(
          GraphqlCommandBuilder.aMutationBuilder("deleteShares")
              .withString("node_id", folder)
              .withListOfStrings("share_target_ids", new String[] {SHAREE_ID})
              .withWantedResultFormat("")
              .build(),
          OWNER_COOKIE);
      status = finishHeldUpload(socket);
    }

    String node = onlyChildId(folder);
    Assertions.assertThat(status).startsWith("HTTP/1.1 200");
    Assertions.assertThat(
            rowExists("SELECT 1 FROM share WHERE node_id = ? AND target_uuid = ?", node, SHAREE_ID))
        .isFalse();
  }

  @Test
  void givenTwoSameNameUploadsPublishingTogetherThenNamesAreDistinct() throws Exception {
    String folder = seedFolder("race-upload-same-name", LOCAL_ROOT, OWNER_COOKIE);

    String first;
    String second;
    try (Connection holder = holdRow("SELECT 1 FROM node WHERE node_id = ? FOR SHARE", folder);
        Socket socketA = startUpload(folder, "same.txt");
        Socket socketB = startUpload(folder, "same.txt")) {
      CompletableFuture<String> finishA = CompletableFuture.supplyAsync(() -> finish(socketA));
      CompletableFuture<String> finishB = CompletableFuture.supplyAsync(() -> finish(socketB));
      awaitBlockedBy(holder, 2);
      holder.rollback();
      first = finishA.get(30, TimeUnit.SECONDS);
      second = finishB.get(30, TimeUnit.SECONDS);
    }

    Assertions.assertThat(first).startsWith("HTTP/1.1 200");
    Assertions.assertThat(second).startsWith("HTTP/1.1 200");
    Assertions.assertThat(childNames(folder)).containsExactlyInAnyOrder("same.txt", "same (1).txt");
  }

  private static String finish(Socket socket) {
    try {
      return finishRawUpload(socket, CHUNK);
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  private static int children(String folderId) throws SQLException {
    try (Connection connection = jdbcConnection();
        PreparedStatement statement =
            connection.prepareStatement("SELECT count(*) FROM node WHERE folder_id = ?")) {
      statement.setString(1, folderId);
      try (ResultSet rows = statement.executeQuery()) {
        rows.next();
        return rows.getInt(1);
      }
    }
  }

  private static String onlyChildId(String folderId) throws SQLException {
    try (Connection connection = jdbcConnection();
        PreparedStatement statement =
            connection.prepareStatement("SELECT trim(node_id) FROM node WHERE folder_id = ?")) {
      statement.setString(1, folderId);
      try (ResultSet rows = statement.executeQuery()) {
        Assertions.assertThat(rows.next()).isTrue();
        return rows.getString(1);
      }
    }
  }

  private static java.util.List<String> childNames(String folderId) throws SQLException {
    java.util.List<String> names = new java.util.ArrayList<>();
    try (Connection connection = jdbcConnection();
        PreparedStatement statement =
            connection.prepareStatement("SELECT name FROM node WHERE folder_id = ?")) {
      statement.setString(1, folderId);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          names.add(rows.getString(1));
        }
      }
    }
    return names;
  }

  private static int revisionRows(String nodeId) throws SQLException {
    try (Connection connection = jdbcConnection();
        PreparedStatement statement =
            connection.prepareStatement("SELECT count(*) FROM revision WHERE node_id = ?")) {
      statement.setString(1, nodeId);
      try (ResultSet rows = statement.executeQuery()) {
        rows.next();
        return rows.getInt(1);
      }
    }
  }
}
