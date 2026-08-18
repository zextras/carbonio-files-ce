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
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code com.zextras.carbonio.files.acceptance.AddedAndRemovedNodeNotificationMoveApiIT} rewritten
 * as an out-of-process {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}: moving a node
 * FROM one directory shared with a user TO another directory ALSO shared with the same user
 * produces BOTH an {@code AddedNode} (into the destination) and a {@code RemovedNode} (out of the
 * source) notification. Seeding (API calls capturing server-generated ids) and transport changed;
 * the scenario and assertion are otherwise preserved verbatim.
 */
class AddedAndRemovedNodeNotificationMoveApiIT extends AbstractFilesIT {

  private static final String OWNER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String SECOND_USER_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String OWNER_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String SECOND_USER_COOKIE = "ZM_AUTH_TOKEN=fake-token-b";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", OWNER_ID);
    FilesStackTestResource.getUserManagementService().registerToken("fake-token-b", SECOND_USER_ID);
  }

  /**
   * Two folders, BOTH shared with the second user; a node created inside the second folder (1
   * AddedNode, since it's already shared) is then moved into the first (1 RemovedNode from the
   * second + 1 AddedNode into the first).
   */
  private static void createBaseScenario() {
    String folderAId = seedFolder("folderA", LOCAL_ROOT, OWNER_COOKIE);
    String folderBId = seedFolder("folderB", LOCAL_ROOT, OWNER_COOKIE);

    seedShare(folderAId, SECOND_USER_ID, ACL.SharePermission.READ_AND_SHARE, OWNER_COOKIE);
    seedShare(folderBId, SECOND_USER_ID, ACL.SharePermission.READ_AND_SHARE, OWNER_COOKIE);

    String nodeId = seedFolder("other_folder", folderBId, OWNER_COOKIE);

    String moveMutation =
        GraphqlCommandBuilder.aMutationBuilder("moveNodes")
            .withListOfStrings("node_ids", new String[] {nodeId})
            .withString("destination_id", folderAId)
            .withWantedResultFormat("{ id }")
            .build();
    graphql(moveMutation, OWNER_COOKIE);
  }

  private static Map<String, Object> getNotifications(String cookie) {
    String query =
        GraphqlCommandBuilder.aQueryBuilder("getNotifications")
            .withBoolean("update_last_seen", true)
            .withWantedResultFormat(
                "{ notifications { ... on RemovedNode { created_at }, ... on AddedNode { created_at"
                    + " }, ... on NewShare { created_at } } }")
            .build();
    Response response = graphql(query, cookie);
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    return TestUtils.jsonResponseToMap(response.getBody().asString(), "getNotifications");
  }

  @Test
  void
      givenANodeMovedFromOneSharedDirectoryToAnotherSharedDirectoryGetNotificationsShouldReturnAddedAndRemovedNodeNotifications() {
    // Given
    createBaseScenario();

    // When
    Map<String, Object> page = getNotifications(SECOND_USER_COOKIE);

    // Then — two newShare for the two directories, one AddedNode for the node creation, and two
    // (AddedNode + RemovedNode = MOVE) for the move operation from the first shared dir to the
    // second one
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> notifications = (List<Map<String, Object>>) page.get("notifications");
    Assertions.assertThat(notifications).hasSize(5);
  }
}
