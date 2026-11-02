// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.Constants.Config.Link;
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
        pending.add(CompletableFuture.supplyAsync(first));
        pending.add(CompletableFuture.supplyAsync(second));
        awaitBlockedBy(holder, 2);
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
        pending.add(CompletableFuture.supplyAsync(() -> createFolder(LOCAL_ROOT, name)));
        pending.add(CompletableFuture.supplyAsync(() -> createFolder(LOCAL_ROOT, name)));
        awaitBlockedBy(holder, 2);
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
    Assertions.assertThat(names).hasSize(2);
    Assertions.assertThat(names).doesNotHaveDuplicates();
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
}
