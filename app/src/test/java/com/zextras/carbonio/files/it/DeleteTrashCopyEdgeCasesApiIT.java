// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.response.Response;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code com.zextras.carbonio.files.acceptance.DeleteTrashCopyEdgeCasesApiIT} rewritten as an
 * out-of-process {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}. Deepens {@code
 * NodeDataFetcher} branch coverage for {@code trashNodes} and {@code flagNodes}:
 *
 * <ul>
 *   <li>{@code trashNodes}: the truster ≠ the trashed node's CURRENT parent's owner — both the
 *       "owner not yet in the notify list" (fresh add) and "owner already there" (skip, avoid
 *       duplicate) outcomes of {@code !usersToNotify.contains(parent.getOwnerId())}. The node's
 *       owner differing from its structural parent's owner is NOT producible via the public API (a
 *       real {@code createFolder}/{@code upload} always inherits the parent's owner), so these two
 *       nodes are seeded via {@link #seedInconsistentNode} (raw JDBC, mirrors exactly what {@code
 *       NodeRepositoryImpl#createNewNode} persists).
 *   <li>{@code flagNodes}: a ROOT id in the request — the {@code getNodeType() != ROOT} filter.
 * </ul>
 *
 * All 3 methods and their assertions are preserved verbatim; only the seeding mechanism and
 * transport changed.
 */
class DeleteTrashCopyEdgeCasesApiIT extends AbstractFilesIT {

  private static final String OWNER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OTHER_USER_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String OWNER_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String OTHER_COOKIE = "ZM_AUTH_TOKEN=fake-token-b";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", OWNER_ID);
    FilesStackTestResource.getUserManagementService().registerToken("fake-token-b", OTHER_USER_ID);
  }

  @SuppressWarnings("unchecked")
  private int notificationCountOf(String cookie, String onClause) {
    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("getNotifications")
            .withBoolean("update_last_seen", true)
            .withWantedResultFormat("{ notifications { " + onClause + " } }")
            .build();
    Response response = graphql(bodyPayload, cookie);
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Map<String, Object> page =
        TestUtils.jsonResponseToMap(response.getBody().asString(), "getNotifications");
    List<Map<String, Object>> notifications = (List<Map<String, Object>>) page.get("notifications");
    return (int) notifications.stream().filter(n -> !n.isEmpty()).count();
  }

  private Response trashNodes(String nodeId, String cookie) {
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("trashNodes")
            .withListOfStrings("node_ids", new String[] {nodeId})
            .withWantedResultFormat("")
            .build();
    return graphql(bodyPayload, cookie);
  }

  // ---------------------------------------------------------------------------------------------
  // trashNodes
  // ---------------------------------------------------------------------------------------------

  @Test
  void givenATrasherWhoIsNotTheNodesParentOwnerTrashNodesShouldNotifyTheParentOwnerFreshly()
      throws SQLException {
    // Given — folder F owned by OWNER_ID, shared WRITE to OTHER_USER_ID; a node OWNED by
    // OTHER_USER_ID but structurally parented under F, with no shares of its own
    String parentFolderId = seedFolder("F", LOCAL_ROOT, OWNER_COOKIE);
    seedShare(parentFolderId, OTHER_USER_ID, SharePermission.READ_AND_WRITE, OWNER_COOKIE);
    String nodeId = java.util.UUID.randomUUID().toString();
    seedInconsistentNode(
        nodeId,
        OTHER_USER_ID,
        OTHER_USER_ID,
        parentFolderId,
        "child.txt",
        NodeType.TEXT,
        "LOCAL_ROOT," + parentFolderId,
        1L,
        "text/plain");

    // When — the node's owner (not F's owner) trashes it
    Response response = trashNodes(nodeId, OTHER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(response.getBody().asString())).isEmpty();
    Assertions.assertThat(notificationCountOf(OWNER_COOKIE, "... on RemovedNode { created_at }"))
        .isEqualTo(1);
  }

  @Test
  void
      givenATrasherWhoIsNotTheNodesParentOwnerButTheOwnerIsAlreadyAShareTargetTrashNodesShouldNotDuplicateTheNotification()
          throws SQLException {
    // Given — same shape, but the node ALSO has a DIRECT share to F's owner already
    String parentFolderId = seedFolder("F", LOCAL_ROOT, OWNER_COOKIE);
    seedShare(parentFolderId, OTHER_USER_ID, SharePermission.READ_AND_WRITE, OWNER_COOKIE);
    String nodeId = java.util.UUID.randomUUID().toString();
    seedInconsistentNode(
        nodeId,
        OTHER_USER_ID,
        OTHER_USER_ID,
        parentFolderId,
        "child2.txt",
        NodeType.TEXT,
        "LOCAL_ROOT," + parentFolderId,
        1L,
        "text/plain");
    seedShare(nodeId, OWNER_ID, SharePermission.READ_ONLY, OTHER_COOKIE);

    // When
    Response response = trashNodes(nodeId, OTHER_COOKIE);

    // Then — F's owner still gets EXACTLY ONE notification
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(notificationCountOf(OWNER_COOKIE, "... on RemovedNode { created_at }"))
        .isEqualTo(1);
  }

  // ---------------------------------------------------------------------------------------------
  // flagNodes
  // ---------------------------------------------------------------------------------------------

  @Test
  void givenARootIdFlagNodesShouldFilterItAsAnError() {
    // When
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("flagNodes")
            .withListOfStrings("node_ids", new String[] {"LOCAL_ROOT"})
            .withBoolean("flag", true)
            .withWantedResultFormat("")
            .build();
    Response response = graphql(bodyPayload, OWNER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly(
            "There was a problem while executing requested operation on node: LOCAL_ROOT");
  }
}
