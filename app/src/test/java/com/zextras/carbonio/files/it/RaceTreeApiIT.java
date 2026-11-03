// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.Constants.Config.Link;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RaceTreeApiIT extends AbstractFilesIT {

  private static final String OWNER_ID = "51111111-1111-1111-1111-111111111111";
  private static final String OWNER_COOKIE = "ZM_AUTH_TOKEN=race-tree-owner";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("race-tree-owner", OWNER_ID);
  }

  private static Response createFolder(String parentId, String name) {
    return graphql(
        GraphqlCommandBuilder.aMutationBuilder("createFolder")
            .withString("destination_id", parentId)
            .withString("name", name)
            .withWantedResultFormat("{ id name }")
            .build(),
        OWNER_COOKIE);
  }

  private static Response rename(String nodeId, String name) {
    return graphql(
        GraphqlCommandBuilder.aMutationBuilder("updateNode")
            .withString("node_id", nodeId)
            .withString("name", name)
            .withWantedResultFormat("{ id name }")
            .build(),
        OWNER_COOKIE);
  }

  private static Response flag(String nodeId) {
    return graphql(
        GraphqlCommandBuilder.aMutationBuilder("flagNodes")
            .withListOfStrings("node_ids", new String[] {nodeId})
            .withBoolean("flag", true)
            .withWantedResultFormat("")
            .build(),
        OWNER_COOKIE);
  }

  private static Response createLink(String nodeId) {
    return graphql(
        GraphqlCommandBuilder.aMutationBuilder("createLink")
            .withString("node_id", nodeId)
            .withWantedResultFormat("{ id }")
            .build(),
        OWNER_COOKIE);
  }

  private static Response createCollaborationLink(String nodeId) {
    return graphql(
        GraphqlCommandBuilder.aMutationBuilder("createCollaborationLink")
            .withString("node_id", nodeId)
            .withEnum("permission", SharePermission.READ_ONLY)
            .withWantedResultFormat("{ id }")
            .build(),
        OWNER_COOKIE);
  }

  private static List<String> namesUnder(String folderId) throws SQLException {
    List<String> names = new ArrayList<>();
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

  private static int count(String sql, String param) throws SQLException {
    try (Connection connection = jdbcConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, param);
      try (ResultSet rows = statement.executeQuery()) {
        rows.next();
        return rows.getInt(1);
      }
    }
  }

  private static void advisory(Connection connection, String function, String ownerId)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT " + function + "(hashtextextended('files-children/LOCAL_ROOT/' || ?, 0))")) {
      statement.setString(1, ownerId);
      statement.execute();
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

  private static List<Response> twoConcurrentUnderRowLock(
      String sql, String lockedId, Supplier<Response> first, Supplier<Response> second)
      throws Exception {
    List<CompletableFuture<Response>> pending = new ArrayList<>();
    try (Connection holder = holdRow(sql, lockedId)) {
      try {
        pending.add(CompletableFuture.supplyAsync(first, RACE_EXECUTOR));
        pending.add(CompletableFuture.supplyAsync(second, RACE_EXECUTOR));
        awaitBlockedOrDone(holder, 2, pending.toArray(new CompletableFuture<?>[0]));
      } finally {
        holder.rollback();
      }
      List<Response> responses = new ArrayList<>();
      for (CompletableFuture<Response> future : pending) {
        responses.add(future.get(30, TimeUnit.SECONDS));
      }
      return responses;
    } finally {
      awaitAll(pending);
    }
  }

  private static Response moveNodes(String destinationId, String... nodeIds) {
    return graphql(
        GraphqlCommandBuilder.aMutationBuilder("moveNodes")
            .withListOfStrings("node_ids", nodeIds)
            .withString("destination_id", destinationId)
            .withWantedResultFormat("{ id }")
            .build(),
        OWNER_COOKIE);
  }

  private static Response trashNodes(String... nodeIds) {
    return graphql(
        GraphqlCommandBuilder.aMutationBuilder("trashNodes")
            .withListOfStrings("node_ids", nodeIds)
            .withWantedResultFormat("")
            .build(),
        OWNER_COOKIE);
  }

  private static Response restoreNodes(String... nodeIds) {
    return graphql(
        GraphqlCommandBuilder.aMutationBuilder("restoreNodes")
            .withListOfStrings("node_ids", nodeIds)
            .withWantedResultFormat("{ id }")
            .build(),
        OWNER_COOKIE);
  }

  private static Response deleteNodes(String... nodeIds) {
    return graphql(
        GraphqlCommandBuilder.aMutationBuilder("deleteNodes")
            .withListOfStrings("node_ids", nodeIds)
            .withWantedResultFormat("")
            .build(),
        OWNER_COOKIE);
  }

  private static boolean isTrashed(String nodeId) throws SQLException {
    return rowExists("SELECT 1 FROM trashed WHERE node_id = ?", nodeId);
  }

  private static boolean hasError(Response response) {
    return response.jsonPath().getList("errors") != null;
  }

  private static void join(List<CompletableFuture<Response>> pending, List<Response> out)
      throws Exception {
    for (CompletableFuture<Response> future : pending) {
      out.add(future.get(30, TimeUnit.SECONDS));
    }
  }

  @Test
  void givenTwoSameNameCreateFolderTogetherThenNamesAreDistinct() throws Exception {
    String parent = seedFolder("race-tree-parent", LOCAL_ROOT, OWNER_COOKIE);

    List<Response> responses =
        twoConcurrentUnderRowLock(
            "SELECT 1 FROM node WHERE node_id = ? FOR SHARE",
            parent,
            () -> createFolder(parent, "x"),
            () -> createFolder(parent, "x"));

    responses.forEach(
        response -> Assertions.assertThat(response.jsonPath().getList("errors")).isNull());
    Assertions.assertThat(namesUnder(parent)).containsExactlyInAnyOrder("x", "x (1)");
  }

  @Test
  void givenTwoSameNameCreateFolderInTheRootTogetherThenNamesAreDistinct() throws Exception {
    String name = "x-root-" + UUID.randomUUID();
    List<CompletableFuture<Response>> pending = new ArrayList<>();
    List<Response> responses = new ArrayList<>();
    Connection holder = jdbcConnection();
    boolean released = false;
    try {
      advisory(holder, "pg_advisory_lock", OWNER_ID);
      try {
        pending.add(
            CompletableFuture.supplyAsync(() -> createFolder(LOCAL_ROOT, name), RACE_EXECUTOR));
        pending.add(
            CompletableFuture.supplyAsync(() -> createFolder(LOCAL_ROOT, name), RACE_EXECUTOR));
        awaitBlockedOrDone(holder, 2, pending.toArray(new CompletableFuture<?>[0]));
      } finally {
        advisory(holder, "pg_advisory_unlock", OWNER_ID);
        released = true;
      }
      for (CompletableFuture<Response> future : pending) {
        responses.add(future.get(30, TimeUnit.SECONDS));
      }
    } finally {
      if (!released) {
        advisory(holder, "pg_advisory_unlock", OWNER_ID);
      }
      holder.close();
      awaitAll(pending);
    }

    responses.forEach(
        response -> Assertions.assertThat(response.jsonPath().getList("errors")).isNull());
    Assertions.assertThat(
            responses.stream().map(r -> r.jsonPath().getString("data.createFolder.name")))
        .containsExactlyInAnyOrder(name, name + " (1)");
  }

  @Test
  void givenARenameRacingACreateWithTheTargetNameThenNamesStayDistinct() throws Exception {
    String parent = seedFolder("race-tree-rename-parent", LOCAL_ROOT, OWNER_COOKIE);
    String folderA = seedFolder("a", parent, OWNER_COOKIE);

    twoConcurrentUnderRowLock(
        "SELECT 1 FROM node WHERE node_id = ? FOR SHARE",
        parent,
        () -> rename(folderA, "b"),
        () -> createFolder(parent, "b"));

    List<String> names = namesUnder(parent);
    Assertions.assertThat(names).hasSize(2).doesNotHaveDuplicates();
  }

  @Test
  void givenTwoFirstFlagsOfTheSameNodeTogetherThenBothSucceed() throws Exception {
    String node = seedFolder("race-tree-flag", LOCAL_ROOT, OWNER_COOKIE);

    List<Response> responses =
        twoConcurrentUnderRowLock(
            "SELECT 1 FROM node WHERE node_id = ? FOR SHARE",
            node,
            () -> flag(node),
            () -> flag(node));

    responses.forEach(
        response -> Assertions.assertThat(response.jsonPath().getList("errors")).isNull());
    Assertions.assertThat(
            count("SELECT count(*) FROM custom WHERE node_id = ? AND star = true", node))
        .isEqualTo(1);
  }

  @Test
  void givenTwoCreateLinkAtTheCapTogetherThenTheCapHolds() throws Exception {
    String node = seedFolder("race-tree-link", LOCAL_ROOT, OWNER_COOKIE);
    for (int i = 0; i < Link.MAX_LINKS_PER_NODE - 1; i++) {
      seedLink(node, OWNER_COOKIE);
    }

    List<Response> responses =
        twoConcurrentUnderRowLock(
            "SELECT 1 FROM node WHERE node_id = ? FOR SHARE",
            node,
            () -> createLink(node),
            () -> createLink(node));

    long failures = responses.stream().filter(r -> r.jsonPath().getList("errors") != null).count();
    Assertions.assertThat(failures).isEqualTo(1);
    Assertions.assertThat(
            responses.stream()
                .filter(r -> r.jsonPath().getList("errors") != null)
                .map(r -> r.jsonPath().getString("errors[0].extensions.errorCode")))
        .containsExactly("LINK_LIMIT_EXCEEDED");
    Assertions.assertThat(count("SELECT count(*) FROM link WHERE node_id = ?", node))
        .isEqualTo(Link.MAX_LINKS_PER_NODE);
  }

  @Test
  void givenTwoCreateCollaborationLinkWithTheSamePermissionTogetherThenOneLinkExists()
      throws Exception {
    String node = seedFolder("race-tree-collab", LOCAL_ROOT, OWNER_COOKIE);

    List<Response> responses =
        twoConcurrentUnderRowLock(
            "SELECT 1 FROM node WHERE node_id = ? FOR SHARE",
            node,
            () -> createCollaborationLink(node),
            () -> createCollaborationLink(node));

    responses.forEach(
        response -> Assertions.assertThat(response.jsonPath().getList("errors")).isNull());
    Assertions.assertThat(count("SELECT count(*) FROM collaboration_link WHERE node_id = ?", node))
        .isEqualTo(1);
  }

  @Test
  void givenAMoveRacingATrashOfTheSameNodeThenNoGhost() throws Exception {
    String source = seedFolder("race-tree-ghost-src", LOCAL_ROOT, OWNER_COOKIE);
    String destination = seedFolder("race-tree-ghost-dst", LOCAL_ROOT, OWNER_COOKIE);
    String fileA = seedFile("ghost-a", source, new byte[] {1}, OWNER_COOKIE);
    String fileB = seedFile("ghost-b", source, new byte[] {1}, OWNER_COOKIE);
    String gate = fileA.compareTo(fileB) < 0 ? fileA : fileB;
    String victim = gate.equals(fileA) ? fileB : fileA;

    List<CompletableFuture<Response>> pending = new ArrayList<>();
    List<Response> responses = new ArrayList<>();
    try (Connection holder = holdRow("SELECT 1 FROM node WHERE node_id = ? FOR UPDATE", gate)) {
      try {
        pending.add(
            CompletableFuture.supplyAsync(
                () -> moveNodes(destination, gate, victim), RACE_EXECUTOR));
        awaitBlockedBy(holder, 1);
        pending.add(CompletableFuture.supplyAsync(() -> trashNodes(victim), RACE_EXECUTOR));
        awaitBlockedOrDone(holder, 2, pending.toArray(new CompletableFuture<?>[0]));
      } finally {
        holder.rollback();
      }
      join(pending, responses);
    } finally {
      awaitAll(pending);
    }

    responses.forEach(response -> Assertions.assertThat(hasError(response)).isFalse());
    Assertions.assertThat(nodeColumnRaw(victim, "folder_id")).isEqualTo("TRASH_ROOT");
    Assertions.assertThat(isTrashed(victim)).isTrue();
  }

  @Test
  void givenATrashedNodeMovedThenItLeavesTheTrash() throws Exception {
    String destination = seedFolder("race-tree-b0-dst", LOCAL_ROOT, OWNER_COOKIE);
    String file = seedFile("b0-file", LOCAL_ROOT, new byte[] {1}, OWNER_COOKIE);
    seedTrashed(file, OWNER_COOKIE);

    Response response = moveNodes(destination, file);

    Assertions.assertThat(hasError(response)).isFalse();
    Assertions.assertThat(nodeColumnRaw(file, "folder_id")).isEqualTo(destination);
    Assertions.assertThat(isTrashed(file)).isFalse();
    Assertions.assertThat(nodeColumnRaw(file, "ancestor_ids")).doesNotContain("TRASH_ROOT");
  }

  private static void assertNoCycle() throws SQLException {
    Assertions.assertThat(
            rowExists(
                "SELECT 1 FROM node WHERE position(trim(node_id) in coalesce(ancestor_ids,''))"
                    + " > 0"))
        .isFalse();
  }

  private static void assertAtMostOneSucceeded(List<Response> responses) {
    List<Response> succeeded = responses.stream().filter(r -> !hasError(r)).toList();
    Assertions.assertThat(succeeded).hasSizeLessThanOrEqualTo(1);
    responses.stream()
        .filter(RaceTreeApiIT::hasError)
        .forEach(r -> Assertions.assertThat(r.getBody().asString()).doesNotContain("System error"));
  }

  @Test
  void givenCrossingMovesThenNoCycle() throws Exception {
    String parentOne = seedFolder("race-tree-cross-p1", LOCAL_ROOT, OWNER_COOKIE);
    String parentTwo = seedFolder("race-tree-cross-p2", LOCAL_ROOT, OWNER_COOKIE);
    String folderA = seedFolder("race-tree-cross-a", parentOne, OWNER_COOKIE);
    String folderB = seedFolder("race-tree-cross-b", parentTwo, OWNER_COOKIE);

    List<Response> responses =
        twoConcurrentUnderRowLock(
            "SELECT 1 FROM node WHERE node_id = ? FOR SHARE",
            folderA,
            () -> moveNodes(folderB, folderA),
            () -> moveNodes(folderA, folderB));

    assertAtMostOneSucceeded(responses);
    List<Response> failed = responses.stream().filter(RaceTreeApiIT::hasError).toList();
    Assertions.assertThat(failed).hasSize(1);
    Assertions.assertThat(TestUtils.jsonResponseToErrorCodes(failed.get(0).getBody().asString()))
        .containsExactly("NODE_WRITE_ERROR");
    assertNoCycle();
  }

  @Test
  void givenNestedCrossingMovesThenNoCycle() throws Exception {
    String root = seedFolder("race-tree-nested-root", LOCAL_ROOT, OWNER_COOKIE);
    String p = seedFolder("race-tree-nested-p", root, OWNER_COOKIE);
    String d = seedFolder("race-tree-nested-d", p, OWNER_COOKIE);
    String q = seedFolder("race-tree-nested-q", root, OWNER_COOKIE);
    String x = seedFolder("race-tree-nested-x", q, OWNER_COOKIE);
    String y = seedFolder("race-tree-nested-y", x, OWNER_COOKIE);
    String childOfX = seedFolder("race-tree-nested-cx", x, OWNER_COOKIE);
    String childOfP = seedFolder("race-tree-nested-cp", p, OWNER_COOKIE);

    List<CompletableFuture<Response>> pending = new ArrayList<>();
    List<Response> responses = new ArrayList<>();
    try (Connection holder =
        holdRow("SELECT 1 FROM node WHERE node_id IN (?, ?) FOR SHARE", childOfX, childOfP)) {
      try {
        pending.add(CompletableFuture.supplyAsync(() -> moveNodes(d, x), RACE_EXECUTOR));
        pending.add(CompletableFuture.supplyAsync(() -> moveNodes(y, p), RACE_EXECUTOR));
        awaitBlockedOrDone(holder, 2, pending.toArray(new CompletableFuture<?>[0]));
      } finally {
        holder.rollback();
      }
      join(pending, responses);
    } finally {
      awaitAll(pending);
    }

    assertAtMostOneSucceeded(responses);
    assertNoCycle();
  }

  @Test
  void givenARestoreRacingAMoveOutOfTheTrashThenOneWins() throws Exception {
    String folder = seedFolder("race-tree-rm-f", LOCAL_ROOT, OWNER_COOKIE);
    String destination = seedFolder("race-tree-rm-d", LOCAL_ROOT, OWNER_COOKIE);
    String file = seedFile("rm-x", folder, new byte[] {1}, OWNER_COOKIE);
    seedTrashed(file, OWNER_COOKIE);

    twoConcurrentUnderRowLock(
        "SELECT 1 FROM node WHERE node_id = ? FOR SHARE",
        file,
        () -> restoreNodes(file),
        () -> moveNodes(destination, file));

    String where = nodeColumnRaw(file, "folder_id");
    Assertions.assertThat(where).isIn(folder, destination);
    Assertions.assertThat(isTrashed(file)).isFalse();
    Assertions.assertThat(nodeColumnRaw(file, "ancestor_ids")).doesNotContain("TRASH_ROOT");
  }

  private void restoreAndDeleteDestination(boolean restoreFirst) throws Exception {
    String folder = seedFolder("race-tree-orphan-f", LOCAL_ROOT, OWNER_COOKIE);
    String file = seedFile("orphan-x", folder, new byte[] {1}, OWNER_COOKIE);
    seedTrashed(file, OWNER_COOKIE);

    List<CompletableFuture<Response>> pending = new ArrayList<>();
    try (Connection holder = holdRow("SELECT 1 FROM node WHERE node_id = ? FOR SHARE", folder)) {
      try {
        Supplier<Response> restore = () -> restoreNodes(file);
        Supplier<Response> delete = () -> deleteNodes(folder);
        pending.add(CompletableFuture.supplyAsync(restoreFirst ? restore : delete, RACE_EXECUTOR));
        awaitBlockedBy(holder, 1);
        pending.add(CompletableFuture.supplyAsync(restoreFirst ? delete : restore, RACE_EXECUTOR));
        awaitBlockedOrDone(holder, 2, pending.toArray(new CompletableFuture<?>[0]));
      } finally {
        holder.rollback();
      }
      join(pending, new ArrayList<>());
    } finally {
      awaitAll(pending);
    }

    Assertions.assertThat(
            rowExists(
                "SELECT 1 FROM node n WHERE n.folder_id IS NOT NULL AND NOT EXISTS"
                    + " (SELECT 1 FROM node p WHERE p.node_id = n.folder_id) AND n.node_id = ?",
                file))
        .isFalse();
  }

  @Test
  void givenARestoreThenADeleteOfItsDestinationThenNoOrphan() throws Exception {
    restoreAndDeleteDestination(true);
  }

  @Test
  void givenADeleteOfTheDestinationThenARestoreThenNoOrphan() throws Exception {
    restoreAndDeleteDestination(false);
  }

  @Test
  void givenTwoTrashOfTheSameNodeTogetherThenOneSucceedsWithoutSystemError() throws Exception {
    String file = seedFile("double-trash", LOCAL_ROOT, new byte[] {1}, OWNER_COOKIE);

    List<Response> responses =
        twoConcurrentUnderRowLock(
            "SELECT 1 FROM node WHERE node_id = ? FOR SHARE",
            file,
            () -> trashNodes(file),
            () -> trashNodes(file));

    List<Response> failed = responses.stream().filter(RaceTreeApiIT::hasError).toList();
    Assertions.assertThat(failed).hasSize(1);
    Assertions.assertThat(TestUtils.jsonResponseToErrorCodes(failed.get(0).getBody().asString()))
        .containsExactly("NODE_WRITE_ERROR");
    Assertions.assertThat(failed.get(0).getBody().asString()).doesNotContain("System error");
    Assertions.assertThat(isTrashed(file)).isTrue();
  }

  private static final int CHUNK = 32 * 1024;
  private static final String NODE_ROW_SHARE = "SELECT 1 FROM node WHERE node_id = ? FOR SHARE";

  private static Socket startHeldRawUpload(String path, String headers) throws Exception {
    var storages = FilesStackTestResource.getStoragesService();
    storages.holdUploads();
    try {
      Socket socket = startRawUpload(path, headers, OWNER_COOKIE, CHUNK);
      socket.getOutputStream().write(new byte[CHUNK]);
      socket.getOutputStream().flush();
      storages.awaitHeldUpload();
      return socket;
    } catch (Exception | Error e) {
      storages.releaseUploads();
      throw e;
    }
  }

  private static void assertNoUncoveredBlob() throws SQLException {
    for (String nodeId : FilesStackTestResource.getStoragesService().blobNodeIds()) {
      Assertions.assertThat(
              rowExists("SELECT 1 FROM node WHERE node_id = ?", nodeId)
                  || tombstoneRowsForNode(nodeId) > 0
                  || !pendingReservations(nodeId).isEmpty())
          .as("blob of node %s has no node, tombstone or pending reservation", nodeId)
          .isTrue();
    }
  }

  @Test
  void givenAnUploadPublishingWhileTheFolderIsDeletedThenNoBlobIsLeaked() throws Exception {
    String folder = seedFolder("race-tree-del-upload", LOCAL_ROOT, OWNER_COOKIE);
    seedFile("a.bin", folder, new byte[] {1}, OWNER_COOKIE);
    var storages = FilesStackTestResource.getStoragesService();

    List<CompletableFuture<Response>> pending = new ArrayList<>();
    String status;
    try (Socket upload =
        startHeldRawUpload(
            "/upload", "ParentId: " + folder + "\r\n" + filenameHeader("late-upload.bin"))) {
      try (Connection holder = holdRow(NODE_ROW_SHARE, folder)) {
        try {
          storages.releaseUploads();
          awaitBlockedBy(holder, 1);
          CompletableFuture<Response> delete =
              CompletableFuture.supplyAsync(() -> deleteNodes(folder), RACE_EXECUTOR);
          pending.add(delete);
          awaitBlockedOrDone(holder, 2, delete);
        } finally {
          holder.rollback();
        }
      }
      status = readStatusLine(upload);
      join(pending, new ArrayList<>());
    } finally {
      storages.releaseUploads();
      awaitAll(pending);
    }

    Assertions.assertThat(status).matches("HTTP/1.1 (200|404).*");
    Assertions.assertThat(childCount(folder)).isZero();
    assertNoUncoveredBlob();
  }

  @Test
  void givenAVersionConfirmedWhileTheFolderIsDeletedThenItsBlobIsNotLeaked() throws Exception {
    String folder = seedFolder("race-tree-del-version", LOCAL_ROOT, OWNER_COOKIE);
    String file = seedFile("v.bin", folder, new byte[] {1}, OWNER_COOKIE);
    var storages = FilesStackTestResource.getStoragesService();

    List<CompletableFuture<Response>> pending = new ArrayList<>();
    try (Socket upload =
        startHeldRawUpload(
            "/upload-version",
            "NodeId: " + file + "\r\nOverwriteVersion: false\r\n" + filenameHeader("v.bin"))) {
      try (Connection holder = holdRow(NODE_ROW_SHARE, file)) {
        try {
          storages.releaseUploads();
          awaitBlockedBy(holder, 1);
          CompletableFuture<Response> delete =
              CompletableFuture.supplyAsync(() -> deleteNodes(folder), RACE_EXECUTOR);
          pending.add(delete);
          awaitBlockedOrDone(holder, 2, delete);
        } finally {
          holder.rollback();
        }
      }
      readStatusLine(upload);
      join(pending, new ArrayList<>());
    } finally {
      storages.releaseUploads();
      awaitAll(pending);
    }

    Assertions.assertThat(rowExists("SELECT 1 FROM node WHERE node_id = ?", file)).isFalse();
    Assertions.assertThat(versionRows(file)).isEmpty();
    assertNoUncoveredBlob();
    Assertions.assertThat(
            !storages.has(file, 2)
                || tombstoneRowsForNode(file) > 0
                || pendingReservations(file).contains(2))
        .isTrue();
  }

  @Test
  void givenAFolderCreatedWhileItsParentIsDeletedThenNoOrphanRemains() throws Exception {
    String folder = seedFolder("race-tree-del-create", LOCAL_ROOT, OWNER_COOKIE);

    List<CompletableFuture<Response>> pending = new ArrayList<>();
    try (Connection holder = holdRow(NODE_ROW_SHARE, folder)) {
      try {
        pending.add(
            CompletableFuture.supplyAsync(() -> createFolder(folder, "late"), RACE_EXECUTOR));
        pending.add(CompletableFuture.supplyAsync(() -> deleteNodes(folder), RACE_EXECUTOR));
        awaitBlockedOrDone(holder, 2, pending.toArray(new CompletableFuture<?>[0]));
      } finally {
        holder.rollback();
      }
      join(pending, new ArrayList<>());
    } finally {
      awaitAll(pending);
    }

    if (!rowExists("SELECT 1 FROM node WHERE node_id = ?", folder)) {
      Assertions.assertThat(count("SELECT count(*) FROM node n WHERE n.folder_id = ?", folder))
          .isZero();
    }
  }
}
