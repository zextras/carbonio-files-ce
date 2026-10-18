// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
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

/**
 * {@code com.zextras.carbonio.files.acceptance.UpdateNodeApiIT} rewritten as an out-of-process
 * {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}.
 *
 * <p>Covers: happy rename, permission-denied, the rename-collision -&gt; {@code duplicateNode}
 * branch (contrast with {@code createFolder}'s auto-rename-on-collision behaviour: {@code
 * updateNode} REJECTS a collision instead of auto-renaming), a description-only update (no rename
 * search performed at all since {@code name} is absent), and a flagged-only update. All 5 methods
 * and their assertions are preserved verbatim; only the seeding mechanism (API calls capturing
 * server-generated ids) and transport changed.
 */
class UpdateNodeApiIT extends AbstractFilesIT {

  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OTHER_USER_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String REQUESTER_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String OTHER_COOKIE = "ZM_AUTH_TOKEN=fake-token-b";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", REQUESTER_ID);
    FilesStackTestResource.getUserManagementService().registerToken("fake-token-b", OTHER_USER_ID);
  }

  private Response updateNode(
      String nodeId, String name, String description, Boolean flagged, String cookie) {
    GraphqlCommandBuilder builder =
        GraphqlCommandBuilder.aMutationBuilder("updateNode").withString("node_id", nodeId);
    if (name != null) {
      builder.withString("name", name);
    }
    if (description != null) {
      builder.withString("description", description);
    }
    if (flagged != null) {
      builder.withBoolean("flagged", flagged);
    }
    String bodyPayload = builder.withWantedResultFormat("{ id name description }").build();
    return graphql(bodyPayload, cookie);
  }

  @Test
  void givenWritePermissionUpdateNodeShouldRenameTheNode() {
    // Given
    String nodeId =
        seedFile(
            "old.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);

    // When
    Response response = updateNode(nodeId, "renamed", null, null, REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(response.getBody().asString())).isEmpty();
    Map<String, Object> node =
        TestUtils.jsonResponseToMap(response.getBody().asString(), "updateNode");
    Assertions.assertThat(node).containsEntry("name", "renamed");
  }

  @Test
  void givenNoWritePermissionUpdateNodeShouldReturnNodeNotFoundError() {
    // Given — owned by someone else, shared READ_ONLY (no write) with the requester
    String nodeId =
        seedFile(
            "notMine.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OTHER_COOKIE);
    seedShare(nodeId, REQUESTER_ID, SharePermission.READ_ONLY, OTHER_COOKIE);

    // When
    Response response = updateNode(nodeId, "renamed", null, null, REQUESTER_COOKIE);

    // Then — updateNodeFetcher's permission-denied branch returns nodeNotFound, not nodeWriteError
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errorCodes = TestUtils.jsonResponseToErrorCodes(response.getBody().asString());
    Assertions.assertThat(errorCodes).containsExactly("NODE_NOT_FOUND");
  }

  @Test
  void givenANameCollisionInTheSameParentUpdateNodeShouldReturnDuplicateNodeErrorNotAutoRename() {
    // Given — two files in LOCAL_ROOT; renaming the second to collide with the first's full name
    seedFile("taken.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    String nodeId =
        seedFile(
            "original.txt",
            LOCAL_ROOT,
            "content".getBytes(StandardCharsets.UTF_8),
            REQUESTER_COOKIE);

    // When — renaming to "taken" would produce the full name "taken.txt", already present
    Response response = updateNode(nodeId, "taken", null, null, REQUESTER_COOKIE);

    // Then — REJECTED with duplicateNode, unlike createFolder's silent " (1)" auto-rename
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errorCodes = TestUtils.jsonResponseToErrorCodes(response.getBody().asString());
    Assertions.assertThat(errorCodes).containsExactly("NODE_DUPLICATED");

    // the node was NOT renamed
    Assertions.assertThat(nodeExists(nodeId, REQUESTER_COOKIE)).isTrue();
  }

  @Test
  void givenAFolderRenamedByAnotherUserItsLastEditorShouldStayNull() {
    String folderId = seedFolder("shared-folder", LOCAL_ROOT, REQUESTER_COOKIE);
    seedShare(folderId, OTHER_USER_ID, SharePermission.READ_AND_WRITE, REQUESTER_COOKIE);

    Response rename = updateNode(folderId, "renamed-by-b", null, null, OTHER_COOKIE);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(rename.getBody().asString())).isEmpty();

    String query =
        GraphqlCommandBuilder.aQueryBuilder("getNode")
            .withString("node_id", folderId)
            .withWantedResultFormat("{ id name last_editor { id } }")
            .build();
    Response response = graphql(query, REQUESTER_COOKIE);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(response.getBody().asString())).isEmpty();
    Map<String, Object> node =
        TestUtils.jsonResponseToMap(response.getBody().asString(), "getNode");
    Assertions.assertThat(node)
        .containsEntry("name", "renamed-by-b")
        .containsEntry("last_editor", null);
  }

  @Test
  void givenOnlyADescriptionUpdateNodeShouldUpdateItWithoutTouchingTheName() {
    // Given — closes the `optName.isPresent()` FALSE branch: no rename search is performed at all
    String nodeId =
        seedFile(
            "untouched.txt",
            LOCAL_ROOT,
            "content".getBytes(StandardCharsets.UTF_8),
            REQUESTER_COOKIE);

    // When
    Response response = updateNode(nodeId, null, "a new description", null, REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Map<String, Object> node =
        TestUtils.jsonResponseToMap(response.getBody().asString(), "updateNode");
    Assertions.assertThat(node)
        .containsEntry("name", "untouched")
        .containsEntry("description", "a new description");
  }

  @Test
  void givenOnlyAFlaggedValueUpdateNodeShouldFlagTheNodeWithoutTouchingItsName() {
    // Given
    String nodeId =
        seedFile(
            "flagme.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);

    // When
    Response response = updateNode(nodeId, null, null, true, REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Map<String, Object> node =
        TestUtils.jsonResponseToMap(response.getBody().asString(), "updateNode");
    Assertions.assertThat(node).containsEntry("name", "flagme");
  }

  @Test
  void givenAMoveCommittedWhileARenameIsInFlightTheRenameShouldNotUndoTheMove() throws Exception {
    String sourceFolderId = seedFolder("race-source", LOCAL_ROOT, REQUESTER_COOKIE);
    String targetFolderId = seedFolder("race-target", LOCAL_ROOT, REQUESTER_COOKIE);
    String nodeId =
        seedFile(
            "racing.txt",
            sourceFolderId,
            "content".getBytes(StandardCharsets.UTF_8),
            REQUESTER_COOKIE);
    seedFlag(nodeId, REQUESTER_COOKIE);

    Response response;
    try (Connection move = jdbcConnection()) {
      move.setAutoCommit(false);
      // The flag row lock parks a pre-fix rename between its node read and its node write.
      try (PreparedStatement lockFlag =
              move.prepareStatement(
                  "SELECT star FROM custom WHERE node_id = ? AND user_id = ? FOR UPDATE");
          PreparedStatement moveNode =
              move.prepareStatement(
                  "UPDATE node SET folder_id = ?, ancestor_ids = ? WHERE node_id = ?")) {
        lockFlag.setString(1, nodeId);
        lockFlag.setString(2, REQUESTER_ID);
        try (ResultSet flag = lockFlag.executeQuery()) {
          Assertions.assertThat(flag.next()).isTrue();
        }
        moveNode.setString(1, targetFolderId);
        moveNode.setString(2, LOCAL_ROOT + "," + targetFolderId);
        moveNode.setString(3, nodeId);
        moveNode.executeUpdate();
      }
      CompletableFuture<Response> rename =
          CompletableFuture.supplyAsync(
              () -> updateNode(nodeId, "renamed", null, false, REQUESTER_COOKIE));
      awaitBlockedBy(move);
      move.commit();
      response = rename.get(60, TimeUnit.SECONDS);
    }

    Assertions.assertThat(TestUtils.jsonResponseToErrors(response.getBody().asString())).isEmpty();
    Assertions.assertThat(TestUtils.jsonResponseToMap(response.getBody().asString(), "updateNode"))
        .containsEntry("name", "renamed");
    try (Connection connection = jdbcConnection();
        PreparedStatement statement =
            connection.prepareStatement("SELECT folder_id, name FROM node WHERE node_id = ?")) {
      statement.setString(1, nodeId);
      try (ResultSet row = statement.executeQuery()) {
        Assertions.assertThat(row.next()).isTrue();
        Assertions.assertThat(row.getString("folder_id").trim()).isEqualTo(targetFolderId);
        Assertions.assertThat(row.getString("name")).isEqualTo("renamed.txt");
      }
    }
  }

  @Test
  void givenAMoveCommittedWhileARenameIsInFlightTheRenameShouldCheckTheNameInTheNewFolder()
      throws Exception {
    String sourceFolderId = seedFolder("race-source", LOCAL_ROOT, REQUESTER_COOKIE);
    String targetFolderId = seedFolder("race-target", LOCAL_ROOT, REQUESTER_COOKIE);
    String nodeId =
        seedFile(
            "racing.txt",
            sourceFolderId,
            "content".getBytes(StandardCharsets.UTF_8),
            REQUESTER_COOKIE);
    seedFile(
        "renamed.txt",
        targetFolderId,
        "content".getBytes(StandardCharsets.UTF_8),
        REQUESTER_COOKIE);
    seedFlag(nodeId, REQUESTER_COOKIE);

    Response response;
    try (Connection move = jdbcConnection()) {
      move.setAutoCommit(false);
      // The flag row lock parks a pre-fix rename between its node read and its node write.
      try (PreparedStatement lockFlag =
              move.prepareStatement(
                  "SELECT star FROM custom WHERE node_id = ? AND user_id = ? FOR UPDATE");
          PreparedStatement moveNode =
              move.prepareStatement(
                  "UPDATE node SET folder_id = ?, ancestor_ids = ? WHERE node_id = ?")) {
        lockFlag.setString(1, nodeId);
        lockFlag.setString(2, REQUESTER_ID);
        try (ResultSet flag = lockFlag.executeQuery()) {
          Assertions.assertThat(flag.next()).isTrue();
        }
        moveNode.setString(1, targetFolderId);
        moveNode.setString(2, LOCAL_ROOT + "," + targetFolderId);
        moveNode.setString(3, nodeId);
        moveNode.executeUpdate();
      }
      CompletableFuture<Response> rename =
          CompletableFuture.supplyAsync(
              () -> updateNode(nodeId, "renamed", null, false, REQUESTER_COOKIE));
      awaitBlockedBy(move);
      move.commit();
      response = rename.get(60, TimeUnit.SECONDS);
    }

    Assertions.assertThat(TestUtils.jsonResponseToErrorCodes(response.getBody().asString()))
        .containsExactly("NODE_DUPLICATED");
    try (Connection connection = jdbcConnection();
        PreparedStatement statement =
            connection.prepareStatement("SELECT folder_id, name FROM node WHERE node_id = ?")) {
      statement.setString(1, nodeId);
      try (ResultSet row = statement.executeQuery()) {
        Assertions.assertThat(row.next()).isTrue();
        Assertions.assertThat(row.getString("folder_id").trim()).isEqualTo(targetFolderId);
        Assertions.assertThat(row.getString("name")).isEqualTo("racing.txt");
      }
    }
  }
}
