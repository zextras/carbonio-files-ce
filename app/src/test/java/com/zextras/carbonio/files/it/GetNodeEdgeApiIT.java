// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code com.zextras.carbonio.files.acceptance.GetNodeEdgeApiIT} rewritten as an out-of-process
 * {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}. All 6 methods and their assertions
 * are preserved verbatim; only the seeding mechanism (API calls capturing server-generated ids,
 * except one JDBC-only pre-state — see below) and transport changed.
 *
 * <p><b>JDBC-seeded pre-state (D1 rule 4 escape hatch):</b> {@code
 * givenAReadableChildWithAnUnreadableParentTheParentFieldIsSilentlyNull} needs a child whose OWNER
 * differs from its structural parent's owner. The real {@code createFolder}/{@code upload}
 * mutations always inherit the parent's owner (see {@code CreateFolderApiIT}'s finding), so this
 * owner/parent-owner mismatch is NOT producible through the public API at all — it is seeded via
 * {@link AbstractFilesIT#seedInconsistentNode}, the ONLY method in this class that does not go
 * through the {@code seedXxx} API helpers.
 */
class GetNodeEdgeApiIT extends AbstractFilesIT {

  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OTHER_USER_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String REQUESTER_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String OTHER_COOKIE = "ZM_AUTH_TOKEN=fake-token-b";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", REQUESTER_ID);
    FilesStackTestResource.getUserManagementService().registerToken("fake-token-b", OTHER_USER_ID);
  }

  private Response getNode(String nodeId, Integer version, String resultFormat, String cookie) {
    GraphqlCommandBuilder builder =
        GraphqlCommandBuilder.aQueryBuilder("getNode").withString("node_id", nodeId);
    if (version != null) {
      builder.withInteger("version", version);
    }
    String bodyPayload = builder.withWantedResultFormat(resultFormat).build();
    return graphql(bodyPayload, cookie);
  }

  @Test
  void givenAReadableChildWithAnUnreadableParentTheParentFieldIsSilentlyNull()
      throws java.sql.SQLException {
    // Given — folder P owned by OTHER_USER_ID, never shared with the requester (unreadable to
    // them); child C is owned by the REQUESTER (so the requester CAN read C directly) but is
    // structurally parented under P. This owner/parent-owner mismatch is not producible through
    // the real createFolder/upload mutations (which always inherit the parent's owner), so both
    // rows are seeded via the raw-JDBC escape hatch, isolating the "parent unreadable" branch from
    // the "child unreadable" branch (which would fail before ever reaching `parent`).
    String parentId = "10000000-0000-0000-0000-000000000001";
    String childId = "00000000-0000-0000-0000-000000000001";
    seedInconsistentNode(
        parentId,
        OTHER_USER_ID,
        OTHER_USER_ID,
        "LOCAL_ROOT",
        "privateParent",
        NodeType.FOLDER,
        "LOCAL_ROOT",
        0L,
        null);
    seedInconsistentNode(
        childId,
        REQUESTER_ID,
        REQUESTER_ID,
        parentId,
        "child.txt",
        NodeType.TEXT,
        "LOCAL_ROOT," + parentId,
        1L,
        "text/plain");

    // When
    Response response = getNode(childId, null, "{ id parent { id } }", REQUESTER_COOKIE);

    // Then — getNode itself succeeds (the requester owns the child), but parent is silently null
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(response.getBody().asString())).isEmpty();
    Map<String, Object> node =
        TestUtils.jsonResponseToMap(response.getBody().asString(), "getNode");
    Assertions.assertThat(node).containsEntry("id", childId);
    Assertions.assertThat(node.get("parent")).isNull();
  }

  @Test
  void givenAReadableParentTheParentFieldResolvesNormally() {
    // Given — contrast case: same shape, but the parent IS owned by the requester, so it IS
    // readable and the field resolves to real data instead of silently going null.
    String parentId = seedFolder("myParent", LOCAL_ROOT, REQUESTER_COOKIE);
    String childId =
        seedFile("child2.txt", parentId, "c2".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);

    // When
    Response response = getNode(childId, null, "{ id parent { id } }", REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Map<String, Object> node =
        TestUtils.jsonResponseToMap(response.getBody().asString(), "getNode");
    Assertions.assertThat((Map<String, Object>) node.get("parent")).containsEntry("id", parentId);
  }

  @Test
  void givenANonExistentNodeDirectGetNodeShouldReturnNodeNotFoundError() {
    // Given
    String nonExistentId = "00000000-0000-0000-0000-00000000ffff";

    // When
    Response response = getNode(nonExistentId, null, "{ id }", REQUESTER_COOKIE);

    // Then — unlike the `parent` field, direct getNode surfaces a real error
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("Could not find node with id " + nonExistentId);
    Map<String, Object> node =
        TestUtils.jsonResponseToMap(response.getBody().asString(), "getNode");
    Assertions.assertThat(node).isEmpty();
  }

  @Test
  void givenNoPermissionOnAnExistingNodeDirectGetNodeShouldReturnNodeNotFoundError() {
    // Given — exists, owned by someone else, never shared
    String nodeId =
        seedFile(
            "notMine.txt", LOCAL_ROOT, "notmine".getBytes(StandardCharsets.UTF_8), OTHER_COOKIE);

    // When
    Response response = getNode(nodeId, null, "{ id }", REQUESTER_COOKIE);

    // Then — same nodeNotFound-shaped error as the not-found case; the gate cannot distinguish them
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("Could not find node with id " + nodeId);
  }

  /**
   * Closes {@code GenericControllerEvaluator#validateNodeId}'s missing branch: {@code
   * RootId.TRASH_ROOT} is a special-cased valid id (alongside {@code LOCAL_ROOT}, already exercised
   * elsewhere in the suite e.g. via {@code createFolder(parent_id: "LOCAL_ROOT")}), but no test
   * ever passed {@code "TRASH_ROOT"} itself as a {@code getNode} argument, so that specific {@code
   * || nodeId.equals(RootId.TRASH_ROOT)} branch direction was never taken.
   */
  @Test
  void givenTrashRootAsNodeIdDirectGetNodeShouldPassValidationAndResolve() {
    // When
    Response response = getNode("TRASH_ROOT", null, "{ id }", REQUESTER_COOKIE);

    // Then — no "Invalid node ID" validation error; TRASH_ROOT resolves like any other root id.
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(response.getBody().asString())).isEmpty();
    Map<String, Object> node =
        TestUtils.jsonResponseToMap(response.getBody().asString(), "getNode");
    Assertions.assertThat(node).containsEntry("id", "TRASH_ROOT");
  }

  @Test
  void givenARequestedVersionThatDoesNotExistGetNodeReturnsBaseDataPlusAnError() {
    // Given — a file that only has version 1
    String nodeId =
        seedFile(
            "versioned.txt", LOCAL_ROOT, "v1".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);

    // When — request a version that was never created
    Response response = getNode(nodeId, 999, "{ id name type }", REQUESTER_COOKIE);

    // Then — base node data IS present...
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Map<String, Object> node =
        TestUtils.jsonResponseToMap(response.getBody().asString(), "getNode");
    Assertions.assertThat(node)
        .containsEntry("id", nodeId)
        .containsEntry("name", "versioned")
        .containsEntry("type", NodeType.TEXT.toString());

    // ...alongside (not instead of) the version-not-found error
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("Could not find version: 999 for node with id " + nodeId);
  }
}
