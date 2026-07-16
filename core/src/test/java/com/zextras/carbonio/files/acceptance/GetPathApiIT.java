// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.acceptance;

import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.acceptance.seam.FilesTestApp;
import com.zextras.carbonio.files.acceptance.seam.impl.GuiceNettyFilesTestAppBuilder;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.api.utilities.entities.PopulatorNode;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
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
 * Task 1.6 (part 1) of the acceptance coverage-expansion plan: direct coverage of {@code
 * NodeDataFetcher#getPathFetcher}, which no existing acceptance test exercises at all.
 *
 * <p>{@code getPathFetcher} gates entry with the exact same per-node {@code
 * permissionsChecker.getPermissions(nodeId, requesterId).has(READ_ONLY)} check used by {@code
 * getNode} (confirmed by reading the source): if the requester has no relationship (not owner, no
 * {@code Share} row) with the REQUESTED node itself, it returns a single-element list carrying a
 * {@code nodeNotFound} error — {@code "Could not find node with id <id>"} — exactly like {@code
 * getNode} (see {@code NodePermissionsFieldApiIT}'s prior finding). Once past that gate, the
 * behaviour bifurcates on {@code node.getOwnerId().equals(requesterId)} (or {@code node.getType()
 * == ROOT}):
 *
 * <ul>
 *   <li>owner branch: the FULL ancestor chain is returned, from {@code LOCAL_ROOT} to the node,
 *       with NO per-ancestor permission check at all (it trusts that owning a node implies the
 *       whole chain above it is "yours" to see);
 *   <li>non-owner branch: it collects {@code Share} rows for the requester across the WHOLE
 *       ancestor chain (a single {@code shareRepository.getShares(treeNodeIds, requesterId)} call,
 *       no recursion), then returns the sub-list starting at the FIRST (topmost, root-to-leaf
 *       order) node that has a matching {@code Share} row, through the requested node itself.
 * </ul>
 *
 * <p><b>FINDING (the plan's "assert current behaviour and file a finding if it throws" hedge on
 * the inconsistent-share edge):</b> it does NOT throw. Since the initial gate already guarantees
 * the requested node itself has a matching {@code Share} row (that IS what let it pass the gate in
 * the non-owner case), {@code sharedNodes} in the non-owner branch is NEVER empty — at minimum it
 * contains the requested node itself — so {@code sharedNodes.get(0)} never throws {@code
 * IndexOutOfBoundsException}. When NO ancestor above the requested node has its own {@code Share}
 * row (the "inconsistent share" edge: a leaf shared directly with no share anywhere on its
 * ancestor chain — which cannot happen via the real {@code createShare} mutation, since that
 * cascades share rows DOWN to descendants, but IS directly constructible via the test backdoor's
 * non-cascading {@code addShare}), the method silently degrades to a ONE-ELEMENT path containing
 * ONLY the requested node itself — no ancestors, no error. This is a real, silent product
 * oddity worth flagging: a "path" response of length 1 for a file nested three folders deep gives
 * the caller zero indication of that nesting.
 */
class GetPathApiIT {

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

  private PopulatorNode folder(String id, String ownerId, String parentId, String ancestorIds, String name) {
    return new PopulatorNode(
        id, ownerId, ownerId, parentId, name, "", NodeType.FOLDER, ancestorIds, 0L, null);
  }

  private PopulatorNode file(String id, String ownerId, String parentId, String ancestorIds, String name) {
    return new PopulatorNode(
        id, ownerId, ownerId, parentId, name, "", NodeType.TEXT, ancestorIds, 1L, "text/plain");
  }

  private HttpResponse getPathRaw(String nodeId, String cookie) {
    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("getPath")
            .withString("node_id", nodeId)
            .withWantedResultFormat("{ id name }")
            .build();
    HttpRequest httpRequest = HttpRequest.of("POST", "/graphql/", cookie, bodyPayload);
    return app.send(httpRequest);
  }

  private List<Map<String, Object>> getPath(String nodeId, String cookie) {
    HttpResponse httpResponse = getPathRaw(nodeId, cookie);
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    return TestUtils.jsonResponseToList(httpResponse.getBodyPayload(), "getPath");
  }

  @Test
  void givenOwnedNodeGetPathShouldReturnTheFullChainFromRoot() {
    // Given — a 3-level chain, fully owned by the requester
    String folderAId = "10000000-0000-0000-0000-000000000001";
    String folderBId = "10000000-0000-0000-0000-000000000002";
    String fileCId = "00000000-0000-0000-0000-000000000001";
    app.backdoor()
        .populator()
        .addNode(folder(folderAId, REQUESTER_ID, "LOCAL_ROOT", "LOCAL_ROOT", "folderA"))
        .addNode(folder(folderBId, REQUESTER_ID, folderAId, "LOCAL_ROOT," + folderAId, "folderB"))
        .addNode(file(fileCId, REQUESTER_ID, folderBId, "LOCAL_ROOT," + folderAId + "," + folderBId, "fileC.txt"));

    // When
    List<Map<String, Object>> path = getPath(fileCId, "ZM_AUTH_TOKEN=fake-token");

    // Then — the full chain, starting at LOCAL_ROOT itself
    Assertions.assertThat(path).hasSize(4);
    Assertions.assertThat(path.get(0)).containsEntry("id", "LOCAL_ROOT").containsEntry("name", "ROOT");
    Assertions.assertThat(path.get(1)).containsEntry("id", folderAId).containsEntry("name", "folderA");
    Assertions.assertThat(path.get(2)).containsEntry("id", folderBId).containsEntry("name", "folderB");
    Assertions.assertThat(path.get(3)).containsEntry("id", fileCId).containsEntry("name", "fileC");
  }

  @Test
  void givenASharedSubtreeGetPathShouldStartAtTheHighestSharedAncestorNotAtRoot() {
    // Given — a 3-level chain owned by OTHER_USER_ID; only folderB and the leaf are individually
    // shared with the requester (mirrors what the real createShare mutation's cascade would leave
    // behind if only folderB, not folderA, had ever been shared)
    String folderAId = "10000000-0000-0000-0000-000000000001";
    String folderBId = "10000000-0000-0000-0000-000000000002";
    String fileCId = "00000000-0000-0000-0000-000000000001";
    app.backdoor()
        .populator()
        .addNode(folder(folderAId, OTHER_USER_ID, "LOCAL_ROOT", "LOCAL_ROOT", "folderA"))
        .addNode(folder(folderBId, OTHER_USER_ID, folderAId, "LOCAL_ROOT," + folderAId, "folderB"))
        .addNode(file(fileCId, OTHER_USER_ID, folderBId, "LOCAL_ROOT," + folderAId + "," + folderBId, "fileC.txt"))
        .addShare(folderBId, REQUESTER_ID, ACL.SharePermission.READ_ONLY)
        .addShare(fileCId, REQUESTER_ID, ACL.SharePermission.READ_ONLY);

    // When
    List<Map<String, Object>> path = getPath(fileCId, "ZM_AUTH_TOKEN=fake-token");

    // Then — starts at folderB (the highest node with an explicit share), NOT at LOCAL_ROOT nor
    // at the unshared folderA
    Assertions.assertThat(path).hasSize(2);
    Assertions.assertThat(path.get(0)).containsEntry("id", folderBId).containsEntry("name", "folderB");
    Assertions.assertThat(path.get(1)).containsEntry("id", fileCId).containsEntry("name", "fileC");
  }

  /**
   * See the class-level FINDING: this is the "inconsistent share" edge — a leaf shared directly
   * with no share anywhere on its ancestor chain. It does NOT throw; it silently returns a
   * one-element path containing only the leaf itself.
   */
  @Test
  void givenALeafSharedWithNoAncestorShareGetPathSilentlyReturnsOnlyTheLeafItself() {
    // Given — 3-level chain owned by OTHER_USER_ID; ONLY the leaf has a Share row, no ancestor does
    String folderAId = "10000000-0000-0000-0000-000000000001";
    String folderBId = "10000000-0000-0000-0000-000000000002";
    String fileCId = "00000000-0000-0000-0000-000000000001";
    app.backdoor()
        .populator()
        .addNode(folder(folderAId, OTHER_USER_ID, "LOCAL_ROOT", "LOCAL_ROOT", "folderA"))
        .addNode(folder(folderBId, OTHER_USER_ID, folderAId, "LOCAL_ROOT," + folderAId, "folderB"))
        .addNode(file(fileCId, OTHER_USER_ID, folderBId, "LOCAL_ROOT," + folderAId + "," + folderBId, "fileC.txt"))
        .addShare(fileCId, REQUESTER_ID, ACL.SharePermission.READ_ONLY);

    // When
    HttpResponse httpResponse = getPathRaw(fileCId, "ZM_AUTH_TOKEN=fake-token");

    // Then — silently degrades to a singleton path; no ancestors, no error
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    List<Map<String, Object>> path =
        TestUtils.jsonResponseToList(httpResponse.getBodyPayload(), "getPath");
    Assertions.assertThat(path).hasSize(1);
    Assertions.assertThat(path.get(0)).containsEntry("id", fileCId).containsEntry("name", "fileC");
    Assertions.assertThat(TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload())).isEmpty();
  }

  @Test
  void givenNoRelationshipToTheRequestedNodeGetPathShouldReturnNodeNotFound() {
    // Given — a node owned by OTHER_USER_ID, never shared with the requester at all
    String fileDId = "00000000-0000-0000-0000-000000000002";
    app.backdoor()
        .populator()
        .addNode(file(fileDId, OTHER_USER_ID, "LOCAL_ROOT", "LOCAL_ROOT", "fileD.txt"));

    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("getPath")
            .withString("node_id", fileDId)
            .withWantedResultFormat("{ id name }")
            .build();
    HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);

    // When
    HttpResponse httpResponse = app.send(httpRequest);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("Could not find node with id " + fileDId);
  }
}
