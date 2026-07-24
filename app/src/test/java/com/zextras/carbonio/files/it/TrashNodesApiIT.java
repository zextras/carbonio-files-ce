// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code com.zextras.carbonio.files.acceptance.TrashNodesApiIT} rewritten as an out-of-process
 * {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}. Direct HTTP coverage of the {@code
 * trashNodes} mutation itself (root-rejected, not-found, permission-denied, partial-success list
 * semantics, subtree cascade). All 6 methods and their assertions are preserved verbatim; only the
 * seeding mechanism (API calls capturing server-generated ids) and transport changed.
 */
class TrashNodesApiIT extends AbstractFilesIT {

  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OTHER_USER_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String REQUESTER_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String OTHER_COOKIE = "ZM_AUTH_TOKEN=fake-token-b";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", REQUESTER_ID);
    FilesStackTestResource.getUserManagementService().registerToken("fake-token-b", OTHER_USER_ID);
  }

  private Response trashNodes(String[] nodeIds, String cookie) {
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("trashNodes")
            .withListOfStrings("node_ids", nodeIds)
            .withWantedResultFormat("")
            .build();
    return graphql(bodyPayload, cookie);
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
    Response response = graphql(bodyPayload, REQUESTER_COOKIE);
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Map<String, Object> page = TestUtils.jsonResponseToMap(response.getBody().asString(), "findNodes");
    List<Map<String, Object>> nodes = (List<Map<String, Object>>) page.get("nodes");
    return nodes.stream().map(node -> (String) node.get("id")).toList();
  }

  @Test
  void givenANodeInRootTrashNodesShouldMoveItToTrashAndExcludeItFromRootSearch() {
    // Given
    String nodeId =
        seedFile("toTrash.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);

    // When
    Response response = trashNodes(new String[] {nodeId}, REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> data =
        (List<String>) TestUtils.jsonResponseToMap(response.getBody().asString(), "trashNodes").get("data");
    Assertions.assertThat(data).containsExactly(nodeId);

    // the row still exists (soft trash), but is no longer visible from LOCAL_ROOT and is visible from TRASH_ROOT
    Assertions.assertThat(nodeExists(nodeId, REQUESTER_COOKIE)).isTrue();
    Assertions.assertThat(findNodeIdsInFolder("LOCAL_ROOT", true)).doesNotContain(nodeId);
    Assertions.assertThat(findNodeIdsInFolder("TRASH_ROOT", false)).containsExactly(nodeId);
  }

  @Test
  void givenTheRootNodeItselfTrashNodesShouldRejectItWithNodeWriteError() {
    // When
    Response response = trashNodes(new String[] {"LOCAL_ROOT"}, REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Map<String, Object> page = TestUtils.jsonResponseToMap(response.getBody().asString(), "trashNodes");
    Assertions.assertThat((List<String>) page.get("data")).isEmpty();

    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("There was a problem while executing requested operation on node: LOCAL_ROOT");
  }

  @Test
  void givenANonExistentNodeTrashNodesShouldReturnNodeWriteError() {
    // Given
    String nonExistentId = "00000000-0000-0000-0000-00000000ffff";

    // When
    Response response = trashNodes(new String[] {nonExistentId}, REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("There was a problem while executing requested operation on node: " + nonExistentId);
  }

  @Test
  void givenNoWritePermissionTrashNodesShouldReturnNodeWriteError() {
    // Given — owned by someone else, shared to the requester as READ_ONLY (no write)
    String nodeId =
        seedFile("notMine.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OTHER_COOKIE);
    seedShare(nodeId, REQUESTER_ID, ACL.SharePermission.READ_ONLY, OTHER_COOKIE);

    // When
    Response response = trashNodes(new String[] {nodeId}, REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("There was a problem while executing requested operation on node: " + nodeId);
  }

  @Test
  void givenAMixOfTrashableAndBadNodesTrashNodesShouldReturnPartialSuccess() {
    // Given
    String goodId =
        seedFile("good.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    String badId = "00000000-0000-0000-0000-00000000ffff";

    // When
    Response response = trashNodes(new String[] {goodId, badId}, REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Map<String, Object> page = TestUtils.jsonResponseToMap(response.getBody().asString(), "trashNodes");
    Assertions.assertThat((List<String>) page.get("data")).containsExactly(goodId);

    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("There was a problem while executing requested operation on node: " + badId);
  }

  @Test
  void givenAFolderWithAChildTrashNodesShouldCascadeTheSubtreeIntoTrash() {
    // Given
    String folderId = seedFolder("parentFolder", LOCAL_ROOT, REQUESTER_COOKIE);
    String childId =
        seedFile("child.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    // move the child under the folder directly via a real HTTP moveNodes call so its ancestor
    // chain is consistent before we trash the folder
    String movePayload =
        GraphqlCommandBuilder.aMutationBuilder("moveNodes")
            .withListOfStrings("node_ids", new String[] {childId})
            .withString("destination_id", folderId)
            .withWantedResultFormat("{ id }")
            .build();
    Response moveResponse = graphql(movePayload, REQUESTER_COOKIE);
    Assertions.assertThat(moveResponse.getStatusCode()).isEqualTo(200);

    // When
    Response response = trashNodes(new String[] {folderId}, REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Map<String, Object> page = TestUtils.jsonResponseToMap(response.getBody().asString(), "trashNodes");
    Assertions.assertThat((List<String>) page.get("data")).containsExactly(folderId);

    // the whole subtree (folder + child) is now reachable under TRASH_ROOT and gone from LOCAL_ROOT
    Assertions.assertThat(findNodeIdsInFolder("TRASH_ROOT", true))
        .containsExactlyInAnyOrder(folderId, childId);
    Assertions.assertThat(findNodeIdsInFolder("LOCAL_ROOT", true)).isEmpty();
  }
}
