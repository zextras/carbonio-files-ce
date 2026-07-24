// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/*
 * This test class mimics findNodes tests but only checks if the pagination is correct and has been created
 * to avoid checking two things in the same test class, so findNodesApi will only check if the search returns correct
 * values without pagination and PaginationFindNodes will only check if those values are paginated correctly.
 * Obviously if the search is broken so will be the pagination since paginated values are returned based on the
 * search criteria.
 *
 * {@code com.zextras.carbonio.files.acceptance.PaginationFindNodesApiIT} rewritten as an
 * out-of-process {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}. All 8 methods and
 * their assertions are preserved verbatim; only the seeding mechanism (API calls capturing
 * server-generated ids, flattened out of the original's shared-per-nested-class fixture — see
 * {@link com.zextras.carbonio.files.it.FindNodesApiIT}'s class javadoc for why) and transport
 * changed.
 */
class PaginationFindNodesApiIT extends AbstractFilesIT {

  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OTHER_USER_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String REQUESTER_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String OTHER_COOKIE = "ZM_AUTH_TOKEN=fake-token-b";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", REQUESTER_ID);
    FilesStackTestResource.getUserManagementService().registerToken("fake-token-b", OTHER_USER_ID);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> findNodesPage(String sort, int limit, String pageToken, String cookie) {
    GraphqlCommandBuilder builder =
        GraphqlCommandBuilder.aQueryBuilder("findNodes")
            .withString("folder_id", "LOCAL_ROOT")
            .withBoolean("cascade", true)
            .withEnumLiteral("sort", sort)
            .withInteger("limit", limit);
    if (pageToken != null) {
      builder.withString("page_token", pageToken);
    }
    String bodyPayload = builder.withWantedResultFormat("{ nodes { id name }, page_token }").build();

    Response response = graphql(bodyPayload, cookie);
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    return TestUtils.jsonResponseToMap(response.getBody().asString(), "findNodes");
  }

  private String[] seedFiveMixedNodes() {
    String folderAId = seedFolder("folderA", LOCAL_ROOT, REQUESTER_COOKIE);
    tickClock();
    String folderBId = seedFolder("folderB", LOCAL_ROOT, REQUESTER_COOKIE);
    tickClock();
    String aaaId = seedFile("aaa.txt", LOCAL_ROOT, "a".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    tickClock();
    String bbbId = seedFile("bbb.txt", LOCAL_ROOT, "b".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    tickClock();
    String cccId = seedFile("ccc.txt", LOCAL_ROOT, "c".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    return new String[] {folderAId, folderBId, aaaId, bbbId, cccId};
  }

  @Test
  void givenFilesOnRootSearchWithSortNameAscShouldReturnCorrectlyPaginatedNodes() {
    String[] ids = seedFiveMixedNodes();

    Map<String, Object> firstPage = findNodesPage("NAME_ASC", 4, null, REQUESTER_COOKIE);
    String pageToken = (String) firstPage.get("page_token");

    Map<String, Object> secondPage = findNodesPage("NAME_ASC", 1, pageToken, REQUESTER_COOKIE);
    List<Map<String, Object>> nodes = (List<Map<String, Object>>) secondPage.get("nodes");

    Assertions.assertThat(nodes).hasSize(1);
    Assertions.assertThat(nodes.get(0)).containsEntry("id", ids[4]).containsEntry("name", "ccc");
  }

  @Test
  void givenFilesOnRootSearchWithSortNameDescShouldReturnCorrectlyPaginatedNodes() {
    String[] ids = seedFiveMixedNodes();

    Map<String, Object> firstPage = findNodesPage("NAME_DESC", 4, null, REQUESTER_COOKIE);
    String pageToken = (String) firstPage.get("page_token");

    Map<String, Object> secondPage = findNodesPage("NAME_DESC", 1, pageToken, REQUESTER_COOKIE);
    List<Map<String, Object>> nodes = (List<Map<String, Object>>) secondPage.get("nodes");

    Assertions.assertThat(nodes).hasSize(1);
    Assertions.assertThat(nodes.get(0)).containsEntry("id", ids[2]).containsEntry("name", "aaa");
  }

  @Test
  void givenFilesOnRootSearchWithSortLastUpdateAscShouldReturnCorrectlyPaginatedNodes() {
    String[] ids = seedFiveMixedNodes();

    Map<String, Object> firstPage = findNodesPage("UPDATED_AT_ASC", 4, null, REQUESTER_COOKIE);
    String pageToken = (String) firstPage.get("page_token");

    Map<String, Object> secondPage = findNodesPage("UPDATED_AT_ASC", 1, pageToken, REQUESTER_COOKIE);
    List<Map<String, Object>> nodes = (List<Map<String, Object>>) secondPage.get("nodes");

    Assertions.assertThat(nodes).hasSize(1);
    Assertions.assertThat(nodes.get(0)).containsEntry("id", ids[4]).containsEntry("name", "ccc");
  }

  @Test
  void givenFilesOnRootSearchWithSortLastUpdateDescShouldReturnCorrectlyPaginatedNodes() { // recents
    String[] ids = seedFiveMixedNodes();

    Map<String, Object> firstPage = findNodesPage("UPDATED_AT_DESC", 4, null, REQUESTER_COOKIE);
    String pageToken = (String) firstPage.get("page_token");

    Map<String, Object> secondPage = findNodesPage("UPDATED_AT_DESC", 1, pageToken, REQUESTER_COOKIE);
    List<Map<String, Object>> nodes = (List<Map<String, Object>>) secondPage.get("nodes");

    Assertions.assertThat(nodes).hasSize(1);
    Assertions.assertThat(nodes.get(0)).containsEntry("id", ids[2]).containsEntry("name", "aaa");
  }

  private String[] seedNodesWithDistinctSizes() {
    String folder1Id = seedFolder("folder", LOCAL_ROOT, REQUESTER_COOKIE);
    tickClock();
    String folder2Id = seedFolder("folder", LOCAL_ROOT, REQUESTER_COOKIE);
    tickClock();
    String file0Id = seedFile("fake.txt", LOCAL_ROOT, new byte[0], REQUESTER_COOKIE);
    tickClock();
    String file1Id = seedFile("fake.txt", LOCAL_ROOT, new byte[1], REQUESTER_COOKIE);
    tickClock();
    String file2Id = seedFile("fake.txt", LOCAL_ROOT, new byte[2], REQUESTER_COOKIE);
    return new String[] {folder1Id, folder2Id, file0Id, file1Id, file2Id};
  }

  @Test
  void givenFilesOnRootSearchWithSortSizeAscShouldReturnCorrectlyPaginatedNodes() {
    String[] ids = seedNodesWithDistinctSizes();

    Map<String, Object> firstPage = findNodesPage("SIZE_ASC", 4, null, REQUESTER_COOKIE);
    String pageToken = (String) firstPage.get("page_token");

    Map<String, Object> secondPage = findNodesPage("SIZE_ASC", 1, pageToken, REQUESTER_COOKIE);
    List<Map<String, Object>> nodes = (List<Map<String, Object>>) secondPage.get("nodes");

    // Note: unlike the old raw-DB backdoor, the real API dedups same-named siblings with a
    // "(N)" suffix — see FindNodesApiIT's equivalent size-sort tests for the same observation.
    Assertions.assertThat(nodes).hasSize(1);
    Assertions.assertThat(nodes.get(0)).containsEntry("id", ids[4]).containsEntry("name", "fake (2)");
  }

  @Test
  void givenFilesOnRootSearchWithSortSizeDescShouldReturnCorrectlyPaginatedNodes() {
    String[] ids = seedNodesWithDistinctSizes();

    Map<String, Object> firstPage = findNodesPage("SIZE_DESC", 4, null, REQUESTER_COOKIE);
    String pageToken = (String) firstPage.get("page_token");

    Map<String, Object> secondPage = findNodesPage("SIZE_DESC", 1, pageToken, REQUESTER_COOKIE);
    List<Map<String, Object>> nodes = (List<Map<String, Object>>) secondPage.get("nodes");

    Assertions.assertThat(nodes).hasSize(1);
    Assertions.assertThat(nodes.get(0)).containsEntry("id", ids[1]).containsEntry("name", "folder (1)");
  }

  @Test
  @DisplayName("""
    Given a LOCAL_ROOT with three nodes inside, a limit of two elements per page and the second
    element is a file having a filename with a SQL injected: the findNodes should not be vulnerable
    by the injection and should return the second page containing the third node and a null
    page_token
    """)
  void givenFilesOnRootHavingInjectedSQLInFilenameSearchWithSortNameAscShouldNotBeVulnerable() {
    seedFolder("folderA", LOCAL_ROOT, REQUESTER_COOKIE);
    tickClock();
    seedFile("test')) OR 1=1 --file.txt", LOCAL_ROOT, "x".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    tickClock();
    String yFileId = seedFile("y file.txt", LOCAL_ROOT, "y".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    tickClock();
    seedFolder("g folder", LOCAL_ROOT, OTHER_COOKIE);
    tickClock();
    seedFile("z file.txt", LOCAL_ROOT, "z".getBytes(StandardCharsets.UTF_8), OTHER_COOKIE);

    Map<String, Object> firstPage = findNodesPage("NAME_ASC", 2, null, REQUESTER_COOKIE);
    String pageToken = (String) firstPage.get("page_token");

    Map<String, Object> secondPage = findNodesPage("NAME_ASC", 2, pageToken, REQUESTER_COOKIE);
    List<Map<String, Object>> nodes = (List<Map<String, Object>>) secondPage.get("nodes");

    Assertions.assertThat(nodes).hasSize(1);
    Assertions.assertThat(nodes.get(0)).containsEntry("id", yFileId).containsEntry("name", "y file");
  }

  @DisplayName("""
    Given a LOCAL_ROOT with three nodes inside, a limit of two elements per page and the second
    element is a folder having a filename with a SQL injected: the findNodes should not be
    vulnerable by the injection and should return the second page containing the third node and a
    null page_token
    """)
  @Test
  void givenFoldersOnRootHavingInjectedSQLInFilenameSearchWithSortNameAscShouldNotBeVulnerable() {
    seedFolder("folderA", LOCAL_ROOT, REQUESTER_COOKIE);
    tickClock();
    seedFolder("folderB')) OR 1=1 --", LOCAL_ROOT, REQUESTER_COOKIE);
    tickClock();
    String gFolderId = seedFolder("g folder", LOCAL_ROOT, REQUESTER_COOKIE);
    tickClock();
    seedFolder("g folder", LOCAL_ROOT, OTHER_COOKIE);
    tickClock();
    seedFolder("h folder", LOCAL_ROOT, OTHER_COOKIE);

    Map<String, Object> firstPage = findNodesPage("NAME_ASC", 2, null, REQUESTER_COOKIE);
    String pageToken = (String) firstPage.get("page_token");

    Map<String, Object> secondPage = findNodesPage("NAME_ASC", 2, pageToken, REQUESTER_COOKIE);
    List<Map<String, Object>> nodes = (List<Map<String, Object>>) secondPage.get("nodes");

    Assertions.assertThat(nodes).hasSize(1);
    Assertions.assertThat(nodes.get(0)).containsEntry("id", gFolderId).containsEntry("name", "g folder");
  }
}
