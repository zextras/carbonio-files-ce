// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.acceptance;

import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.acceptance.seam.FilesTestApp;
import com.zextras.carbonio.files.acceptance.seam.impl.GuiceNettyFilesTestAppBuilder;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.api.utilities.entities.PopulatorNode;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.utilities.http.HttpRequest;
import com.zextras.carbonio.files.utilities.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Task 1.6 (part 2) of the acceptance coverage-expansion plan: edge branches of {@code
 * NodeDataFetcher#getNodeFetcher}, which is bound BOTH to the top-level {@code getNode} query AND
 * to the {@code parent} field on {@code File}/{@code Folder} (source: reading {@code
 * getNodeFetcher}'s body, which special-cases {@code environment.getField().getName().equals(
 * "parent")} into an {@code isParent} flag).
 *
 * <p>Both call sites share the SAME permission gate ({@code
 * permissionsChecker.getPermissions(nodeId, requesterId).has(READ_ONLY)}), but they react
 * DIFFERENTLY when it fails:
 *
 * <ul>
 *   <li>direct {@code getNode(node_id: ...)} (not-found OR permission-denied — the gate cannot
 *       distinguish the two, per the existing {@code NodePermissionsFieldApiIT} finding): returns
 *       a {@code nodeNotFound} GraphQL ERROR — {@code "Could not find node with id <id>"};
 *   <li>{@code parent} field resolution: returns an EMPTY {@code DataFetcherResult} (no data, NO
 *       error) — the field is simply {@code null}, completely silently.
 * </ul>
 *
 * <p>To observe the silent {@code parent: null} path for real (as opposed to the gate failing on
 * the child itself, which would fail the whole {@code getNode} call before {@code parent} is even
 * reached), the child node must be one the requester CAN read while its PARENT is one the
 * requester CANNOT — constructed here via the raw backdoor with an owner/parent-owner mismatch
 * that the real API's owner-inheritance-on-creation would never produce naturally (same
 * technique as {@code CreateFolderApiIT}'s and {@code GetPathApiIT}'s deliberately-inconsistent
 * fixtures).
 *
 * <p>Also covers the missing-version partial-result branch of {@code
 * convertNodeToDataFetcherResult}: when a requested {@code version} doesn't match any {@code
 * FileVersion} row, the base Node fields (id/name/type/...) are still populated in {@code data}
 * (the {@code DataFetcherResult.Builder} already carries {@code .data(result)} before the version
 * check ever runs) while a {@code fileVersionNotFound} error is ALSO attached to the SAME result —
 * both appear together in the response, not one instead of the other.
 */
class GetNodeEdgeApiIT {

  static FilesTestApp app;

  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OTHER_USER_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

  @BeforeAll
  static void init() {
    app =
        GuiceNettyFilesTestAppBuilder.aFilesTestApp()
            .withDatabase()
            .withServiceDiscover()
            .withUserManagement(
                Map.of(
                    "fake-token", REQUESTER_ID,
                    "fake-token-b", OTHER_USER_ID))
            .build();
  }

  @AfterEach
  void cleanUp() {
    app.backdoor().resetDatabase();
  }

  @AfterAll
  static void cleanUpAll() {
    app.close();
  }

  @SuppressWarnings("unchecked")
  private HttpResponse getNode(String nodeId, Integer version, String resultFormat, String cookie) {
    GraphqlCommandBuilder builder =
        GraphqlCommandBuilder.aQueryBuilder("getNode").withString("node_id", nodeId);
    if (version != null) {
      builder.withInteger("version", version);
    }
    String bodyPayload = builder.withWantedResultFormat(resultFormat).build();
    HttpRequest httpRequest = HttpRequest.of("POST", "/graphql/", cookie, bodyPayload);
    return app.send(httpRequest);
  }

