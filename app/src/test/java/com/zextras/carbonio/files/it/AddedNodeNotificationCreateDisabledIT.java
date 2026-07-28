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
 * Config-split sibling of {@link AddedNodeNotificationCreateApiIT} (Batch I / D3): carries the ONE
 * scenario that needs notifications disabled ({@link NotificationsDisabledResource},
 * class-restricted) — {@code networking-config.carbonio.files.enable-notifications} is a
 * boot-time snapshot for the WHOLE launched process, not settable per-method.
 */
@WithTestResource(
    value = NotificationsDisabledResource.class,
    scope = TestResourceScope.RESTRICTED_TO_CLASS)
class AddedNodeNotificationCreateDisabledIT extends AbstractFilesIT {

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
  void givenANodeCreationOnASharedDirectoryAndDisabledNotificationsNoNotificationShouldBeSavedOrReturned() {
    // Given
    String folderId = seedFolder("folder", LOCAL_ROOT, OWNER_COOKIE);
    seedShare(folderId, SECOND_USER_ID, ACL.SharePermission.READ_AND_SHARE, OWNER_COOKIE);

    String createMutation =
        GraphqlCommandBuilder.aMutationBuilder("createFolder")
            .withString("destination_id", folderId)
            .withString("name", "other_folder")
            .withWantedResultFormat("{ id }")
            .build();
    graphql(createMutation, OWNER_COOKIE);

    // When
    String query =
        GraphqlCommandBuilder.aQueryBuilder("getNotifications")
            .withBoolean("update_last_seen", true)
            .withWantedResultFormat(
                "{ notifications { ... on AddedNode { created_at }, ... on NewShare { created_at } } }")
            .build();
    Response response = graphql(query, SECOND_USER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Map<String, Object> page = TestUtils.jsonResponseToMap(response.getBody().asString(), "getNotifications");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> notifications = (List<Map<String, Object>>) page.get("notifications");
    Assertions.assertThat(notifications).hasSize(0);
  }
}
