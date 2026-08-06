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
 * {@code com.zextras.carbonio.files.acceptance.FolderChildrenApiIT} rewritten as an out-of-process
 * {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}. All 3 methods and their assertions
 * (including the class-level finding about {@code LOCAL_ROOT}'s hard {@code owner_id = requester}
 * clause vs. a normal folder's owner-OR-share clause) are preserved verbatim; only the seeding
 * mechanism (API calls capturing server-generated ids) and transport changed.
 */
class FolderChildrenApiIT extends AbstractFilesIT {

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
  private Response getChildren(String folderId, int limit, String pageToken, String cookie) {
    String childrenArgs =
        pageToken == null
            ? "limit: " + limit + ", sort: NAME_ASC"
            : "limit: " + limit + ", sort: NAME_ASC, page_token: \\\"" + pageToken + "\\\"";
    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("getNode")
            .withString("node_id", folderId)
            .withWantedResultFormat(
                "{ ... on Folder { children("
                    + childrenArgs
                    + ") { nodes { id name }, page_token } } }")
            .build();
    return graphql(bodyPayload, cookie);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> childrenPage(
      String folderId, int limit, String pageToken, String cookie) {
    Response response = getChildren(folderId, limit, pageToken, cookie);
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(response.getBody().asString())).isEmpty();
    Map<String, Object> node =
        TestUtils.jsonResponseToMap(response.getBody().asString(), "getNode");
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
    String ownedId =
        seedFile(
            "owned.txt", LOCAL_ROOT, "owned".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    String sharedId =
        seedFile(
            "sharedWithMe.txt",
            LOCAL_ROOT,
            "shared".getBytes(StandardCharsets.UTF_8),
            OTHER_COOKIE);
    seedShare(sharedId, REQUESTER_ID, ACL.SharePermission.READ_ONLY, OTHER_COOKIE);

    // When
    Map<String, Object> page = childrenPage("LOCAL_ROOT", 10, null, REQUESTER_COOKIE);

    // Then — only the owned node; the shared one does NOT leak into LOCAL_ROOT's children
    Assertions.assertThat(childIds(page)).containsExactly(ownedId);
  }

  @Test
  void givenANormalFolderChildrenShouldReturnBothOwnedAndIndividuallySharedNodes()
      throws java.sql.SQLException {
    // Given — folder F owned by the requester; C1 owned by the requester, C2 owned by
    // OTHER_USER_ID but directly shared with the requester — both structurally parented under F.
    // NOTE: C2's owner/parent-owner mismatch (parented under a folder it does NOT own, without
    // ever being granted write permission on it) is not producible through the real upload
    // mutation (uploading into F as OTHER_COOKIE would 404 — no write permission on F; and if
    // OTHER_COOKIE somehow held write access, the uploaded child would inherit F's OWNER per the
    // owner-inheritance rule, never the actor's own id) — seeded via the raw-JDBC escape hatch.
    String folderId = seedFolder("F", LOCAL_ROOT, REQUESTER_COOKIE);
    String c1Id =
        seedFile("c1.txt", folderId, "c1".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    String c2Id = "00000000-0000-0000-0000-0000000000c2";
    seedInconsistentNode(
        c2Id,
        OTHER_USER_ID,
        OTHER_USER_ID,
        folderId,
        "c2.txt",
        com.zextras.carbonio.files.dal.dao.ebean.NodeType.TEXT,
        "LOCAL_ROOT," + folderId,
        2L,
        "text/plain");
    seedShare(c2Id, REQUESTER_ID, ACL.SharePermission.READ_ONLY, OTHER_COOKIE);

    // When
    Map<String, Object> page = childrenPage(folderId, 10, null, REQUESTER_COOKIE);

    // Then — unlike LOCAL_ROOT, a normal folder shows BOTH the owned and the individually-shared
    // child
    Assertions.assertThat(childIds(page)).containsExactlyInAnyOrder(c1Id, c2Id);
  }

  @Test
  void givenMoreChildrenThanTheLimitChildrenShouldPaginateViaPageToken() {
    // Given — folder F2 owned by the requester, with 3 children, page size 2
    String folderId = seedFolder("F2", LOCAL_ROOT, REQUESTER_COOKIE);
    tickClock();
    String aId =
        seedFile("aaa.txt", folderId, "a".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    tickClock();
    String bId =
        seedFile("bbb.txt", folderId, "b".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    tickClock();
    String cId =
        seedFile("ccc.txt", folderId, "c".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);

    // When — first page
    Map<String, Object> firstPage = childrenPage(folderId, 2, null, REQUESTER_COOKIE);

    // Then — exactly 2 nodes and a non-null continuation token
    Assertions.assertThat(childIds(firstPage)).containsExactly(aId, bId);
    Assertions.assertThat(firstPage.get("page_token")).isNotNull();

    // When — second page, using the returned token
    String pageToken = (String) firstPage.get("page_token");
    Map<String, Object> secondPage = childrenPage(folderId, 2, pageToken, REQUESTER_COOKIE);

    // Then — the remaining single child, and no further page
    Assertions.assertThat(childIds(secondPage)).containsExactly(cId);
    Assertions.assertThat(secondPage.get("page_token")).isNull();
  }
}
