// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
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

class MoveTrashConflictApiIT extends AbstractFilesIT {

  private static final String USER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String TRASH_ROOT = "TRASH_ROOT";
  private static final String NODE_WRITE_ERROR = "NODE_WRITE_ERROR";

  private record Row(String parentId, String ancestors, boolean trashed) {}

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", USER_ID);
  }

  private static String file(String name, String parentId) {
    return seedFile(name, parentId, "content".getBytes(StandardCharsets.UTF_8), COOKIE);
  }

  private static Row row(String nodeId) throws SQLException {
    try (Connection connection = jdbcConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT n.folder_id, n.ancestor_ids, t.node_id IS NOT NULL FROM node n"
                    + " LEFT JOIN trashed t ON t.node_id = n.node_id WHERE n.node_id = ?")) {
      statement.setString(1, nodeId);
      try (ResultSet rows = statement.executeQuery()) {
        Assertions.assertThat(rows.next()).isTrue();
        return new Row(rows.getString(1).trim(), rows.getString(2), rows.getBoolean(3));
      }
    }
  }

  private static List<String> ghosts() throws SQLException {
    try (Connection connection = jdbcConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT RTRIM(n.node_id) FROM node n JOIN trashed t ON t.node_id = n.node_id"
                    + " WHERE n.ancestor_ids NOT LIKE 'TRASH_ROOT%'")) {
      List<String> ids = new ArrayList<>();
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          ids.add(rows.getString(1));
        }
      }
      return ids;
    }
  }

  private static void update(String sql, Object... params) throws SQLException {
    try (Connection connection = jdbcConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) {
        statement.setObject(i + 1, params[i]);
      }
      statement.executeUpdate();
    }
  }

  private static Response moveResponse(String destination, String... ids) {
    return graphql(
        GraphqlCommandBuilder.aMutationBuilder("moveNodes")
            .withListOfStrings("node_ids", ids)
            .withString("destination_id", destination)
            .withWantedResultFormat("{ id }")
            .build(),
        COOKIE);
  }

  private static Response trashResponse(String... ids) {
    return graphql(
        GraphqlCommandBuilder.aMutationBuilder("trashNodes")
            .withListOfStrings("node_ids", ids)
            .withWantedResultFormat("")
            .build(),
        COOKIE);
  }

  private static List<String> errorCodes(Response response) {
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    return TestUtils.jsonResponseToErrorCodes(response.getBody().asString());
  }

  private static void assertLostRace(Response response, String operation, String nodeId) {
    String body = response.getBody().asString();
    Assertions.assertThat(errorCodes(response)).containsExactly(NODE_WRITE_ERROR);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(body))
        .singleElement()
        .asString()
        .contains(nodeId);
    Assertions.assertThat(
            (List<?>) TestUtils.jsonResponseToValue(body, operation).orElse(List.of()))
        .isEmpty();
  }

  private static void hold(Connection holder, String nodeId) throws SQLException {
    holder.setAutoCommit(false);
    try (PreparedStatement statement =
        holder.prepareStatement("SELECT 1 FROM node WHERE node_id = ? FOR UPDATE")) {
      statement.setString(1, nodeId);
      statement.executeQuery().close();
    }
  }

  @Test
  void givenATrashedNodeMoveNodesShouldRefuseItAndKeepItInTheTrash() throws SQLException {
    String destination = seedFolder("destination", LOCAL_ROOT, COOKIE);
    String nodeId = file("trashed.txt", LOCAL_ROOT);
    Assertions.assertThat(errorCodes(trashResponse(nodeId))).isEmpty();

    List<String> codes = errorCodes(moveResponse(destination, nodeId));

    Assertions.assertThat(codes).containsExactly(NODE_WRITE_ERROR);
    Assertions.assertThat(row(nodeId)).isEqualTo(new Row(TRASH_ROOT, TRASH_ROOT, true));
  }

  @Test
  void givenANodeInATrashedFolderMoveNodesShouldRefuseItAndKeepItInTheTrash() throws SQLException {
    String destination = seedFolder("destination", LOCAL_ROOT, COOKIE);
    String folder = seedFolder("folder", LOCAL_ROOT, COOKIE);
    String nodeId = file("inside.txt", folder);
    Assertions.assertThat(errorCodes(trashResponse(folder))).isEmpty();
    Row before = row(nodeId);
    Assertions.assertThat(before.ancestors()).startsWith(TRASH_ROOT);

    List<String> codes = errorCodes(moveResponse(destination, nodeId));

    Assertions.assertThat(codes).containsExactly(NODE_WRITE_ERROR);
    Assertions.assertThat(row(nodeId)).isEqualTo(before);
  }

  @Test
  void givenAMoveAndATrashOfTheSameNodeExactlyOneShouldSucceedAndNoGhostRemains() throws Exception {
    String destination = seedFolder("destination", LOCAL_ROOT, COOKIE);
    String nodeId = file("contended.txt", LOCAL_ROOT);

    Response move;
    Response trash;
    try (Connection holder = jdbcConnection()) {
      hold(holder, nodeId);
      CompletableFuture<Response> trashing =
          CompletableFuture.supplyAsync(() -> trashResponse(nodeId));
      CompletableFuture<Response> moving =
          CompletableFuture.supplyAsync(() -> moveResponse(destination, nodeId));
      awaitBlockedBy(holder, 2);
      holder.commit();
      trash = trashing.get(60, TimeUnit.SECONDS);
      move = moving.get(60, TimeUnit.SECONDS);
    }

    List<String> trashCodes = errorCodes(trash);
    List<String> moveCodes = errorCodes(move);
    Assertions.assertThat(trashCodes.size() + moveCodes.size()).isEqualTo(1);
    Assertions.assertThat(ghosts()).isEmpty();
    Row after = row(nodeId);
    if (trashCodes.isEmpty()) {
      assertLostRace(move, "moveNodes", nodeId);
      Assertions.assertThat(after).isEqualTo(new Row(TRASH_ROOT, TRASH_ROOT, true));
    } else {
      assertLostRace(trash, "trashNodes", nodeId);
      Assertions.assertThat(after)
          .isEqualTo(new Row(destination, "LOCAL_ROOT," + destination, false));
    }
  }

  @Test
  void givenTwoMovesOfTheSameNodeExactlyOneShouldSucceedAndTheNodeIsInOneFolder() throws Exception {
    String first = seedFolder("first", LOCAL_ROOT, COOKIE);
    String second = seedFolder("second", LOCAL_ROOT, COOKIE);
    String nodeId = file("contended.txt", LOCAL_ROOT);

    Response toFirst;
    Response toSecond;
    try (Connection holder = jdbcConnection()) {
      hold(holder, nodeId);
      CompletableFuture<Response> movingFirst =
          CompletableFuture.supplyAsync(() -> moveResponse(first, nodeId));
      CompletableFuture<Response> movingSecond =
          CompletableFuture.supplyAsync(() -> moveResponse(second, nodeId));
      awaitBlockedBy(holder, 2);
      holder.commit();
      toFirst = movingFirst.get(60, TimeUnit.SECONDS);
      toSecond = movingSecond.get(60, TimeUnit.SECONDS);
    }

    List<String> firstCodes = errorCodes(toFirst);
    List<String> secondCodes = errorCodes(toSecond);
    Assertions.assertThat(firstCodes.size() + secondCodes.size()).isEqualTo(1);
    Row after = row(nodeId);
    String winner = firstCodes.isEmpty() ? first : second;
    assertLostRace(firstCodes.isEmpty() ? toSecond : toFirst, "moveNodes", nodeId);
    Assertions.assertThat(after).isEqualTo(new Row(winner, "LOCAL_ROOT," + winner, false));
  }

  @Test
  void givenAConcurrentRenameMoveNodesShouldKeepTheNewName() throws Exception {
    String destination = seedFolder("destination", LOCAL_ROOT, COOKIE);
    String nodeId = file("original.txt", LOCAL_ROOT);

    Response move;
    try (Connection holder = jdbcConnection()) {
      holder.setAutoCommit(false);
      try (PreparedStatement statement =
          holder.prepareStatement("UPDATE node SET name = 'renamed.txt' WHERE node_id = ?")) {
        statement.setString(1, nodeId);
        statement.executeUpdate();
      }
      CompletableFuture<Response> moving =
          CompletableFuture.supplyAsync(() -> moveResponse(destination, nodeId));
      awaitBlockedBy(holder);
      holder.commit();
      move = moving.get(60, TimeUnit.SECONDS);
    }

    Assertions.assertThat(errorCodes(move)).isEmpty();
    Assertions.assertThat(row(nodeId))
        .isEqualTo(new Row(destination, "LOCAL_ROOT," + destination, false));
    try (Connection connection = jdbcConnection();
        PreparedStatement statement =
            connection.prepareStatement("SELECT name FROM node WHERE node_id = ?")) {
      statement.setString(1, nodeId);
      try (ResultSet rows = statement.executeQuery()) {
        Assertions.assertThat(rows.next()).isTrue();
        Assertions.assertThat(rows.getString(1)).isEqualTo("renamed.txt");
      }
    }
  }

  @Test
  void givenANodeWithAStaleTrashedRowAndALivePathMoveNodesShouldMoveIt() throws SQLException {
    String destination = seedFolder("destination", LOCAL_ROOT, COOKIE);
    String nodeId = file("ghost.txt", LOCAL_ROOT);
    update("INSERT INTO trashed (node_id, parent_id) VALUES (?, ?)", nodeId, LOCAL_ROOT);

    Assertions.assertThat(errorCodes(moveResponse(destination, nodeId))).isEmpty();

    Assertions.assertThat(row(nodeId))
        .isEqualTo(new Row(destination, "LOCAL_ROOT," + destination, true));
  }

  @Test
  void givenAConcurrentMoveOutOfATrashedFolderTrashNodesShouldNotRewriteTheMovedSubtree()
      throws Exception {
    String destination = seedFolder("destination", LOCAL_ROOT, COOKIE);
    String trashed = seedFolder("trashed", LOCAL_ROOT, COOKIE);
    String moved = seedFolder("moved", trashed, COOKIE);
    String movedChild = file("movedChild.txt", moved);
    String sibling = file("sibling.txt", trashed);
    String destinationPath = "LOCAL_ROOT," + destination;

    Response trash;
    try (Connection holder = jdbcConnection()) {
      holder.setAutoCommit(false);
      try (PreparedStatement statement =
          holder.prepareStatement(
              "UPDATE node SET folder_id = ?, ancestor_ids = ? WHERE node_id = ?")) {
        statement.setString(1, destination);
        statement.setString(2, destinationPath);
        statement.setString(3, moved);
        statement.executeUpdate();
      }
      try (PreparedStatement statement =
          holder.prepareStatement("UPDATE node SET ancestor_ids = ? WHERE node_id = ?")) {
        statement.setString(1, destinationPath + "," + moved);
        statement.setString(2, movedChild);
        statement.executeUpdate();
      }
      CompletableFuture<Response> trashing =
          CompletableFuture.supplyAsync(() -> trashResponse(trashed));
      awaitBlockedBy(holder);
      holder.commit();
      trash = trashing.get(60, TimeUnit.SECONDS);
    }

    Assertions.assertThat(errorCodes(trash)).isEmpty();
    Assertions.assertThat(row(trashed)).isEqualTo(new Row(TRASH_ROOT, TRASH_ROOT, true));
    Assertions.assertThat(row(sibling).ancestors()).isEqualTo(TRASH_ROOT + "," + trashed);
    Assertions.assertThat(row(moved)).isEqualTo(new Row(destination, destinationPath, false));
    Assertions.assertThat(row(movedChild).ancestors()).isEqualTo(destinationPath + "," + moved);
  }

  private static Response deleteResponse(String... ids) {
    return graphql(
        GraphqlCommandBuilder.aMutationBuilder("deleteNodes")
            .withListOfStrings("node_ids", ids)
            .withWantedResultFormat("")
            .build(),
        COOKIE);
  }

  private static boolean exists(String nodeId) throws SQLException {
    try (Connection connection = jdbcConnection();
        PreparedStatement statement =
            connection.prepareStatement("SELECT 1 FROM node WHERE node_id = ?")) {
      statement.setString(1, nodeId);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next();
      }
    }
  }

  private static Response deleteWhileMoveCommits(String[][] moves, String deletedId)
      throws Exception {
    try (Connection holder = jdbcConnection()) {
      holder.setAutoCommit(false);
      try (PreparedStatement statement =
          holder.prepareStatement(
              "UPDATE node SET folder_id = ?, ancestor_ids = ? WHERE node_id = ?")) {
        for (String[] move : moves) {
          statement.setString(1, move[1]);
          statement.setString(2, move[2]);
          statement.setString(3, move[0]);
          statement.executeUpdate();
        }
      }
      CompletableFuture<Response> deleting =
          CompletableFuture.supplyAsync(() -> deleteResponse(deletedId));
      awaitBlockedBy(holder);
      holder.commit();
      return deleting.get(60, TimeUnit.SECONDS);
    }
  }

  @Test
  void givenAMoveOutCommittedWhileTheOldParentIsDeletedDeleteNodesShouldKeepTheMovedSubtree()
      throws Exception {
    String destination = seedFolder("destination", LOCAL_ROOT, COOKIE);
    String deleted = seedFolder("deleted", LOCAL_ROOT, COOKIE);
    String moved = seedFolder("moved", deleted, COOKIE);
    String movedChild = file("movedChild.txt", moved);
    String sibling = file("sibling.txt", deleted);
    String destinationPath = "LOCAL_ROOT," + destination;

    Response delete =
        deleteWhileMoveCommits(
            new String[][] {
              {moved, destination, destinationPath},
              {movedChild, moved, destinationPath + "," + moved}
            },
            deleted);

    Assertions.assertThat(errorCodes(delete)).isEmpty();
    Assertions.assertThat(exists(deleted)).isFalse();
    Assertions.assertThat(exists(sibling)).isFalse();
    Assertions.assertThat(row(moved)).isEqualTo(new Row(destination, destinationPath, false));
    Assertions.assertThat(row(movedChild).ancestors()).isEqualTo(destinationPath + "," + moved);
    Assertions.assertThat(tombstoneRowsForNode(movedChild)).isZero();
    Assertions.assertThat(download(movedChild, COOKIE).getStatusCode()).isEqualTo(200);
  }

  @Test
  void givenAMoveInsideTheSubtreeCommittedWhileItIsDeletedDeleteNodesShouldDeleteTheMovedNode()
      throws Exception {
    String deleted = seedFolder("deleted", LOCAL_ROOT, COOKIE);
    String from = seedFolder("from", deleted, COOKIE);
    String to = seedFolder("to", deleted, COOKIE);
    String moved = file("moved.txt", from);

    Response delete =
        deleteWhileMoveCommits(
            new String[][] {{moved, to, "LOCAL_ROOT," + deleted + "," + to}}, deleted);

    Assertions.assertThat(errorCodes(delete)).isEmpty();
    Assertions.assertThat(exists(deleted)).isFalse();
    Assertions.assertThat(exists(to)).isFalse();
    Assertions.assertThat(exists(moved)).isFalse();
    Assertions.assertThat(download(moved, COOKIE).getStatusCode()).isEqualTo(404);
  }
}
