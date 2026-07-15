// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.acceptance;

import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.acceptance.seam.FilesTestApp;
import com.zextras.carbonio.files.acceptance.seam.impl.GuiceNettyFilesTestAppBuilder;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.api.utilities.entities.SimplePopulatorFolder;
import com.zextras.carbonio.files.api.utilities.entities.SimplePopulatorTextFile;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
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
 * Task 1.5 (part 2) of the acceptance coverage-expansion plan: direct HTTP coverage of the {@code
 * trashNodes} mutation itself. Existing tests only use the test-only backdoor ({@code
 * DatabasePopulator#addNodeToTrash}) to SET UP other scenarios (e.g. {@code RestoreNodesApiIT}) —
 * none exercise the real mutation's own branches (root-rejected, not-found, permission-denied,
 * partial-success list semantics, subtree cascade).
 */
class TrashNodesApiIT {

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
  private HttpResponse trashNodes(String[] nodeIds, String cookie) {
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("trashNodes")
            .withListOfStrings("node_ids", nodeIds)
            .withWantedResultFormat("")
            .build();
    HttpRequest httpRequest = HttpRequest.of("POST", "/graphql/", cookie, bodyPayload);
    return app.send(httpRequest);
  }

  @SuppressWarnings("unchecked")
  private List<String> findNodeIdsInFolder(String folderId, boolean cascade) {
    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("findNodes")
            .withString("folder_id", folderId)
            .withBoolean("cascade", cascade)
            .withEnumLiteral("sort", "NAME_ASC")
            .withInteger("limit", 20)
            .withWantedResultFormat("{ nodes { id }, page_token }")
            .build();
    HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);
    HttpResponse httpResponse = app.send(httpRequest);
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Map<String, Object> page = TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "findNodes");
    List<Map<String, Object>> nodes = (List<Map<String, Object>>) page.get("nodes");
    return nodes.stream().map(node -> (String) node.get("id")).toList();
  }

  @Test
  void givenANodeInRootTrashNodesShouldMoveItToTrashAndExcludeItFromRootSearch() {
    // Given
    String nodeId = "00000000-0000-0000-0000-000000000001";
    app.backdoor().populator().addNode(new SimplePopulatorTextFile(nodeId, REQUESTER_ID, "toTrash.txt"));

    // When
    HttpResponse httpResponse = trashNodes(new String[] {nodeId}, "ZM_AUTH_TOKEN=fake-token");

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    List<String> data = (List<String>) TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "trashNodes").get("data");
    Assertions.assertThat(data).containsExactly(nodeId);

    // the row still exists (soft trash), but is no longer visible from LOCAL_ROOT and is visible from TRASH_ROOT
    Assertions.assertThat(app.backdoor().nodeExists(nodeId)).isTrue();
    Assertions.assertThat(findNodeIdsInFolder("LOCAL_ROOT", true)).doesNotContain(nodeId);
    Assertions.assertThat(findNodeIdsInFolder("TRASH_ROOT", false)).containsExactly(nodeId);
  }

  @Test
  void givenTheRootNodeItselfTrashNodesShouldRejectItWithNodeWriteError() {
    // When
    HttpResponse httpResponse = trashNodes(new String[] {"LOCAL_ROOT"}, "ZM_AUTH_TOKEN=fake-token");

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Map<String, Object> page = TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "trashNodes");
    Assertions.assertThat((List<String>) page.get("data")).isEmpty();

    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("There was a problem while executing requested operation on node: LOCAL_ROOT");
  }

  @Test
  void givenANonExistentNodeTrashNodesShouldReturnNodeWriteError() {
    // Given
    String nonExistentId = "00000000-0000-0000-0000-00000000ffff";

    // When
    HttpResponse httpResponse = trashNodes(new String[] {nonExistentId}, "ZM_AUTH_TOKEN=fake-token");

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("There was a problem while executing requested operation on node: " + nonExistentId);
  }

  @Test
  void givenNoWritePermissionTrashNodesShouldReturnNodeWriteError() {
    // Given — owned by someone else, shared to the requester as READ_ONLY (no write)
    String nodeId = "00000000-0000-0000-0000-000000000001";
    app.backdoor()
        .populator()
        .addNode(new SimplePopulatorTextFile(nodeId, OTHER_USER_ID, "notMine.txt"))
        .addShare(nodeId, REQUESTER_ID, ACL.SharePermission.READ_ONLY);

    // When
    HttpResponse httpResponse = trashNodes(new String[] {nodeId}, "ZM_AUTH_TOKEN=fake-token");

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("There was a problem while executing requested operation on node: " + nodeId);
  }

  @Test
  void givenAMixOfTrashableAndBadNodesTrashNodesShouldReturnPartialSuccess() {
    // Given
    String goodId = "00000000-0000-0000-0000-000000000001";
    String badId = "00000000-0000-0000-0000-00000000ffff";
    app.backdoor().populator().addNode(new SimplePopulatorTextFile(goodId, REQUESTER_ID, "good.txt"));

    // When
    HttpResponse httpResponse = trashNodes(new String[] {goodId, badId}, "ZM_AUTH_TOKEN=fake-token");

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Map<String, Object> page = TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "trashNodes");
    Assertions.assertThat((List<String>) page.get("data")).containsExactly(goodId);

    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("There was a problem while executing requested operation on node: " + badId);
  }

  @Test
  void givenAFolderWithAChildTrashNodesShouldCascadeTheSubtreeIntoTrash() {
    // Given
    String folderId = "10000000-0000-0000-0000-000000000001";
    String childId = "00000000-0000-0000-0000-000000000001";
    app.backdoor()
        .populator()
        .addNode(new SimplePopulatorFolder(folderId, REQUESTER_ID, "parentFolder"))
        .addNode(new SimplePopulatorTextFile(childId, REQUESTER_ID, "child.txt"));
    // move the child under the folder directly via a real HTTP moveNodes call so its ancestor
    // chain is consistent before we trash the folder
    String movePayload =
        GraphqlCommandBuilder.aMutationBuilder("moveNodes")
            .withListOfStrings("node_ids", new String[] {childId})
            .withString("destination_id", folderId)
            .withWantedResultFormat("{ id }")
            .build();
    HttpResponse moveResponse =
        app.send(HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", movePayload));
    Assertions.assertThat(moveResponse.getStatus()).isEqualTo(200);

    // When
    HttpResponse httpResponse = trashNodes(new String[] {folderId}, "ZM_AUTH_TOKEN=fake-token");

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Map<String, Object> page = TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "trashNodes");
    Assertions.assertThat((List<String>) page.get("data")).containsExactly(folderId);

    // the whole subtree (folder + child) is now reachable under TRASH_ROOT and gone from LOCAL_ROOT
    Assertions.assertThat(findNodeIdsInFolder("TRASH_ROOT", true)).containsExactlyInAnyOrder(folderId, childId);
    Assertions.assertThat(findNodeIdsInFolder("LOCAL_ROOT", true)).isEmpty();
  }
}