  @Test
  void givenAReadableChildWithAnUnreadableParentTheParentFieldIsSilentlyNull() {
    // Given — folder P owned by OTHER_USER_ID, never shared with the requester (unreadable to
    // them); child C is owned by the REQUESTER (so the requester CAN read C directly) but is
    // structurally parented under P. This owner/parent-owner mismatch is not producible through
    // the real createFolder/upload mutations (which always inherit the parent's owner), but is a
    // valid raw DB state and is exactly what isolates the "parent unreadable" branch from the
    // "child unreadable" branch (which would fail before ever reaching `parent`).
    String parentId = "10000000-0000-0000-0000-000000000001";
    String childId = "00000000-0000-0000-0000-000000000001";
    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                parentId, OTHER_USER_ID, OTHER_USER_ID, "LOCAL_ROOT", "privateParent", "",
                NodeType.FOLDER, "LOCAL_ROOT", 0L, null))
        .addNode(
            new PopulatorNode(
                childId, REQUESTER_ID, REQUESTER_ID, parentId, "child.txt", "",
                NodeType.TEXT, "LOCAL_ROOT," + parentId, 1L, "text/plain"));

    // When
    HttpResponse httpResponse =
        getNode(childId, null, "{ id parent { id } }", "ZM_AUTH_TOKEN=fake-token");

    // Then — getNode itself succeeds (the requester owns the child), but parent is silently null
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload())).isEmpty();
    Map<String, Object> node = TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "getNode");
    Assertions.assertThat(node).containsEntry("id", childId);
    Assertions.assertThat(node.get("parent")).isNull();
  }

  @Test
  void givenAReadableParentTheParentFieldResolvesNormally() {
    // Given — contrast case: same shape, but the parent IS owned by the requester, so it IS
    // readable and the field resolves to real data instead of silently going null.
    String parentId = "10000000-0000-0000-0000-000000000002";
    String childId = "00000000-0000-0000-0000-000000000002";
    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                parentId, REQUESTER_ID, REQUESTER_ID, "LOCAL_ROOT", "myParent", "",
                NodeType.FOLDER, "LOCAL_ROOT", 0L, null))
        .addNode(
            new PopulatorNode(
                childId, REQUESTER_ID, REQUESTER_ID, parentId, "child2.txt", "",
                NodeType.TEXT, "LOCAL_ROOT," + parentId, 1L, "text/plain"));

    // When
    HttpResponse httpResponse =
        getNode(childId, null, "{ id parent { id } }", "ZM_AUTH_TOKEN=fake-token");

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Map<String, Object> node = TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "getNode");
    Assertions.assertThat((Map<String, Object>) node.get("parent")).containsEntry("id", parentId);
  }

  @Test
  void givenANonExistentNodeDirectGetNodeShouldReturnNodeNotFoundError() {
    // Given
    String nonExistentId = "00000000-0000-0000-0000-00000000ffff";

    // When
    HttpResponse httpResponse =
        getNode(nonExistentId, null, "{ id }", "ZM_AUTH_TOKEN=fake-token");

    // Then — unlike the `parent` field, direct getNode surfaces a real error
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("Could not find node with id " + nonExistentId);
    Map<String, Object> node = TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "getNode");
    Assertions.assertThat(node).isEmpty();
  }

  @Test
  void givenNoPermissionOnAnExistingNodeDirectGetNodeShouldReturnNodeNotFoundError() {
    // Given — exists, owned by someone else, never shared
    String nodeId = "00000000-0000-0000-0000-000000000003";
    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                nodeId, OTHER_USER_ID, OTHER_USER_ID, "LOCAL_ROOT", "notMine.txt", "",
                NodeType.TEXT, "LOCAL_ROOT", 1L, "text/plain"));

    // When
    HttpResponse httpResponse = getNode(nodeId, null, "{ id }", "ZM_AUTH_TOKEN=fake-token");

    // Then — same nodeNotFound-shaped error as the not-found case; the gate cannot distinguish them
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("Could not find node with id " + nodeId);
  }

  @Test
  void givenARequestedVersionThatDoesNotExistGetNodeReturnsBaseDataPlusAnError() {
    // Given — a file that only has version 1
    String nodeId = "00000000-0000-0000-0000-000000000004";
    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                nodeId, REQUESTER_ID, REQUESTER_ID, "LOCAL_ROOT", "versioned.txt", "",
                NodeType.TEXT, "LOCAL_ROOT", 1L, "text/plain"));

    // When — request a version that was never created
    HttpResponse httpResponse =
        getNode(nodeId, 999, "{ id name type }", "ZM_AUTH_TOKEN=fake-token");

    // Then — base node data IS present...
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Map<String, Object> node = TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "getNode");
    Assertions.assertThat(node)
        .containsEntry("id", nodeId)
        .containsEntry("name", "versioned")
        .containsEntry("type", NodeType.TEXT.toString());

    // ...alongside (not instead of) the version-not-found error
    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("Could not find version: 999 for node with id " + nodeId);
  }
}
