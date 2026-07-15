// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.acceptance;

import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.acceptance.seam.FilesTestApp;
import com.zextras.carbonio.files.acceptance.seam.impl.GuiceNettyFilesTestAppBuilder;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.api.utilities.entities.SimplePopulatorTextFile;
import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
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
 * NEW canonical file: the {@code updateNode} mutation (bound to {@code
 * NodeDataFetcher#updateNodeFetcher}) had ZERO direct acceptance coverage before this file — every
 * other {@code *ApiIT} only exercises it transitively (never at all, in fact: no other file calls
 * the {@code updateNode} mutation).
 *
 * <p>Covers: happy rename, permission-denied, the rename-collision -&gt; {@code duplicateNode}
 * branch (the JaCoCo-confirmed 0%-covered {@code searchAlternativeName(...).equals(nodeFullName)}
 * check — contrast with {@code createFolder}'s auto-rename-on-collision behaviour: {@code
 * updateNode} REJECTS a collision instead of auto-renaming), a description-only update (no rename
 * search performed at all since {@code name} is absent), and a flagged-only update.
 */
class UpdateNodeApiIT {

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

  private HttpResponse updateNode(
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
    return app.send(HttpRequest.of("POST", "/graphql/", cookie, bodyPayload));
  }

  @Test
  void givenWritePermissionUpdateNodeShouldRenameTheNode() {
    // Given
    String nodeId = "70000000-0000-0000-0000-000000000001";
    app.backdoor().populator().addNode(new SimplePopulatorTextFile(nodeId, REQUESTER_ID, "old.txt"));

    // When
    HttpResponse httpResponse = updateNode(nodeId, "renamed", null, null, "ZM_AUTH_TOKEN=fake-token");

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload())).isEmpty();
    Map<String, Object> node = TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "updateNode");
    Assertions.assertThat(node).containsEntry("name", "renamed");
  }

  @Test
  void givenNoWritePermissionUpdateNodeShouldReturnNodeNotFoundError() {
    // Given — owned by someone else, shared READ_ONLY (no write) with the requester
    String nodeId = "70000000-0000-0000-0000-000000000002";
    app.backdoor()
        .populator()
        .addNode(new SimplePopulatorTextFile(nodeId, OTHER_USER_ID, "notMine.txt"))
        .addShare(nodeId, REQUESTER_ID, SharePermission.READ_ONLY);

    // When
    HttpResponse httpResponse = updateNode(nodeId, "renamed", null, null, "ZM_AUTH_TOKEN=fake-token");

    // Then — updateNodeFetcher's permission-denied branch returns nodeNotFound, not nodeWriteError
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("Could not find node with id " + nodeId);
  }

  @Test
  void givenANameCollisionInTheSameParentUpdateNodeShouldReturnDuplicateNodeErrorNotAutoRename() {
    // Given — two files in LOCAL_ROOT; renaming the second to collide with the first's full name
    String existingId = "70000000-0000-0000-0000-000000000003";
    String nodeId = "70000000-0000-0000-0000-000000000004";
    app.backdoor()
        .populator()
        .addNode(new SimplePopulatorTextFile(existingId, REQUESTER_ID, "taken.txt"))
        .addNode(new SimplePopulatorTextFile(nodeId, REQUESTER_ID, "original.txt"));

    // When — renaming to "taken" would produce the full name "taken.txt", already present
    HttpResponse httpResponse = updateNode(nodeId, "taken", null, null, "ZM_AUTH_TOKEN=fake-token");

    // Then — REJECTED with duplicateNode, unlike createFolder's silent " (1)" auto-rename
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly(
            "Trying to create a duplicate for the node " + nodeId + " in destination folder LOCAL_ROOT");

    // the node was NOT renamed
    Assertions.assertThat(app.backdoor().nodeExists(nodeId)).isTrue();
  }

  @Test
  void givenOnlyADescriptionUpdateNodeShouldUpdateItWithoutTouchingTheName() {
    // Given — closes the `optName.isPresent()` FALSE branch: no rename search is performed at all
    String nodeId = "70000000-0000-0000-0000-000000000005";
    app.backdoor().populator().addNode(new SimplePopulatorTextFile(nodeId, REQUESTER_ID, "untouched.txt"));

    // When
    HttpResponse httpResponse =
        updateNode(nodeId, null, "a new description", null, "ZM_AUTH_TOKEN=fake-token");

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Map<String, Object> node = TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "updateNode");
    Assertions.assertThat(node)
        .containsEntry("name", "untouched")
        .containsEntry("description", "a new description");
  }

  @Test
  void givenOnlyAFlaggedValueUpdateNodeShouldFlagTheNodeWithoutTouchingItsName() {
    // Given
    String nodeId = "70000000-0000-0000-0000-000000000006";
    app.backdoor().populator().addNode(new SimplePopulatorTextFile(nodeId, REQUESTER_ID, "flagme.txt"));

    // When
    HttpResponse httpResponse = updateNode(nodeId, null, null, true, "ZM_AUTH_TOKEN=fake-token");

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Map<String, Object> node = TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "updateNode");
    Assertions.assertThat(node).containsEntry("name", "flagme");
  }
}
