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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Config-split sibling of {@link NewShareNotificationApiIT} (Batch I / D3): carries the ONE
 * scenario that needs notifications disabled ({@link NotificationsDisabledResource},
 * class-restricted).
 */
@WithTestResource(
    value = NotificationsDisabledResource.class,
    scope = TestResourceScope.RESTRICTED_TO_CLASS)
class NewShareNotificationDisabledIT extends AbstractFilesIT {

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
  void givenANodeCreatingAndDisabledNotificationsNoNotificationShouldBeSavedOrReturned() {
    // Given
    String nodeId =
        seedFile("name.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    seedShare(nodeId, SECOND_USER_ID, ACL.SharePermission.READ_AND_SHARE, OWNER_COOKIE);

    // When
    String query =
        GraphqlCommandBuilder.aQueryBuilder("getNotifications")
            .withBoolean("update_last_seen", true)
            .withWantedResultFormat("{ notifications { ... on NewShare { created_at } } }")
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
