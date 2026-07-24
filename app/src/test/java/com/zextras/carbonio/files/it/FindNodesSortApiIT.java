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
 * {@code com.zextras.carbonio.files.acceptance.FindNodesSortApiIT} rewritten as an out-of-process
 * {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}. All 7 methods and their assertions
 * (including both class-level FINDINGs — the {@code TYPE_DESC} hardcoded-{@code TYPE_ASC}-prepend
 * bug, and {@code ROOT}-category unreachability, both pinned as the ACTUAL behaviour) are
 * preserved verbatim; only the seeding mechanism (API calls capturing server-generated ids) and
 * transport changed.
 */
class FindNodesSortApiIT extends AbstractFilesIT {

  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OWNER_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String OWNER_C = "cccccccc-cccc-cccc-cccc-cccccccccccc";
  private static final String REQUESTER_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String OWNER_B_COOKIE = "ZM_AUTH_TOKEN=fake-token-b";
  private static final String OWNER_C_COOKIE = "ZM_AUTH_TOKEN=fake-token-c";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", REQUESTER_ID);
    FilesStackTestResource.getUserManagementService().registerToken("fake-token-b", OWNER_B);
    FilesStackTestResource.getUserManagementService().registerToken("fake-token-c", OWNER_C);
  }

  private Response execute(String bodyPayload, String cookie) {
    return graphql(bodyPayload, cookie);
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> findNodesSortedBy(String sortLiteral, String cookie) {
    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("findNodes")
            .withString("folder_id", "LOCAL_ROOT")
            .withBoolean("cascade", true)
            .withEnumLiteral("sort", sortLiteral)
            .withInteger("limit", 10)
            .withWantedResultFormat("{ nodes { id name }, page_token }")
            .build();

    Response response = execute(bodyPayload, cookie);
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);

    Map<String, Object> page = TestUtils.jsonResponseToMap(response.getBody().asString(), "findNodes");
    return (List<Map<String, Object>>) page.get("nodes");
  }

  private List<Map<String, Object>> findNodesSortedBy(String sortLiteral) {
    return findNodesSortedBy(sortLiteral, REQUESTER_COOKIE);
  }

  // --- OWNER_ASC / OWNER_DESC -------------------------------------------------------------

  private String[] seedThreeFilesWithDistinctOwnersVisibleToRequester() {
    String aId = seedFile("ownedByA.txt", LOCAL_ROOT, "a".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    tickClock();
    String bId = seedFile("ownedByB.txt", LOCAL_ROOT, "b".getBytes(StandardCharsets.UTF_8), OWNER_B_COOKIE);
    seedShare(bId, REQUESTER_ID, ACL.SharePermission.READ_ONLY, OWNER_B_COOKIE);
    tickClock();
    String cId = seedFile("ownedByC.txt", LOCAL_ROOT, "c".getBytes(StandardCharsets.UTF_8), OWNER_C_COOKIE);
    seedShare(cId, REQUESTER_ID, ACL.SharePermission.READ_ONLY, OWNER_C_COOKIE);
    return new String[] {aId, bId, cId};
  }

  @Test
  void givenNodesWithDistinctOwnersSortOwnerAscShouldOrderByOwnerIdAscending() {
    // Given
    String[] ids = seedThreeFilesWithDistinctOwnersVisibleToRequester();

    // When
    List<Map<String, Object>> nodes = findNodesSortedBy("OWNER_ASC");

    // Then
    Assertions.assertThat(nodes).hasSize(3);
    Assertions.assertThat(nodes).extracting(node -> node.get("id")).containsExactly(ids[0], ids[1], ids[2]);
  }

  @Test
  void givenNodesWithDistinctOwnersSortOwnerDescShouldOrderByOwnerIdDescending() {
    // Given
    String[] ids = seedThreeFilesWithDistinctOwnersVisibleToRequester();

    // When
    List<Map<String, Object>> nodes = findNodesSortedBy("OWNER_DESC");

    // Then
    Assertions.assertThat(nodes).hasSize(3);
    Assertions.assertThat(nodes).extracting(node -> node.get("id")).containsExactly(ids[2], ids[1], ids[0]);
  }

  // --- LAST_EDITOR_ASC / LAST_EDITOR_DESC -------------------------------------------------

  /**
   * All three files are owned by the SAME user (the requester) so that {@code OWNER} plays no
   * role in the ordering; only {@code editor_id} differs, set via the real {@code updateNode}
   * mutation (which sets {@code Node#setLastEditorId(requesterId)}) run as three different
   * requesters who each hold at least {@code READ_AND_WRITE} on the node.
   */
  private String[] seedThreeFilesWithDistinctLastEditors() {
    String dId = seedFile("editedByA.txt", LOCAL_ROOT, "d".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    tickClock();
    String eId = seedFile("editedByB.txt", LOCAL_ROOT, "e".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    seedShare(eId, OWNER_B, ACL.SharePermission.READ_AND_WRITE, REQUESTER_COOKIE);
    tickClock();
    String fId = seedFile("editedByC.txt", LOCAL_ROOT, "f".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    seedShare(fId, OWNER_C, ACL.SharePermission.READ_AND_WRITE, REQUESTER_COOKIE);

    updateNodeDescription(dId, REQUESTER_COOKIE, "edited by A");
    updateNodeDescription(eId, OWNER_B_COOKIE, "edited by B");
    updateNodeDescription(fId, OWNER_C_COOKIE, "edited by C");
    return new String[] {dId, eId, fId};
  }

  private void updateNodeDescription(String nodeId, String cookie, String description) {
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("updateNode")
            .withString("node_id", nodeId)
            .withString("description", description)
            .withWantedResultFormat("{ id }")
            .build();
    Response response = execute(bodyPayload, cookie);
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(response.getBody().asString())).isEmpty();
  }

  @Test
  void givenNodesWithDistinctLastEditorsSortLastEditorAscShouldOrderByEditorIdAscending() {
    // Given
    String[] ids = seedThreeFilesWithDistinctLastEditors();

    // When
    List<Map<String, Object>> nodes = findNodesSortedBy("LAST_EDITOR_ASC");

    // Then
    Assertions.assertThat(nodes).hasSize(3);
    Assertions.assertThat(nodes).extracting(node -> node.get("id")).containsExactly(ids[0], ids[1], ids[2]);
  }

  @Test
  void givenNodesWithDistinctLastEditorsSortLastEditorDescShouldOrderByEditorIdDescending() {
    // Given
    String[] ids = seedThreeFilesWithDistinctLastEditors();

    // When
    List<Map<String, Object>> nodes = findNodesSortedBy("LAST_EDITOR_DESC");

    // Then
    Assertions.assertThat(nodes).hasSize(3);
    Assertions.assertThat(nodes).extracting(node -> node.get("id")).containsExactly(ids[2], ids[1], ids[0]);
  }

  // --- TYPE_ASC / TYPE_DESC ----------------------------------------------------------------

  private String[] seedOneFolderAndOneFile() {
    String folderId = seedFolder("aFolder", LOCAL_ROOT, REQUESTER_COOKIE);
    tickClock();
    String fileId = seedFile("aFile.txt", LOCAL_ROOT, "f".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    return new String[] {folderId, fileId};
  }

  @Test
  void givenAFolderAndAFileSortTypeAscShouldReturnFolderBeforeFile() {
    // Given
    String[] ids = seedOneFolderAndOneFile();

    // When
    List<Map<String, Object>> nodes = findNodesSortedBy("TYPE_ASC");

    // Then
    Assertions.assertThat(nodes).hasSize(2);
    Assertions.assertThat(nodes.get(0)).containsEntry("id", ids[0]);
    Assertions.assertThat(nodes.get(1)).containsEntry("id", ids[1]);
  }

  /**
   * FINDING: see the class-level Javadoc. {@code sort: TYPE_DESC} does NOT reverse the
   * folder/file grouping — {@code getRealSortingsToApply} always prepends {@code TYPE_ASC} first,
   * making the requested {@code TYPE_DESC} a no-op secondary clause on the same already-resolved
   * column. The observed order is identical to {@code TYPE_ASC}: folder still before file.
   */
  @Test
  void givenAFolderAndAFileSortTypeDescStillReturnsFolderBeforeFileDueToHardcodedTypeAscPrepend() {
    // Given
    String[] ids = seedOneFolderAndOneFile();

    // When
    List<Map<String, Object>> nodes = findNodesSortedBy("TYPE_DESC");

    // Then — NOT reversed, despite the DESC request (documented bug, not fixed per Phase-1 scope)
    Assertions.assertThat(nodes).hasSize(2);
    Assertions.assertThat(nodes.get(0)).containsEntry("id", ids[0]);
    Assertions.assertThat(nodes.get(1)).containsEntry("id", ids[1]);
  }

  // --- CREATED_AT_ASC negative case (plan-corrected) --------------------------------------

  /**
   * {@code CREATED_AT_ASC} exists on the Java {@code NodeSort} enum but was never added to the
   * GraphQL schema's {@code NodeSort} enum (only {@code LAST_EDITOR}, {@code NAME}, {@code
   * OWNER}, {@code TYPE}, {@code UPDATED_AT}, {@code SIZE} are declared). Selecting it over the
   * API can therefore only ever fail GraphQL document validation — it is not a reachable sort.
   */
  @Test
  void givenSortCreatedAtAscWhichIsNotInTheGraphQLSchemaThenValidationErrorNotASort() {
    // Given
    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("findNodes")
            .withString("folder_id", "LOCAL_ROOT")
            .withBoolean("cascade", true)
            .withEnumLiteral("sort", "CREATED_AT_ASC")
            .withInteger("limit", 5)
            .withWantedResultFormat("{ nodes { id }, page_token }")
            .build();

    // When
    Response response = execute(bodyPayload, REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors).hasSize(1);
    Assertions.assertThat(errors.get(0)).contains("Validation error");
  }
}
