// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@code com.zextras.carbonio.files.acceptance.MoveNodesApiIT} rewritten as an out-of-process
 * {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}. Both methods and their assertions are
 * preserved verbatim; only the seeding mechanism (API calls capturing server-generated ids) and
 * transport changed.
 */
class MoveNodesApiIT extends AbstractFilesIT {

  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String REQUESTER_COOKIE = "ZM_AUTH_TOKEN=fake-token";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", REQUESTER_ID);
  }

  private Response moveNodes(String[] nodeIds, String destinationId, String cookie) {
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("moveNodes")
            .withListOfStrings("node_ids", nodeIds)
            .withString("destination_id", destinationId)
            .withWantedResultFormat("{ id name }")
            .build();
    return graphql(bodyPayload, cookie);
  }

  @Test
  void
      givenANodeInRootAndANodeInAnotherFolderWithSameNameMovingThemInTheSameFolderShouldRenameTheMovedOne() {
    // Given — "name.txt" already sits at LOCAL_ROOT; a second "name.txt" sits inside "folder"
    seedFile("name.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    String folderId = seedFolder("folder", LOCAL_ROOT, REQUESTER_COOKIE);
    String nodeToMoveId =
        seedFile(
            "name.txt", folderId, "content".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);

    // When
    Response response = moveNodes(new String[] {nodeToMoveId}, LOCAL_ROOT, REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Map<String, Object> page =
        TestUtils.jsonResponseToMap(response.getBody().asString(), "moveNodes");
    List<Map<String, Object>> nodes = (List<Map<String, Object>>) page.get("data");

    Assertions.assertThat(nodes).hasSize(1);
    Assertions.assertThat(nodes.get(0))
        .containsEntry("id", nodeToMoveId)
        .containsEntry("name", "name (1)");
  }

  @Test
  void givenANodeInAFolderMovingItInTheSameFolderShouldNotRenameTheNode() {
    // Given
    String nodeId =
        seedFile(
            "second.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);

    // When
    Response response = moveNodes(new String[] {nodeId}, LOCAL_ROOT, REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Map<String, Object> page =
        TestUtils.jsonResponseToMap(response.getBody().asString(), "moveNodes");
    List<Map<String, Object>> nodes = (List<Map<String, Object>>) page.get("data");

    Assertions.assertThat(nodes).hasSize(1);
    Assertions.assertThat(nodes.get(0)).containsEntry("id", nodeId).containsEntry("name", "second");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "mutation { moveNodes(destination_id: \\\"LOCAL_ROOT\\\") { id } }",
        "mutation { moveNodes(destination_id: \\\"LOCAL_ROOT\\\", node_ids: null) { id } }"
      })
  void givenAMissingOrNullNodeIdsMoveNodesShouldFailWithValidationErrorAndTouchNothing(
      String query) {
    // Given
    String folderId = seedFolder("folder", LOCAL_ROOT, REQUESTER_COOKIE);
    String nodeId =
        seedFile(
            "untouched.txt",
            folderId,
            "content".getBytes(StandardCharsets.UTF_8),
            REQUESTER_COOKIE);

    // When
    Response response = graphql(query, REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(response.jsonPath().getString("errors[0].message"))
        .isEqualTo("Invalid node IDs. The list cannot be null.");
    Assertions.assertThat(response.jsonPath().getList("errors")).hasSize(1);
    Assertions.assertThat(response.jsonPath().getMap("data")).containsEntry("moveNodes", null);
    Assertions.assertThat(nodeExists(nodeId, REQUESTER_COOKIE)).isTrue();
    Response children =
        graphql(
            "query { findNodes(folder_id: \\\""
                + folderId
                + "\\\", limit: 10, sort: NAME_ASC)"
                + " { nodes { id } } }",
            REQUESTER_COOKIE);
    Assertions.assertThat(children.jsonPath().getList("data.findNodes.nodes.id"))
        .containsExactly(nodeId);
  }

  @Test
  void givenAnEmptyNodeIdsListMoveNodesShouldReturnAnEmptyList() {
    // When
    Response response =
        graphql(
            "mutation { moveNodes(destination_id: \\\"LOCAL_ROOT\\\", node_ids: []) { id } }",
            REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(response.getBody().asString()).isEqualTo("{\"data\":{\"moveNodes\":[]}}");
  }

  @Test
  void givenTheDestinationDeletedWhileTheMoveWaitsForTheNodeLockMoveNodesShouldReportAWriteError()
      throws Exception {
    // Given — the node sorts below the destination, so it is locked (and waited for) first
    String firstId = seedFolder("first", LOCAL_ROOT, REQUESTER_COOKIE);
    String secondId = seedFolder("second", LOCAL_ROOT, REQUESTER_COOKIE);
    String nodeId = firstId.compareTo(secondId) < 0 ? firstId : secondId;
    String destinationId = nodeId.equals(firstId) ? secondId : firstId;

    Response response;
    try (Connection holder = jdbcConnection()) {
      holder.setAutoCommit(false);
      try (PreparedStatement lock =
          holder.prepareStatement("SELECT node_id FROM node WHERE node_id = ? FOR UPDATE")) {
        lock.setString(1, nodeId);
        lock.execute();
      }

      // When
      CompletableFuture<Response> move =
          CompletableFuture.supplyAsync(
              () -> moveNodes(new String[] {nodeId}, destinationId, REQUESTER_COOKIE));
      awaitBlockedBy(holder);
      try (Connection deleter = jdbcConnection();
          PreparedStatement delete =
              deleter.prepareStatement("DELETE FROM node WHERE node_id = ?")) {
        delete.setString(1, destinationId);
        delete.executeUpdate();
      }
      holder.commit();
      response = move.get(60, TimeUnit.SECONDS);
    }

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToErrorCodes(response.getBody().asString()))
        .containsExactly("NODE_WRITE_ERROR");
    Assertions.assertThat(response.jsonPath().getString("errors[0].extensions.nodeId"))
        .isEqualTo(destinationId);
    try (Connection connection = jdbcConnection();
        PreparedStatement statement =
            connection.prepareStatement("SELECT folder_id FROM node WHERE node_id = ?")) {
      statement.setString(1, nodeId);
      try (ResultSet row = statement.executeQuery()) {
        Assertions.assertThat(row.next()).isTrue();
        Assertions.assertThat(row.getString(1).trim()).isEqualTo(LOCAL_ROOT);
      }
    }
  }

  @Test
  void givenANodeDeletedWhileTheMoveWaitsForItsLockMoveNodesShouldMoveTheOthersAndReportIt()
      throws Exception {
    // Given
    String destinationId = seedFolder("destination", LOCAL_ROOT, REQUESTER_COOKIE);
    String doomedId =
        seedFile(
            "doomed.txt", LOCAL_ROOT, "one".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    String otherId =
        seedFile("other.txt", LOCAL_ROOT, "two".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);

    Response response;
    try (Connection holder = jdbcConnection()) {
      holder.setAutoCommit(false);
      try (PreparedStatement lock =
          holder.prepareStatement("SELECT node_id FROM node WHERE node_id = ? FOR UPDATE")) {
        lock.setString(1, doomedId);
        lock.execute();
      }

      // When
      CompletableFuture<Response> move =
          CompletableFuture.supplyAsync(
              () -> moveNodes(new String[] {doomedId, otherId}, destinationId, REQUESTER_COOKIE));
      awaitBlockedBy(holder);
      try (PreparedStatement delete =
          holder.prepareStatement("DELETE FROM node WHERE node_id = ?")) {
        delete.setString(1, doomedId);
        delete.executeUpdate();
      }
      holder.commit();
      response = move.get(60, TimeUnit.SECONDS);
    }

    // Then
    Assertions.assertThat(TestUtils.jsonResponseToErrorCodes(response.getBody().asString()))
        .containsExactly("NODE_WRITE_ERROR");
    Assertions.assertThat(response.jsonPath().getString("errors[0].extensions.nodeId"))
        .isEqualTo(doomedId);
    Assertions.assertThat(response.jsonPath().getList("data.moveNodes.id"))
        .containsExactly(otherId);
  }
}
