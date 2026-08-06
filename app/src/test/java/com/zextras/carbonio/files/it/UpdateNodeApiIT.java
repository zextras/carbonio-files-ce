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
import java.util.List;
import java.util.Map;
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
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("Could not find node with id " + nodeId);
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
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly(
            "Trying to create a duplicate for the node "
                + nodeId
                + " in destination folder LOCAL_ROOT");

    // the node was NOT renamed
    Assertions.assertThat(nodeExists(nodeId, REQUESTER_COOKIE)).isTrue();
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
}
