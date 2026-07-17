// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.acceptance;

import com.zextras.carbonio.files.FilesStackTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.acceptance.seam.FilesTestApp;
import com.zextras.carbonio.files.acceptance.seam.impl.QuarkusFilesTestAppBuilder;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.api.utilities.entities.PopulatorNode;
import com.zextras.carbonio.files.api.utilities.entities.SimplePopulatorFolder;
import com.zextras.carbonio.files.api.utilities.entities.SimplePopulatorTextFile;
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
 * Task 1.3 of the acceptance coverage-expansion plan: {@code findNodes} sorts that no existing
 * acceptance test drives: {@code OWNER_ASC/DESC}, {@code TYPE_ASC/DESC}, {@code
 * LAST_EDITOR_ASC/DESC}, plus one negative case for the plan-corrected {@code CREATED_AT_ASC}
 * (present in the Java {@code NodeSort} enum but NOT in the GraphQL schema's {@code NodeSort}
 * enum, so it can only ever fail GraphQL document validation).
 *
 * <p><b>FINDING (overrides the plan's prediction for {@code TYPE_ASC}/{@code TYPE_DESC}):</b>
 * {@code NodeRepositoryEbean.getRealSortingsToApply} unconditionally prepends {@code TYPE_ASC} as
 * the primary sort for ANY requested sort other than {@code SIZE_ASC}/{@code SIZE_DESC} (which
 * prepend {@code TYPE_ASC}/{@code TYPE_DESC} respectively) — see the {@code else} branch, which
 * always adds {@code TYPE_ASC} first and the requested sort second, even when the requested sort
 * IS {@code TYPE_DESC}. Since both clauses order the exact same {@code node_category} column, the
 * second (the actual request) is a no-op tie-breaker over rows already fully separated by the
 * first: requesting {@code sort: TYPE_DESC} on {@code findNodes} produces the SAME
 * folder-before-file order as {@code TYPE_ASC}, never the reverse. This test pins the actual
 * (broken) behaviour rather than the plan's predicted reversal.
 *
 * <p><b>FINDING (overrides the plan's prediction of a reachable "Root" position):</b> {@code
 * SearchBuilder}'s base query unconditionally excludes {@code node_category = 0} (=
 * {@code NodeCategory.ROOT}) via {@code .not().eq("mNodeCategory", 0)}. {@code LOCAL_ROOT}/{@code
 * TRASH_ROOT} are also owned by nobody ({@code owner_id IS NULL}) and can never satisfy the
 * owner-or-shared visibility clause either. A {@code ROOT}-category node can therefore NEVER
 * appear in a {@code findNodes} result set — the "Root" position of {@code TYPE_ASC/DESC}
 * ("Root, Folder, File") is structurally unreachable through this query, exactly like the {@code
 * Permissions.equals()} / dead-code residue documented in the plan's §0. Only the Folder/File
 * ordering is exercised below.
 */
