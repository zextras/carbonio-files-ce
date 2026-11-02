// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

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
import java.util.function.Supplier;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RaceShareApiIT extends AbstractFilesIT {

  private static final String OWNER_ID = "52111111-1111-1111-1111-111111111111";
  private static final String OWNER_COOKIE = "ZM_AUTH_TOKEN=race-share-owner";
  private static final String USER_ID = "52222222-2222-2222-2222-222222222222";
  private static final String USER_COOKIE = "ZM_AUTH_TOKEN=race-share-user";

  private static final String SHARE_ROW_LOCK =
      "SELECT 1 FROM share WHERE node_id = ? AND target_uuid = ? FOR UPDATE";
  private static final String NODE_ROW_LOCK = "SELECT 1 FROM node WHERE node_id = ? FOR UPDATE";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("race-share-owner", OWNER_ID);
    FilesStackTestResource.getUserManagementService().registerToken("race-share-user", USER_ID);
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

  private static Response createShare(String nodeId, SharePermission permission) {
    return graphql(
        GraphqlCommandBuilder.aMutationBuilder("createShare")
            .withString("node_id", nodeId)
            .withString("share_target_id", USER_ID)
            .withEnum("permission", permission)
            .withWantedResultFormat("{ created_at }")
            .build(),
        OWNER_COOKIE);
  }

  private static Response deleteShares(String nodeId) {
    return graphql(
        GraphqlCommandBuilder.aMutationBuilder("deleteShares")
            .withString("node_id", nodeId)
            .withListOfStrings("share_target_ids", new String[] {USER_ID})
            .withWantedResultFormat("")
            .build(),
        OWNER_COOKIE);
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

  private static boolean shareRow(String nodeId) throws SQLException {
    return rowExists("SELECT 1 FROM share WHERE node_id = ? AND target_uuid = ?", nodeId, USER_ID);
  }

  private static String shareColumn(String nodeId, String column) throws SQLException {
    try (Connection connection = jdbcConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT " + column + "::text FROM share WHERE node_id = ? AND target_uuid = ?")) {
      statement.setString(1, nodeId);
      statement.setString(2, USER_ID);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? rows.getString(1) : null;
      }
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

  private static List<Response> concurrentUnderLock(
      String sql, List<String> params, Supplier<Response> first, Supplier<Response> second)
      throws Exception {
    List<CompletableFuture<Response>> pending = new ArrayList<>();
    try (Connection holder = holdRow(sql, params.toArray(new String[0]))) {
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

  private static String childIdOf(Response createFolderResponse) {
    return createFolderResponse.jsonPath().getString("data.createFolder.id");
  }

  @Test
  void givenARevokeRacingAChildCreationThenTheRevokedUserHasNoShareOnTheChild() throws Exception {
    String folder = seedFolder("race-share-revoke", LOCAL_ROOT, OWNER_COOKIE);
    seedShare(folder, USER_ID, SharePermission.READ_ONLY, OWNER_COOKIE);

    List<Response> responses =
        concurrentUnderLock(
            SHARE_ROW_LOCK,
            List.of(folder, USER_ID),
            () -> deleteShares(folder),
            () -> createFolder(folder, "child"));

    String child = childIdOf(responses.get(1));
    Assertions.assertThat(child).isNotNull();
    Assertions.assertThat(shareRow(folder)).isFalse();
    Assertions.assertThat(shareRow(child)).isFalse();
    Assertions.assertThat(nodeExists(child, USER_COOKIE)).isFalse();
  }

  @Test
  void givenAShareRacingAChildCreationThenTheChildInheritsTheShare() throws Exception {
    String folder = seedFolder("race-share-create", LOCAL_ROOT, OWNER_COOKIE);

    List<Response> responses =
        concurrentUnderLock(
            NODE_ROW_LOCK,
            List.of(folder),
            () -> createShare(folder, SharePermission.READ_AND_WRITE),
            () -> createFolder(folder, "child"));

    String child = childIdOf(responses.get(1));
    Assertions.assertThat(child).isNotNull();
    Assertions.assertThat(shareRow(child)).isTrue();
    Assertions.assertThat(shareColumn(child, "direct")).isEqualTo("false");
    Assertions.assertThat(shareColumn(child, "rights")).isEqualTo(shareColumn(folder, "rights"));
    Assertions.assertThat(nodeExists(child, USER_COOKIE)).isTrue();
  }

  @Test
  void givenAShareCascadeRacingAFileMovedOutThenTheMovedFileKeepsNoInheritedShare()
      throws Exception {
    String shared = seedFolder("race-share-move-src", LOCAL_ROOT, OWNER_COOKIE);
    String file = seedFile("race-share-move.txt", shared, "x".getBytes(), OWNER_COOKIE);
    String privateFolder = seedFolder("race-share-move-private", LOCAL_ROOT, OWNER_COOKIE);

    concurrentUnderLock(
        NODE_ROW_LOCK,
        List.of(shared),
        () -> createShare(shared, SharePermission.READ_ONLY),
        () -> moveNodes(privateFolder, file));

    String folderOfFile = nodeColumnRaw(file, "folder_id");
    if (folderOfFile.trim().equals(privateFolder)) {
      Assertions.assertThat(shareRow(file)).isFalse();
    } else {
      Assertions.assertThat(folderOfFile.trim()).isEqualTo(shared);
      Assertions.assertThat(shareRow(file)).isTrue();
    }
  }

  @Test
  void givenAShareRacingADeepChildCreationThenTheDeepChildInheritsTheShare() throws Exception {
    String top = seedFolder("race-share-deep-s", LOCAL_ROOT, OWNER_COOKIE);
    String middle = seedFolder("race-share-deep-g", top, OWNER_COOKIE);
    String bottom = seedFolder("race-share-deep-h", middle, OWNER_COOKIE);

    List<Response> responses =
        concurrentUnderLock(
            NODE_ROW_LOCK,
            List.of(bottom),
            () -> createShare(top, SharePermission.READ_ONLY),
            () -> createFolder(bottom, "deep"));

    String deep = childIdOf(responses.get(1));
    Assertions.assertThat(deep).isNotNull();
    Assertions.assertThat(shareRow(deep)).isTrue();
    Assertions.assertThat(nodeExists(deep, USER_COOKIE)).isTrue();
  }

  @Test
  void givenADirectShareCreatedDuringAnInheritedCascadeThenItStaysDirect() throws Exception {
    String parent = seedFolder("race-share-direct-p", LOCAL_ROOT, OWNER_COOKIE);
    String child = seedFolder("race-share-direct-c", parent, OWNER_COOKIE);

    concurrentUnderLock(
        NODE_ROW_LOCK,
        List.of(child),
        () -> createShare(child, SharePermission.READ_AND_WRITE),
        () -> createShare(parent, SharePermission.READ_ONLY));

    Assertions.assertThat(shareColumn(child, "direct")).isEqualTo("true");
    Assertions.assertThat(shareColumn(child, "rights"))
        .isEqualTo(String.valueOf(SharePermission.READ_AND_WRITE.encode()));
  }

  @Test
  void givenAShareRevokedWhileATrashedChildIsMovedOutThenTheMovedChildKeepsItsNewShare()
      throws Exception {
    String parent = seedFolder("race-share-trash-p", LOCAL_ROOT, OWNER_COOKIE);
    seedShare(parent, USER_ID, SharePermission.READ_ONLY, OWNER_COOKIE);
    String trashed = seedFolder("race-share-trash-x", parent, OWNER_COOKIE);
    seedTrashed(trashed, OWNER_COOKIE);
    String destination = seedFolder("race-share-trash-d", LOCAL_ROOT, OWNER_COOKIE);
    seedShare(destination, USER_ID, SharePermission.READ_ONLY, OWNER_COOKIE);

    List<CompletableFuture<Response>> pending = new ArrayList<>();
    try (Connection holder = holdRow("SELECT 1 FROM node WHERE node_id = ? FOR SHARE", trashed)) {
      try {
        CompletableFuture<Response> move =
            CompletableFuture.supplyAsync(() -> moveNodes(destination, trashed), RACE_EXECUTOR);
        pending.add(move);
        awaitBlockedOrDone(holder, 1, move);
        CompletableFuture<Response> revoke =
            CompletableFuture.supplyAsync(() -> deleteShares(parent), RACE_EXECUTOR);
        pending.add(revoke);
        awaitBlockedOrDone(holder, 2, move, revoke);
      } finally {
        holder.rollback();
      }
      for (CompletableFuture<Response> future : pending) {
        future.get(30, TimeUnit.SECONDS);
      }
    } finally {
      awaitAll(pending);
    }

    if (nodeColumnRaw(trashed, "folder_id").trim().equals(destination)) {
      Assertions.assertThat(shareRow(trashed)).isTrue();
    }
  }
}
