// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.acceptance;

import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.acceptance.seam.FilesTestApp;
import com.zextras.carbonio.files.acceptance.seam.impl.GuiceNettyFilesTestAppBuilder;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.api.utilities.entities.PopulatorNode;
import com.zextras.carbonio.files.api.utilities.entities.SimplePopulatorFolder;
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
 * Task 1.6 (part 3) of the acceptance coverage-expansion plan: direct coverage of {@code
 * Folder.children} (bound to {@code NodeDataFetcher#getChildNodesFetcherFast}).
 *
 * <p>Reading the source: the fetcher calls {@code nodeRepository.findNodes(...)} scoped to {@code
 * folder_id = <this folder>, cascade = false}, with ONE special case on the {@code
 * shared_with_me} parameter:
 *
 * <ul>
 *   <li>if the folder is {@code LOCAL_ROOT}: {@code sharedWithMe = Optional.of(false)}, which
 *       {@code SearchBuilder#setSharedWithMe} turns into a hard {@code owner_id = requester}
 *       filter — REPLACING the base query's normal {@code (owner OR direct-share)} clause
 *       entirely. So even a node directly shared with the requester at the top level is excluded;
 *   <li>for any other folder: {@code sharedWithMe = Optional.empty()}, so {@code
 *       SearchBuilder}'s BASE constructor filter applies unmodified: {@code owner_id = requester
 *       OR mShares.target_user_id = requester}. A child individually shared with the requester
 *       (a direct {@code Share} row on that exact child, independent of whatever share exists — or
 *       doesn't — on the folder itself) IS included, alongside owned children.
 * </ul>
 *
 * <p>Pagination reuses the same {@code page_token} mechanism as {@code findNodes} (both route
 * through {@code nodePageFetcher} for the {@code nodes} field), confirmed reachable via the {@code
 * children(limit, sort, page_token)} schema arguments.
 */
class FolderChildrenApiIT {

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
  private HttpResponse getChildren(String folderId, int limit, String pageToken, String cookie) {
    String childrenArgs =
        pageToken == null
            ? "limit: " + limit + ", sort: NAME_ASC"
            : "limit: " + limit + ", sort: NAME_ASC, page_token: \\\"" + pageToken + "\\\"";
    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("getNode")
            .withString("node_id", folderId)
            .withWantedResultFormat(
                "{ ... on Folder { children(" + childrenArgs + ") { nodes { id name }, page_token } } }")
            .build();
    HttpRequest httpRequest = HttpRequest.of("POST", "/graphql/", cookie, bodyPayload);
    return app.send(httpRequest);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> childrenPage(String folderId, int limit, String pageToken, String cookie) {
    HttpResponse httpResponse = getChildren(folderId, limit, pageToken, cookie);
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload())).isEmpty();
    Map<String, Object> node = TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "getNode");
    return (Map<String, Object>) node.get("children");
  }

  private List<String> childIds(Map<String, Object> page) {
    List<Map<String, Object>> nodes = (List<Map<String, Object>>) page.get("nodes");
    return nodes.stream().map(n -> (String) n.get("id")).toList();
  }

  @Test
  void
      givenLocalRootWithAnOwnedNodeAndASharedNodeChildrenShouldReturnOnlyTheOwnedNodeNoSharedWithMeLeakage() {
    // Given — one node owned by the requester, and one node owned by OTHER_USER_ID but directly
    // shared with the requester, both sitting at LOCAL_ROOT
    String ownedId = "00000000-0000-0000-0000-000000000001";
    String sharedId = "00000000-0000-0000-0000-000000000002";
    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                ownedId, REQUESTER_ID, REQUESTER_ID, "LOCAL_ROOT", "owned.txt", "",
                NodeType.TEXT, "LOCAL_ROOT", 1L, "text/plain"))
        .addNode(
            new PopulatorNode(
                sharedId, OTHER_USER_ID, OTHER_USER_ID, "LOCAL_ROOT", "sharedWithMe.txt", "",
                NodeType.TEXT, "LOCAL_ROOT", 1L, "text/plain"))
        .addShare(sharedId, REQUESTER_ID, ACL.SharePermission.READ_ONLY);

    // When
    Map<String, Object> page = childrenPage("LOCAL_ROOT", 10, null, "ZM_AUTH_TOKEN=fake-token");

    // Then — only the owned node; the shared one does NOT leak into LOCAL_ROOT's children
    Assertions.assertThat(childIds(page)).containsExactly(ownedId);
  }

  @Test
  void givenANormalFolderChildrenShouldReturnBothOwnedAndIndividuallySharedNodes() {
    // Given — folder F owned by the requester; C1 owned by the requester, C2 owned by
    // OTHER_USER_ID but directly shared with the requester — both structurally parented under F
    String folderId = "10000000-0000-0000-0000-000000000001";
    String c1Id = "00000000-0000-0000-0000-000000000001";
    String c2Id = "00000000-0000-0000-0000-000000000002";
    app.backdoor()
        .populator()
        .addNode(new SimplePopulatorFolder(folderId, REQUESTER_ID, "F"))
        .addNode(
            new PopulatorNode(
                c1Id, REQUESTER_ID, REQUESTER_ID, folderId, "c1.txt", "",
                NodeType.TEXT, "LOCAL_ROOT," + folderId, 1L, "text/plain"))
        .addNode(
            new PopulatorNode(
                c2Id, OTHER_USER_ID, OTHER_USER_ID, folderId, "c2.txt", "",
                NodeType.TEXT, "LOCAL_ROOT," + folderId, 1L, "text/plain"))
        .addShare(c2Id, REQUESTER_ID, ACL.SharePermission.READ_ONLY);

    // When
    Map<String, Object> page = childrenPage(folderId, 10, null, "ZM_AUTH_TOKEN=fake-token");

    // Then — unlike LOCAL_ROOT, a normal folder shows BOTH the owned and the individually-shared
    // child
    Assertions.assertThat(childIds(page)).containsExactlyInAnyOrder(c1Id, c2Id);
  }

  @Test
  void givenMoreChildrenThanTheLimitChildrenShouldPaginateViaPageToken() {
    // Given — folder F2 owned by the requester, with 3 children, page size 2
    String folderId = "10000000-0000-0000-0000-000000000002";
    String aId = "00000000-0000-0000-0000-000000000003";
    String bId = "00000000-0000-0000-0000-000000000004";
    String cId = "00000000-0000-0000-0000-000000000005";
    app.backdoor()
        .populator()
        .addNode(new SimplePopulatorFolder(folderId, REQUESTER_ID, "F2"))
        .addNode(
            new PopulatorNode(
                aId, REQUESTER_ID, REQUESTER_ID, folderId, "aaa.txt", "",
                NodeType.TEXT, "LOCAL_ROOT," + folderId, 1L, "text/plain"))
        .addNode(
            new PopulatorNode(
                bId, REQUESTER_ID, REQUESTER_ID, folderId, "bbb.txt", "",
                NodeType.TEXT, "LOCAL_ROOT," + folderId, 1L, "text/plain"))
        .addNode(
            new PopulatorNode(
                cId, REQUESTER_ID, REQUESTER_ID, folderId, "ccc.txt", "",
                NodeType.TEXT, "LOCAL_ROOT," + folderId, 1L, "text/plain"));

    // When — first page
    Map<String, Object> firstPage = childrenPage(folderId, 2, null, "ZM_AUTH_TOKEN=fake-token");

    // Then — exactly 2 nodes and a non-null continuation token
    Assertions.assertThat(childIds(firstPage)).containsExactly(aId, bId);
    Assertions.assertThat(firstPage.get("page_token")).isNotNull();

    // When — second page, using the returned token
    String pageToken = (String) firstPage.get("page_token");
    Map<String, Object> secondPage =
        childrenPage(folderId, 2, pageToken, "ZM_AUTH_TOKEN=fake-token");

    // Then — the remaining single child, and no further page
    Assertions.assertThat(childIds(secondPage)).containsExactly(cId);
    Assertions.assertThat(secondPage.get("page_token")).isNull();
  }
}
