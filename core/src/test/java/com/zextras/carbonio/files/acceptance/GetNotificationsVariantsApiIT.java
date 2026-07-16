// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.acceptance;

import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.acceptance.seam.FilesTestApp;
import com.zextras.carbonio.files.acceptance.seam.impl.GuiceNettyFilesTestAppBuilder;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.api.utilities.entities.SimplePopulatorTextFile;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
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
 * Task 1.5 (part 3) of the acceptance coverage-expansion plan: direct coverage of {@code
 * getNotifications} itself (as opposed to the existing notification ITs, which only assert that a
 * notification was created as a side effect of some other mutation).
 *
 * <p><b>FINDING (overrides the plan's brief):</b> the plan asked for "not-found/permission/
 * validation variants (none exist today)". {@code NotificationDataFetcher#getNotificationsFetcher}
 * has NONE of these three kinds of branches at all:
 *
 * <ul>
 *   <li>no permission concept — the query is entirely scoped to the requester's own notifications,
 *       there is no target id to be denied access to;
 *   <li>no schema field-validator is bound to {@code getNotifications} in {@code
 *       GraphQLProvider#buildValidationInstrumentation} (unlike {@code findNodes}/{@code
 *       getNode.children}), so no custom validation error can ever fire;
 *   <li>an invalid/unknown {@code page_token} is NOT a not-found error: {@code
 *       NotificationRepositoryEbean#doFind} looks up the token's {@code UserNotificationInterest}
 *       row and, when absent, silently falls back to an empty notification id list — {@code
 *       getNotifications} returns 200 with an empty {@code notifications} array, never an error.
 * </ul>
 *
 * <p>Given there is no error surface to exercise, this file instead pins the real reachable
 * "variant" behaviour: first-time-user fallback, the {@code update_last_seen} unread/last_seen
 * side effects (including the not-entirely-obvious fact that the CURRENT call's response reports
 * the counters as they were BEFORE this call's own reset), pagination via {@code page_token}
 * (including the exactly-at-the-limit case), and the silently-empty garbage-token case above.
 */
class GetNotificationsVariantsApiIT {

  static FilesTestApp app;

  private static final String RECEIVER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String SHARER_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String FRESH_USER_ID = "cccccccc-cccc-cccc-cccc-cccccccccccc";

  @BeforeAll
  static void init() {
    app =
        GuiceNettyFilesTestAppBuilder.aFilesTestApp()
            .withDatabase()
            .withServiceDiscover()
            .withUserManagement(
                Map.of(
                    "fake-token", RECEIVER_ID,
                    "fake-token-sharer", SHARER_ID,
                    "fake-token-fresh", FRESH_USER_ID))
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

  /** Shares a fresh node from {@code SHARER_ID} to {@code RECEIVER_ID}, creating one NewShare notification. */
  private void shareANewNodeWithReceiver(String nodeId) {
    app.backdoor()
        .populator()
        .addNode(new SimplePopulatorTextFile(nodeId, SHARER_ID, nodeId.substring(0, 8) + ".txt"));

    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("createShare")
            .withString("node_id", nodeId)
            .withString("share_target_id", RECEIVER_ID)
            .withEnum("permission", ACL.SharePermission.READ_ONLY)
            .withWantedResultFormat("{ created_at }")
            .build();
    HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token-sharer", bodyPayload);
    HttpResponse httpResponse = app.send(httpRequest);
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload())).isEmpty();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> getNotifications(
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
    String bodyPayload =
        builder
            .withWantedResultFormat(
                "{ notifications { ... on NewShare { id } }, unread, last_seen, page_token }")
            .build();

    HttpRequest httpRequest = HttpRequest.of("POST", "/graphql/", cookie, bodyPayload);
    HttpResponse httpResponse = app.send(httpRequest);
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload())).isEmpty();

    return TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "getNotifications");
  }

  @Test
  void givenAUserWithNoPriorNotificationsInfoGetNotificationsFallsBackToZeroUnreadAndZeroLastSeen() {
    // When — FRESH_USER_ID has never received a notification and has no UserNotificationsInfo row
    Map<String, Object> page = getNotifications(false, null, null, "ZM_AUTH_TOKEN=fake-token-fresh");

    // Then
    Assertions.assertThat((List<Object>) page.get("notifications")).isEmpty();
    Assertions.assertThat(page).containsEntry("unread", 0).containsEntry("last_seen", 0);
    Assertions.assertThat(page.get("page_token")).isNull();
  }

  @Test
  void givenUnreadNotificationsUpdateLastSeenTrueReturnsThePreResetCountThenResetsForTheNextCall() {
    // Given — two notifications waiting, unread counter is 2
    shareANewNodeWithReceiver("00000000-0000-0000-0000-000000000001");
    shareANewNodeWithReceiver("00000000-0000-0000-0000-000000000002");

    // When — first call with update_last_seen: true
    Map<String, Object> firstCall = getNotifications(true, null, null, "ZM_AUTH_TOKEN=fake-token");

    // Then — THIS response reports the counter as it was BEFORE this call's own reset
    Assertions.assertThat((List<Object>) firstCall.get("notifications")).hasSize(2);
    Assertions.assertThat(firstCall).containsEntry("unread", 2);

    // When — a second call, with no new notifications in between
    Map<String, Object> secondCall = getNotifications(true, null, null, "ZM_AUTH_TOKEN=fake-token");

    // Then — now the counter reflects the reset performed by the first call
    Assertions.assertThat(secondCall).containsEntry("unread", 0);
  }

  @Test
  void givenUpdateLastSeenFalseTheUnreadCounterIsNeverReset() {
    // Given
    shareANewNodeWithReceiver("00000000-0000-0000-0000-000000000001");

    // When
    Map<String, Object> firstCall = getNotifications(false, null, null, "ZM_AUTH_TOKEN=fake-token");
    Map<String, Object> secondCall = getNotifications(false, null, null, "ZM_AUTH_TOKEN=fake-token");

    // Then — unread stays at 1 across repeated update_last_seen: false calls
    Assertions.assertThat(firstCall).containsEntry("unread", 1);
    Assertions.assertThat(secondCall).containsEntry("unread", 1);
  }

  @Test
  void givenMoreNotificationsThanTheLimitPaginationReturnsAPageTokenThenTheRemainder() {
    // Given — 3 notifications, page size 2
    shareANewNodeWithReceiver("00000000-0000-0000-0000-000000000001");
    shareANewNodeWithReceiver("00000000-0000-0000-0000-000000000002");
    shareANewNodeWithReceiver("00000000-0000-0000-0000-000000000003");

    // When — first page
    Map<String, Object> firstPage = getNotifications(false, 2, null, "ZM_AUTH_TOKEN=fake-token");
    List<Map<String, Object>> firstPageNotifications =
        (List<Map<String, Object>>) firstPage.get("notifications");

    // Then — exactly at the limit still returns a (possibly "useless") page_token
    Assertions.assertThat(firstPageNotifications).hasSize(2);
    Assertions.assertThat(firstPage.get("page_token")).isNotNull();

    // When — second page, using the returned token
    String pageToken = (String) firstPage.get("page_token");
    Map<String, Object> secondPage = getNotifications(false, 2, pageToken, "ZM_AUTH_TOKEN=fake-token");
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
    shareANewNodeWithReceiver("00000000-0000-0000-0000-000000000001");

    // When
    Map<String, Object> page =
        getNotifications(false, null, "not-a-real-page-token", "ZM_AUTH_TOKEN=fake-token");

    // Then
    Assertions.assertThat((List<Object>) page.get("notifications")).isEmpty();
    Assertions.assertThat(page.get("page_token")).isNull();
  }
}
