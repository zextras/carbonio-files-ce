// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import com.zextras.carbonio.files.it.support.config.NotificationsDisabledResource;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.restassured.response.Response;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Config-split sibling of {@link RemovedNodeNotificationMoveApiIT} (Batch I / D3): carries the ONE
 * scenario that needs notifications disabled ({@link NotificationsDisabledResource},
 * class-restricted).
 */
@WithTestResource(
    value = NotificationsDisabledResource.class,
    scope = TestResourceScope.RESTRICTED_TO_CLASS)
class RemovedNodeNotificationMoveDisabledIT extends AbstractFilesIT {

  private static final String OWNER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String SECOND_USER_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String OWNER_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String SECOND_USER_COOKIE = "ZM_AUTH_TOKEN=fake-token-b";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", OWNER_ID);
    FilesStackTestResource.getUserManagementService().registerToken("fake-token-b", SECOND_USER_ID);
  }

  @Test
  void
      givenANodeRemovalByMoveOnASharedDirectoryAndDisabledNotificationsNoNotificationShouldBeSavedOrReturned() {
    // Given
    String sharedFolderId = seedFolder("folder", LOCAL_ROOT, OWNER_COOKIE);
    String nodeId = seedFolder("other_folder", sharedFolderId, OWNER_COOKIE);
    seedShare(sharedFolderId, SECOND_USER_ID, ACL.SharePermission.READ_AND_SHARE, OWNER_COOKIE);

    String moveMutation =
        GraphqlCommandBuilder.aMutationBuilder("moveNodes")
            .withListOfStrings("node_ids", new String[] {nodeId})
            .withString("destination_id", LOCAL_ROOT)
            .withWantedResultFormat("{ id }")
            .build();
    Response moveResponse = graphql(moveMutation, OWNER_COOKIE);
    Assertions.assertThat(moveResponse.getStatusCode()).isEqualTo(200);

    // When
    String query =
        GraphqlCommandBuilder.aQueryBuilder("getNotifications")
            .withBoolean("update_last_seen", true)
            .withWantedResultFormat(
                "{ notifications { ... on RemovedNode { created_at }, ... on NewShare { created_at"
                    + " } } }")
            .build();
    Response response = graphql(query, SECOND_USER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Map<String, Object> page =
        TestUtils.jsonResponseToMap(response.getBody().asString(), "getNotifications");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> notifications = (List<Map<String, Object>>) page.get("notifications");
    Assertions.assertThat(notifications).hasSize(0);
  }
}