@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class FindNodesSortApiIT {

  static FilesTestApp app;

  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OWNER_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String OWNER_C = "cccccccc-cccc-cccc-cccc-cccccccccccc";

  @BeforeAll
  static void init() {
    app =
        QuarkusFilesTestAppBuilder.aFilesTestApp()
            .withDatabase()
            .withServiceDiscover()
            .withUserManagement(
                Map.of(
                    "fake-token", REQUESTER_ID,
                    "fake-token-b", OWNER_B,
                    "fake-token-c", OWNER_C))
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

  private HttpResponse execute(String bodyPayload, String cookie) {
    HttpRequest httpRequest = HttpRequest.of("POST", "/graphql/", cookie, bodyPayload);
    return app.send(httpRequest);
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

    HttpResponse httpResponse = execute(bodyPayload, cookie);
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    Map<String, Object> page = TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "findNodes");
    return (List<Map<String, Object>>) page.get("nodes");
  }

  private List<Map<String, Object>> findNodesSortedBy(String sortLiteral) {
    return findNodesSortedBy(sortLiteral, "ZM_AUTH_TOKEN=fake-token");
  }

  // --- OWNER_ASC / OWNER_DESC -------------------------------------------------------------

  private void seedThreeFilesWithDistinctOwnersVisibleToRequester() {
    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                "00000000-0000-0000-0000-00000000000a",
                REQUESTER_ID,
                REQUESTER_ID,
                "LOCAL_ROOT",
                "ownedByA.txt",
                "",
                NodeType.TEXT,
                "LOCAL_ROOT",
                1L,
                "text/plain"))
        .addNode(
            new PopulatorNode(
                "00000000-0000-0000-0000-00000000000b",
                OWNER_B,
                OWNER_B,
                "LOCAL_ROOT",
                "ownedByB.txt",
                "",
                NodeType.TEXT,
                "LOCAL_ROOT",
                1L,
                "text/plain"))
        .addShare("00000000-0000-0000-0000-00000000000b", REQUESTER_ID, ACL.SharePermission.READ_ONLY)
        .addNode(
            new PopulatorNode(
                "00000000-0000-0000-0000-00000000000c",
                OWNER_C,
                OWNER_C,
                "LOCAL_ROOT",
                "ownedByC.txt",
                "",
                NodeType.TEXT,
                "LOCAL_ROOT",
                1L,
                "text/plain"))
        .addShare("00000000-0000-0000-0000-00000000000c", REQUESTER_ID, ACL.SharePermission.READ_ONLY);
  }

  @Test
  void givenNodesWithDistinctOwnersSortOwnerAscShouldOrderByOwnerIdAscending() {
    // Given
    seedThreeFilesWithDistinctOwnersVisibleToRequester();

    // When
    List<Map<String, Object>> nodes = findNodesSortedBy("OWNER_ASC");

    // Then
    Assertions.assertThat(nodes).hasSize(3);
    Assertions.assertThat(nodes)
        .extracting(node -> node.get("id"))
        .containsExactly(
            "00000000-0000-0000-0000-00000000000a",
            "00000000-0000-0000-0000-00000000000b",
            "00000000-0000-0000-0000-00000000000c");
  }

  @Test
  void givenNodesWithDistinctOwnersSortOwnerDescShouldOrderByOwnerIdDescending() {
    // Given
    seedThreeFilesWithDistinctOwnersVisibleToRequester();

    // When
    List<Map<String, Object>> nodes = findNodesSortedBy("OWNER_DESC");

    // Then
    Assertions.assertThat(nodes).hasSize(3);
    Assertions.assertThat(nodes)
        .extracting(node -> node.get("id"))
        .containsExactly(
            "00000000-0000-0000-0000-00000000000c",
            "00000000-0000-0000-0000-00000000000b",
            "00000000-0000-0000-0000-00000000000a");
  }

  // --- LAST_EDITOR_ASC / LAST_EDITOR_DESC -------------------------------------------------

  /**
   * All three files are owned by the SAME user (the requester) so that {@code OWNER} plays no
   * role in the ordering; only {@code editor_id} differs, set via the real {@code updateNode}
   * mutation (which sets {@code Node#setLastEditorId(requesterId)}) run as three different
   * requesters who each hold at least {@code READ_AND_WRITE} on the node.
   */
  private void seedThreeFilesWithDistinctLastEditors() {
    app.backdoor()
        .populator()
        .addNode(new SimplePopulatorTextFile("00000000-0000-0000-0000-00000000000d", REQUESTER_ID, "editedByA.txt"))
        .addNode(new SimplePopulatorTextFile("00000000-0000-0000-0000-00000000000e", REQUESTER_ID, "editedByB.txt"))
        .addShare("00000000-0000-0000-0000-00000000000e", OWNER_B, ACL.SharePermission.READ_AND_WRITE)
        .addNode(new SimplePopulatorTextFile("00000000-0000-0000-0000-00000000000f", REQUESTER_ID, "editedByC.txt"))
        .addShare("00000000-0000-0000-0000-00000000000f", OWNER_C, ACL.SharePermission.READ_AND_WRITE);

    updateNodeDescription("00000000-0000-0000-0000-00000000000d", "ZM_AUTH_TOKEN=fake-token", "edited by A");
    updateNodeDescription("00000000-0000-0000-0000-00000000000e", "ZM_AUTH_TOKEN=fake-token-b", "edited by B");
    updateNodeDescription("00000000-0000-0000-0000-00000000000f", "ZM_AUTH_TOKEN=fake-token-c", "edited by C");
  }

  private void updateNodeDescription(String nodeId, String cookie, String description) {
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("updateNode")
            .withString("node_id", nodeId)
            .withString("description", description)
            .withWantedResultFormat("{ id }")
            .build();
    HttpResponse response = execute(bodyPayload, cookie);
    Assertions.assertThat(response.getStatus()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(response.getBodyPayload())).isEmpty();
  }

  @Test
  void givenNodesWithDistinctLastEditorsSortLastEditorAscShouldOrderByEditorIdAscending() {
    // Given
    seedThreeFilesWithDistinctLastEditors();

    // When
    List<Map<String, Object>> nodes = findNodesSortedBy("LAST_EDITOR_ASC");

    // Then
    Assertions.assertThat(nodes).hasSize(3);
    Assertions.assertThat(nodes)
        .extracting(node -> node.get("id"))
        .containsExactly(
            "00000000-0000-0000-0000-00000000000d",
            "00000000-0000-0000-0000-00000000000e",
            "00000000-0000-0000-0000-00000000000f");
  }

  @Test
  void givenNodesWithDistinctLastEditorsSortLastEditorDescShouldOrderByEditorIdDescending() {
    // Given
    seedThreeFilesWithDistinctLastEditors();

    // When
    List<Map<String, Object>> nodes = findNodesSortedBy("LAST_EDITOR_DESC");

    // Then
    Assertions.assertThat(nodes).hasSize(3);
    Assertions.assertThat(nodes)
        .extracting(node -> node.get("id"))
        .containsExactly(
            "00000000-0000-0000-0000-00000000000f",
            "00000000-0000-0000-0000-00000000000e",
            "00000000-0000-0000-0000-00000000000d");
  }

  // --- TYPE_ASC / TYPE_DESC ----------------------------------------------------------------

  private void seedOneFolderAndOneFile() {
    app.backdoor()
        .populator()
        .addNode(new SimplePopulatorFolder("10000000-0000-0000-0000-000000000001", REQUESTER_ID, "aFolder"))
        .addNode(new SimplePopulatorTextFile("00000000-0000-0000-0000-000000000001", REQUESTER_ID, "aFile.txt"));
  }

  @Test
  void givenAFolderAndAFileSortTypeAscShouldReturnFolderBeforeFile() {
    // Given
    seedOneFolderAndOneFile();

    // When
    List<Map<String, Object>> nodes = findNodesSortedBy("TYPE_ASC");

    // Then
    Assertions.assertThat(nodes).hasSize(2);
    Assertions.assertThat(nodes.get(0)).containsEntry("id", "10000000-0000-0000-0000-000000000001");
    Assertions.assertThat(nodes.get(1)).containsEntry("id", "00000000-0000-0000-0000-000000000001");
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
    seedOneFolderAndOneFile();

    // When
    List<Map<String, Object>> nodes = findNodesSortedBy("TYPE_DESC");

    // Then — NOT reversed, despite the DESC request (documented bug, not fixed per Phase-1 scope)
    Assertions.assertThat(nodes).hasSize(2);
    Assertions.assertThat(nodes.get(0)).containsEntry("id", "10000000-0000-0000-0000-000000000001");
    Assertions.assertThat(nodes.get(1)).containsEntry("id", "00000000-0000-0000-0000-000000000001");
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
    HttpResponse httpResponse = execute(bodyPayload, "ZM_AUTH_TOKEN=fake-token");

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors).hasSize(1);
    Assertions.assertThat(errors.get(0)).contains("Validation error");
  }
}
