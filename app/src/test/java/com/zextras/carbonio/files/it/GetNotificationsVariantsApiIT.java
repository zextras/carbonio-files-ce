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
 * {@code com.zextras.carbonio.files.acceptance.GetNotificationsVariantsApiIT} rewritten as an
 * out-of-process {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}: direct coverage of
 * {@code getNotifications} itself (as opposed to the other notification ITs, which only assert
 * that a notification was created as a side effect of some other mutation). All 5 methods and
 * their assertions are preserved verbatim; only the seeding (API calls capturing server-generated
 * ids) and transport changed.
 *
 * <p><b>FINDING (carried over):</b> {@code NotificationDataFetcher#getNotificationsFetcher} has no
 * permission concept (the query is entirely scoped to the requester's own notifications), no bound
 * schema validator, and treats an invalid/unknown {@code page_token} as a silent empty-page
 * fallback rather than an error ({@code NotificationRepositoryEbean#doFind} falls back to an empty
 * notification id list when the token's {@code UserNotificationInterest} row is absent). This
 * class pins the real reachable "variant" behaviour instead: first-time-user fallback, the {@code
 * update_last_seen} unread/last_seen side effects (including the not-entirely-obvious fact that
 * the CURRENT call's response reports the counters as they were BEFORE this call's own reset),
 * pagination via {@code page_token} (including the exactly-at-the-limit case), and the
 * silently-empty garbage-token case above.
 */
class GetNotificationsVariantsApiIT extends AbstractFilesIT {

  private static final String RECEIVER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String SHARER_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String FRESH_USER_ID = "cccccccc-cccc-cccc-cccc-cccccccccccc";
  private static final String RECEIVER_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String SHARER_COOKIE = "ZM_AUTH_TOKEN=fake-token-b";
  private static final String FRESH_USER_COOKIE = "ZM_AUTH_TOKEN=fake-token-c";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", RECEIVER_ID);
    FilesStackTestResource.getUserManagementService().registerToken("fake-token-b", SHARER_ID);
    FilesStackTestResource.getUserManagementService().registerToken("fake-token-c", FRESH_USER_ID);
  }

  /** Shares a fresh node from {@code SHARER_ID} to {@code RECEIVER_ID}, creating one NewShare notification. */
  private static void shareANewNodeWithReceiver() {
    String nodeId =
        seedFile("shared.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), SHARER_COOKIE);
    seedShare(nodeId, RECEIVER_ID, ACL.SharePermission.READ_ONLY, SHARER_COOKIE);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> getNotifications(
      boolean updateLastSeen, Integer limit, String pageToken, String cookie) {
    GraphqlCommandBuilder builder =
        GraphqlCommandBuilder.aQueryBuilder("getNotifications")
            .withBoolean("update_last_seen", updateLastSeen);
    if (limit != null) {
      builder.withInteger("limit", limit);
    }
    if (pageToken != null) {
      builder.withString("page_token", pageToken);
    }
    String query =
        builder
            .withWantedResultFormat(
                "{ notifications { ... on NewShare { id } }, unread, last_seen, page_token }")
            .build();

    Response response = graphql(query, cookie);
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(response.getBody().asString())).isEmpty();

    return TestUtils.jsonResponseToMap(response.getBody().asString(), "getNotifications");
  }

  @Test
  void givenAUserWithNoPriorNotificationsInfoGetNotificationsFallsBackToZeroUnreadAndZeroLastSeen() {
    // When — FRESH_USER_ID has never received a notification and has no UserNotificationsInfo row
    Map<String, Object> page = getNotifications(false, null, null, FRESH_USER_COOKIE);

    // Then
    Assertions.assertThat((List<Object>) page.get("notifications")).isEmpty();
    Assertions.assertThat(page).containsEntry("unread", 0).containsEntry("last_seen", 0);
    Assertions.assertThat(page.get("page_token")).isNull();
  }

  @Test
  void givenUnreadNotificationsUpdateLastSeenTrueReturnsThePreResetCountThenResetsForTheNextCall() {
    // Given — two notifications waiting, unread counter is 2
    shareANewNodeWithReceiver();
    shareANewNodeWithReceiver();

    // When — first call with update_last_seen: true
    Map<String, Object> firstCall = getNotifications(true, null, null, RECEIVER_COOKIE);

    // Then — THIS response reports the counter as it was BEFORE this call's own reset
    Assertions.assertThat((List<Object>) firstCall.get("notifications")).hasSize(2);
    Assertions.assertThat(firstCall).containsEntry("unread", 2);

    // When — a second call, with no new notifications in between
    Map<String, Object> secondCall = getNotifications(true, null, null, RECEIVER_COOKIE);

    // Then — now the counter reflects the reset performed by the first call
    Assertions.assertThat(secondCall).containsEntry("unread", 0);
  }

  @Test
  void givenUpdateLastSeenFalseTheUnreadCounterIsNeverReset() {
    // Given
    shareANewNodeWithReceiver();

    // When
    Map<String, Object> firstCall = getNotifications(false, null, null, RECEIVER_COOKIE);
    Map<String, Object> secondCall = getNotifications(false, null, null, RECEIVER_COOKIE);

    // Then — unread stays at 1 across repeated update_last_seen: false calls
    Assertions.assertThat(firstCall).containsEntry("unread", 1);
    Assertions.assertThat(secondCall).containsEntry("unread", 1);
  }

  @Test
  void givenMoreNotificationsThanTheLimitPaginationReturnsAPageTokenThenTheRemainder() {
    // Given — 3 notifications, page size 2
    shareANewNodeWithReceiver();
    shareANewNodeWithReceiver();
    shareANewNodeWithReceiver();

    // When — first page
    Map<String, Object> firstPage = getNotifications(false, 2, null, RECEIVER_COOKIE);
    List<Map<String, Object>> firstPageNotifications =
        (List<Map<String, Object>>) firstPage.get("notifications");

    // Then — exactly at the limit still returns a (possibly "useless") page_token
    Assertions.assertThat(firstPageNotifications).hasSize(2);
    Assertions.assertThat(firstPage.get("page_token")).isNotNull();

    // When — second page, using the returned token
    String pageToken = (String) firstPage.get("page_token");
    Map<String, Object> secondPage = getNotifications(false, 2, pageToken, RECEIVER_COOKIE);
    List<Map<String, Object>> secondPageNotifications =
        (List<Map<String, Object>>) secondPage.get("notifications");

    // Then — the remaining single notification, and no further page
    Assertions.assertThat(secondPageNotifications).hasSize(1);
    Assertions.assertThat(secondPage.get("page_token")).isNull();

    // and no notification id is repeated across the two pages
    List<String> firstIds = firstPageNotifications.stream().map(n -> (String) n.get("id")).toList();
    List<String> secondIds = secondPageNotifications.stream().map(n -> (String) n.get("id")).toList();
    Assertions.assertThat(firstIds).doesNotContainAnyElementsOf(secondIds);
  }

  /**
   * See the class-level FINDING: an unrecognised {@code page_token} is swallowed silently rather
   * than surfaced as an error.
   */
  @Test
  void givenAGarbagePageTokenGetNotificationsSilentlyReturnsAnEmptyPageInsteadOfAnError() {
    // Given
    shareANewNodeWithReceiver();

    // When
    Map<String, Object> page =
        getNotifications(false, null, "not-a-real-page-token", RECEIVER_COOKIE);

    // Then
    Assertions.assertThat((List<Object>) page.get("notifications")).isEmpty();
    Assertions.assertThat(page.get("page_token")).isNull();
  }
}
