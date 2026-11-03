// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.response.Response;
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

class RaceCopyApiIT extends AbstractFilesIT {

  private static final String OWNER_ID = "41111111-1111-1111-1111-111111111111";
  private static final String OWNER_COOKIE = "ZM_AUTH_TOKEN=race-copy-owner";
  private static final String USER_ID = "42222222-2222-2222-2222-222222222222";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("race-copy-owner", OWNER_ID);
    FilesStackTestResource.getUserManagementService().registerToken("race-copy-user", USER_ID);
  }

  private static Response copyNodes(String[] nodeIds, String destinationId, String cookie) {
    return graphql(
        GraphqlCommandBuilder.aMutationBuilder("copyNodes")
            .withListOfStrings("node_ids", nodeIds)
            .withString("destination_id", destinationId)
            .withWantedResultFormat("{ id name }")
            .build(),
        cookie);
  }

  private static Response mutateNode(String mutation, String nodeId) {
    return graphql(
        GraphqlCommandBuilder.aMutationBuilder(mutation)
            .withListOfStrings("node_ids", new String[] {nodeId})
            .withWantedResultFormat("")
            .build(),
        OWNER_COOKIE);
  }

  @FunctionalInterface
  private interface HeldAction {
    void run() throws Exception;
  }

  private static Response copyWhileHeld(
      String[] nodeIds, String destinationId, String cookie, HeldAction duringHold)
      throws Exception {
    var storages = FilesStackTestResource.getStoragesService();
    storages.holdCopies();
    CompletableFuture<Response> pending = null;
    try {
      pending = CompletableFuture.supplyAsync(() -> copyNodes(nodeIds, destinationId, cookie));
      storages.awaitHeldCopy();
      duringHold.run();
      storages.releaseCopies();
      return pending.get(30, TimeUnit.SECONDS);
    } finally {
      storages.releaseCopies();
      if (pending != null) {
        try {
          pending.get(30, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
      }
    }
  }

  private static boolean inheritsShare(String copiedNode, String destination, String target)
      throws SQLException {
    return rowExists(
        "SELECT 1 FROM share s JOIN share d ON d.node_id = ? AND d.target_uuid = s.target_uuid"
            + " AND d.rights = s.rights WHERE s.node_id = ? AND s.target_uuid = ?"
            + " AND s.direct = false",
        destination,
        copiedNode,
        target);
  }

  private static String seedSourceFolderWithTwoFiles(String name) {
    String source = seedFolder(name, LOCAL_ROOT, OWNER_COOKIE);
    seedFile(name + "-a.txt", source, "a".getBytes(UTF_8), OWNER_COOKIE);
    seedFile(name + "-b.txt", source, "b".getBytes(UTF_8), OWNER_COOKIE);
    return source;
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

  private static List<String[]> descendants(String folderId) throws SQLException {
    List<String[]> found = new ArrayList<>();
    try (Connection connection = jdbcConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT trim(node_id), trim(ancestor_ids) FROM node WHERE ancestor_ids LIKE ?")) {
      statement.setString(1, "%" + folderId + "%");
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          found.add(new String[] {rows.getString(1), rows.getString(2)});
        }
      }
    }
    return found;
  }

  @Test
  void givenACopyInProgressThenNothingExistsInTheDestination() throws Exception {
    String source = seedSourceFolderWithTwoFiles("race-copy-progress");
    String destination = seedFolder("race-copy-progress-dest", LOCAL_ROOT, OWNER_COOKIE);

    Response response =
        copyWhileHeld(
            new String[] {source},
            destination,
            OWNER_COOKIE,
            () -> Assertions.assertThat(childCount(destination)).isZero());

    Assertions.assertThat(response.jsonPath().getList("errors")).isNull();
    String copied = onlyChildId(destination);
    Assertions.assertThat(response.jsonPath().getList("data.copyNodes.id")).containsExactly(copied);
    Assertions.assertThat(childCount(copied)).isEqualTo(2);
    try (Connection connection = jdbcConnection();
        PreparedStatement statement =
            connection.prepareStatement("SELECT trim(node_id) FROM node WHERE folder_id = ?")) {
      statement.setString(1, copied);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          Assertions.assertThat(revisionRows(rows.getString(1))).isEqualTo(1);
        }
      }
    }
  }

  @Test
  void givenAFileBlobFailingThenTheFolderCopyFailsAndNothingIsPublished() throws Exception {
    String source = seedFolder("race-copy-fail", LOCAL_ROOT, OWNER_COOKIE);
    seedFile("race-copy-fail-a.txt", source, "a".getBytes(UTF_8), OWNER_COOKIE);
    String sub = seedFolder("race-copy-fail-sub", source, OWNER_COOKIE);
    seedFile("race-copy-fail-b.txt", sub, "b".getBytes(UTF_8), OWNER_COOKIE);
    String destination = seedFolder("race-copy-fail-dest", LOCAL_ROOT, OWNER_COOKIE);
    var storages = FilesStackTestResource.getStoragesService();
    int blobsBefore = storages.blobCount();

    Response response;
    storages.failCopiesAfter(1);
    try {
      response = copyNodes(new String[] {source}, destination, OWNER_COOKIE);
    } finally {
      storages.failCopiesAfter(-1);
    }

    Assertions.assertThat(response.jsonPath().getList("errors")).hasSize(1);
    Assertions.assertThat(response.jsonPath().getString("errors[0].extensions.errorCode"))
        .isEqualTo("NODE_COPY_ERROR");
    Assertions.assertThat(response.jsonPath().getString("errors[0].extensions.nodeId"))
        .isEqualTo(source);
    Assertions.assertThat(response.jsonPath().getList("data.copyNodes")).isEmpty();
    Assertions.assertThat(childCount(destination)).isZero();
    Assertions.assertThat(storages.blobCount()).isEqualTo(blobsBefore);
    Assertions.assertThat(storages.copyRequests()).hasSizeGreaterThanOrEqualTo(2);
  }

  @Test
  void givenTheDestinationTrashedMidCopyThenEveryCopiedNodeIsUnderTheTrash() throws Exception {
    String source = seedSourceFolderWithTwoFiles("race-copy-trashed");
    String destination = seedFolder("race-copy-trashed-dest", LOCAL_ROOT, OWNER_COOKIE);

    copyWhileHeld(
        new String[] {source},
        destination,
        OWNER_COOKIE,
        () -> mutateNode("trashNodes", destination));

    String copied = onlyChildId(destination);
    Assertions.assertThat(nodeColumnRaw(copied, "ancestor_ids")).startsWith("TRASH_ROOT");
    List<String[]> inside = descendants(copied);
    Assertions.assertThat(inside)
        .hasSize(2)
        .allSatisfy(row -> Assertions.assertThat(row[1]).contains("TRASH_ROOT"));
  }

  @Test
  void givenTheDestinationDeletedMidCopyThenNodeWriteErrorAndNoRows() throws Exception {
    String source = seedSourceFolderWithTwoFiles("race-copy-deleted");
    String destination = seedFolder("race-copy-deleted-dest", LOCAL_ROOT, OWNER_COOKIE);
    int blobsBefore = FilesStackTestResource.getStoragesService().blobCount();

    Response response =
        copyWhileHeld(
            new String[] {source},
            destination,
            OWNER_COOKIE,
            () -> mutateNode("deleteNodes", destination));

    Assertions.assertThat(response.jsonPath().getString("errors[0].extensions.errorCode"))
        .isEqualTo("NODE_WRITE_ERROR");
    Assertions.assertThat(response.jsonPath().getString("errors[0].extensions.nodeId"))
        .isEqualTo(destination);
    Assertions.assertThat(
            rowExists(
                "SELECT 1 FROM node WHERE name = ? AND node_id <> ?", "race-copy-deleted", source))
        .isFalse();
    Assertions.assertThat(FilesStackTestResource.getStoragesService().blobCount())
        .isEqualTo(blobsBefore);
  }

  @Test
  void givenOneOfTwoFilesFailingThenTheOtherIsCopiedAndTheErrorReported() throws Exception {
    String file1 = seedFile("race-copy-two-a.txt", LOCAL_ROOT, "a".getBytes(UTF_8), OWNER_COOKIE);
    String file2 = seedFile("race-copy-two-b.txt", LOCAL_ROOT, "b".getBytes(UTF_8), OWNER_COOKIE);
    String destination = seedFolder("race-copy-two-dest", LOCAL_ROOT, OWNER_COOKIE);

    FilesStackTestResource.getStoragesService().forceMissing(file2, 1);
    Response response = copyNodes(new String[] {file1, file2}, destination, OWNER_COOKIE);

    Assertions.assertThat(response.jsonPath().getList("data.copyNodes.name"))
        .containsExactly("race-copy-two-a");
    Assertions.assertThat(response.jsonPath().getString("errors[0].extensions.errorCode"))
        .isEqualTo("NODE_COPY_ERROR");
    Assertions.assertThat(response.jsonPath().getString("errors[0].extensions.nodeId"))
        .isEqualTo(file2);
    Assertions.assertThat(childCount(destination)).isEqualTo(1);
  }

  @Test
  void givenTheDestinationRowHeldThenThePublishWaitsAndThenSucceeds() throws Exception {
    String file = seedFile("race-copy-held.txt", LOCAL_ROOT, "h".getBytes(UTF_8), OWNER_COOKIE);
    String destination = seedFolder("race-copy-held-dest", LOCAL_ROOT, OWNER_COOKIE);
    var storages = FilesStackTestResource.getStoragesService();

    Response response =
        copyWhileHeld(
            new String[] {file},
            destination,
            OWNER_COOKIE,
            () -> {
              try (Connection holder =
                  holdRow("SELECT 1 FROM node WHERE node_id = ? FOR SHARE", destination)) {
                storages.releaseCopies();
                awaitBlockedBy(holder, 1);
                holder.rollback();
              }
            });

    Assertions.assertThat(response.jsonPath().getList("errors")).isNull();
    Assertions.assertThat(childCount(destination)).isEqualTo(1);
  }

  @Test
  void givenASameNameFileUploadedDuringTheCopyThenTheCopyIsRenamedAtPublish() throws Exception {
    String file = seedFile("race-copy-dedup.txt", LOCAL_ROOT, "x".getBytes(UTF_8), OWNER_COOKIE);
    String destination = seedFolder("race-copy-dedup-dest", LOCAL_ROOT, OWNER_COOKIE);

    Response response =
        copyWhileHeld(
            new String[] {file},
            destination,
            OWNER_COOKIE,
            () -> seedFile("race-copy-dedup.txt", destination, "y".getBytes(UTF_8), OWNER_COOKIE));

    Assertions.assertThat(response.jsonPath().getList("errors")).isNull();
    Assertions.assertThat(response.jsonPath().getList("data.copyNodes.name"))
        .containsExactly("race-copy-dedup (1)");
    Assertions.assertThat(childCount(destination)).isEqualTo(2);
  }

  @Test
  void givenACopyIntoASharedFolderThenEveryCopiedDescendantInheritsTheShares() throws Exception {
    String destination = seedFolder("race-copy-shared-dest", LOCAL_ROOT, OWNER_COOKIE);
    seedShare(destination, USER_ID, SharePermission.READ_AND_WRITE, OWNER_COOKIE);
    String source = seedFolder("race-copy-shared-src", LOCAL_ROOT, OWNER_COOKIE);
    String sub = seedFolder("race-copy-shared-sub", source, OWNER_COOKIE);
    seedFile("race-copy-shared-file.txt", sub, "s".getBytes(UTF_8), OWNER_COOKIE);

    Response response = copyNodes(new String[] {source}, destination, OWNER_COOKIE);

    Assertions.assertThat(response.jsonPath().getList("errors")).isNull();
    String copiedFolder = onlyChildId(destination);
    String copiedSub = onlyChildId(copiedFolder);
    String copiedFile = onlyChildId(copiedSub);
    Assertions.assertThat(inheritsShare(copiedFolder, destination, USER_ID)).isTrue();
    Assertions.assertThat(inheritsShare(copiedSub, destination, USER_ID)).isTrue();
    Assertions.assertThat(inheritsShare(copiedFile, destination, USER_ID)).isTrue();
  }
}
