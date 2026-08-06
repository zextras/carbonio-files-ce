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
 * {@code com.zextras.carbonio.files.acceptance.RemovedNodeNotificationTrashApiIT} rewritten as an
 * out-of-process {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}: trashing a node that
 * sits inside a directory shared with another user notifies that user ({@code RemovedNode} +, from
 * the share itself, {@code NewShare}). Only the enabled-notifications method is here — the seam's
 * {@code setNotificationsEnabled(false)} scenario is split into the sibling {@link
 * RemovedNodeNotificationTrashDisabledIT}. Seeding (API calls capturing server-generated ids,
 * including {@link #seedTrashed} for the trash action) and transport changed; the scenario and
 * assertion are otherwise preserved verbatim. The share is created AFTER the node, so node creation
 * itself never generates an AddedNode notification — only the share (NewShare) and the subsequent
 * trashing (RemovedNode).
 */
class RemovedNodeNotificationTrashApiIT extends AbstractFilesIT {

  private static final String OWNER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String SECOND_USER_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String OWNER_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String SECOND_USER_COOKIE = "ZM_AUTH_TOKEN=fake-token-b";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", OWNER_ID);
    FilesStackTestResource.getUserManagementService().registerToken("fake-token-b", SECOND_USER_ID);
  }

  /** Creates a folder with a node inside, THEN shares the folder, THEN trashes the node. */
  private static void createBaseScenario() {
    String sharedFolderId = seedFolder("folder", LOCAL_ROOT, OWNER_COOKIE);
    String nodeId = seedFolder("other_folder", sharedFolderId, OWNER_COOKIE);

    seedShare(sharedFolderId, SECOND_USER_ID, ACL.SharePermission.READ_AND_SHARE, OWNER_COOKIE);

    seedTrashed(nodeId, OWNER_COOKIE);
  }

  private static Map<String, Object> getNotifications(String cookie) {
    String query =
        GraphqlCommandBuilder.aQueryBuilder("getNotifications")
            .withBoolean("update_last_seen", true)
            .withWantedResultFormat(
                "{ notifications { ... on RemovedNode { created_at }, ... on NewShare { created_at"
                    + " } } }")
            .build();
    Response response = graphql(query, cookie);
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    return TestUtils.jsonResponseToMap(response.getBody().asString(), "getNotifications");
  }

  @Test
  void
      givenANodeRemovalByTrashingItOnASharedDirectoryItShouldCreateANotificationForTheUsersItHasBeenSharedWith() {
    // Given
    createBaseScenario();

    // When
    Map<String, Object> page = getNotifications(SECOND_USER_COOKIE);

    // Then
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> notifications = (List<Map<String, Object>>) page.get("notifications");
    Assertions.assertThat(notifications).hasSize(2); // One newShare and one removedNode
  }
}
