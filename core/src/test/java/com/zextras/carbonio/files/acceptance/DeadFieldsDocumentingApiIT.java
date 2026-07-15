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
 * Task 5.3 (APPROVED, plan §10) of the acceptance coverage-expansion plan: documents four
 * dead/no-op schema surfaces so the current, actual behaviour is pinned as a contract the Quarkus
 * rewrite must either preserve or deliberately change. None of these branches are reachable-but-
 * untested application logic — they are genuinely dead or no-op, so these tests add no branch
 * coverage; they exist purely to lock in behaviour (plan §10 decision 3) and to double as the §9
 * findings below.
 *
 * <h2>Findings documented here</h2>
 *
 * <ul>
 *   <li><b>{@code getUserById} has no bound resolver.</b> {@code GraphQLProvider} wires {@code
 *       Constants.GraphQL.Queries.GET_USER} (the string {@code "getUser"}) to {@code
 *       UserDataFetcher#getUserFetcher}, but the schema's query is named {@code getUserById} —
 *       confirmed by reading both {@code GraphQLProvider} and {@code schema.graphql} directly.
 *       There is no {@code GET_USER_BY_ID} constant at all. Consequently {@code getUserById} falls
 *       through to graphql-java's default {@code PropertyDataFetcher} against the (null) root
 *       Query source object, which always returns {@code null} for ANY {@code user_id} value —
 *       and since NO {@code InputFieldsController} rule is bound to this field either, not even a
 *       malformed/empty id trips a validation error first. Likely a real, shipped bug (a
 *       find-and-replace or copy-paste slip when the query was renamed from {@code getUser} to
 *       {@code getUserById}).
 *   <li><b>{@code Node.share(share_target_id)} has no bound resolver</b> on {@code File}/{@code
 *       Folder} either (confirmed against {@code GraphQLProvider}'s {@code FILE}/{@code FOLDER}
 *       type wirings, which bind {@code creator/owner/last_editor/parent/permissions/shares/links/
 *       collaboration_links} but never {@code share}) — same default-{@code PropertyDataFetcher}-
 *       on-a-{@code Map}-without-that-key fallthrough, always {@code null}, no validation, no
 *       matter the argument.
 *   <li><b>{@code Share.sorts} is a no-op.</b> {@code ShareDataFetcher#getSharesFetcher} never
 *       calls {@code environment.getArgument(...SORTS...)} at all (confirmed by reading the method
 *       body, which carries an explicit {@code // TODO: At the moment the sorting is not
 *       supported} comment) — passing any {@code sorts} value changes nothing.
 *   <li><b>The {@code DistributionList} union member is never constructed.</b> {@code
 *       Constants.GraphQL.ENTITY_TYPE} is set to {@code Types.USER} in {@code
 *       UserDataFetcher#convertUserToDataFetcherResult} and NOWHERE in the whole {@code
 *       src/main} tree is it ever set to {@code Types.DISTRIBUTION_LIST} (confirmed by a full-tree
 *       grep) — so {@code getAccountTypeResolver}'s {@code DistributionList} branch is unreachable
 *       dead code, on both union fields that use it ({@code Account}: {@code getAccountByEmail}/
 *       {@code getAccountsByEmail}; {@code SharedTarget}: {@code Share.share_target}).
 * </ul>
 */
class DeadFieldsDocumentingApiIT {

  static FilesTestApp app;

  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String REQUESTER_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String TARGET_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String TARGET_C = "cccccccc-cccc-cccc-cccc-cccccccccccc";
  private static final String TARGET_D = "dddddddd-dddd-dddd-dddd-dddddddddddd";

  @BeforeAll
  static void init() {
    app =
        GuiceNettyFilesTestAppBuilder.aFilesTestApp()
            .withDatabase()
            .withServiceDiscover()
            .withUserManagement(
                Map.of(
                    "fake-token", REQUESTER_ID,
                    "fake-token-b", TARGET_B,
                    "fake-token-c", TARGET_C,
                    "fake-token-d", TARGET_D))
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

  private HttpResponse execute(String bodyPayload) {
    return app.send(HttpRequest.of("POST", "/graphql/", REQUESTER_COOKIE, bodyPayload));
  }

  @Test
  void givenAnyUserIdGetUserByIdShouldAlwaysReturnNullWithNoError() {
    // Given — a syntactically valid-looking id and an obviously invalid one; the finding is that
    // NEITHER path is even reachable, so both behave identically.
    String validLookingId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    String invalidId = "";

    for (String userId : List.of(validLookingId, invalidId)) {
      String bodyPayload =
          GraphqlCommandBuilder.aQueryBuilder("getUserById")
              .withString("user_id", userId)
              .withWantedResultFormat("{ id email full_name }")
              .build();

      // When
      HttpResponse httpResponse = execute(bodyPayload);

      // Then
      Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
      Assertions.assertThat(TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload())).isEmpty();
      Assertions.assertThat(
              TestUtils.jsonResponseToValue(httpResponse.getBodyPayload(), "getUserById"))
          .isEmpty();
    }
  }

  @Test
  void givenAnyShareTargetIdNodeShareFieldShouldAlwaysReturnNull() {
    // Given — a node the requester owns (so getNode itself succeeds trivially), and three
    // meaningfully different share_target_id combinations: the requester's own id, a registered
    // other user, and an id that resolves to nobody at all.
    String nodeId = "00000000-0000-0000-0000-000000000001";
    app.backdoor().populator().addNode(new SimplePopulatorTextFile(nodeId, REQUESTER_ID));

    for (String shareTargetId : List.of(REQUESTER_ID, TARGET_B, "ffffffff-ffff-ffff-ffff-ffffffffffff")) {
      String bodyPayload =
          GraphqlCommandBuilder.aQueryBuilder("getNode")
              .withString("node_id", nodeId)
              .withWantedResultFormat(
                  "{ id share(share_target_id: \\\"" + shareTargetId + "\\\") { permission } }")
              .build();

      // When
      HttpResponse httpResponse = execute(bodyPayload);

      // Then
      Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
      Assertions.assertThat(TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload())).isEmpty();
      Map<String, Object> node = TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "getNode");
      Assertions.assertThat(node).containsEntry("id", nodeId);
      Assertions.assertThat(node.get("share")).isNull();
    }
  }

  @SuppressWarnings("unchecked")
  @Test
  void givenSortsArgumentSharesOrderShouldBeIdenticalToOmittingIt() {
    // Given — one node shared with three distinct, distinguishable targets, inserted in a fixed
    // order (B, then C, then D; DatabasePopulator delays between inserts to guarantee distinct
    // timestamps).
    String nodeId = "00000000-0000-0000-0000-000000000002";
    app.backdoor()
        .populator()
        .addNode(new SimplePopulatorTextFile(nodeId, REQUESTER_ID))
        .addShare(nodeId, TARGET_B, ACL.SharePermission.READ_ONLY)
        .addShare(nodeId, TARGET_C, ACL.SharePermission.READ_ONLY)
        .addShare(nodeId, TARGET_D, ACL.SharePermission.READ_ONLY);

    String withSortsPayload =
        GraphqlCommandBuilder.aQueryBuilder("getNode")
            .withString("node_id", nodeId)
            .withWantedResultFormat(
                "{ shares(limit: 50, sorts: [CREATION_DESC]) { share_target { ... on User { id } } } }")
            .build();
    String withoutSortsPayload =
        GraphqlCommandBuilder.aQueryBuilder("getNode")
            .withString("node_id", nodeId)
            .withWantedResultFormat("{ shares(limit: 50) { share_target { ... on User { id } } } }")
            .build();

    // When
    HttpResponse withSortsResponse = execute(withSortsPayload);
    HttpResponse withoutSortsResponse = execute(withoutSortsPayload);

    // Then — both must be error-free (all three targets are registered UM users) and, crucially,
    // the ORDER of target ids must be IDENTICAL between the two calls: `sorts` changed nothing.
    Assertions.assertThat(TestUtils.jsonResponseToErrors(withSortsResponse.getBodyPayload())).isEmpty();
    Assertions.assertThat(TestUtils.jsonResponseToErrors(withoutSortsResponse.getBodyPayload())).isEmpty();

    List<String> withSortsOrder = shareTargetIds(withSortsResponse);
    List<String> withoutSortsOrder = shareTargetIds(withoutSortsResponse);

    Assertions.assertThat(withSortsOrder).containsExactlyInAnyOrder(TARGET_B, TARGET_C, TARGET_D);
    Assertions.assertThat(withSortsOrder).isEqualTo(withoutSortsOrder);
  }

  @SuppressWarnings("unchecked")
  private List<String> shareTargetIds(HttpResponse httpResponse) {
    Map<String, Object> node = TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "getNode");
    List<Map<String, Object>> shares = (List<Map<String, Object>>) node.get("shares");
    return shares.stream()
        .map(share -> (Map<String, Object>) share.get("share_target"))
        .map(shareTarget -> (String) shareTarget.get("id"))
        .toList();
  }

  @Test
  void givenTheOnlyTwoUnionFieldsInTheSchemaNeitherEverResolvesToADistributionList() {
    // Given — the Account union (getAccountByEmail) and the SharedTarget union (Share.share_target)
    // are the ONLY two places in the schema a DistributionList could ever appear. Both share the
    // exact same type resolver (UserDataFetcher#getAccountTypeResolver), which is unconditionally
    // wired to Types.USER by every account-producing code path (confirmed by a whole-tree grep: the
    // DISTRIBUTION_LIST literal is never assigned to ENTITY_TYPE anywhere in src/main).
    String nodeId = "00000000-0000-0000-0000-000000000003";
    app.backdoor()
        .populator()
        .addNode(new SimplePopulatorTextFile(nodeId, REQUESTER_ID))
        .addShare(nodeId, TARGET_B, ACL.SharePermission.READ_ONLY);

    String accountByEmailPayload =
        GraphqlCommandBuilder.aQueryBuilder("getAccountByEmail")
            .withString("email", "fake-email@example.com")
            .withWantedResultFormat(
                "{ __typename ... on User { id } ... on DistributionList { id name } }")
            .build();
    String shareTargetPayload =
        GraphqlCommandBuilder.aQueryBuilder("getNode")
            .withString("node_id", nodeId)
            .withWantedResultFormat(
                "{ shares(limit: 10) { share_target { __typename ... on User { id } "
                    + "... on DistributionList { id name } } } }")
            .build();

    // When
    HttpResponse accountByEmailResponse = execute(accountByEmailPayload);
    HttpResponse shareTargetResponse = execute(shareTargetPayload);

    // Then — Account union: always User
    Assertions.assertThat(TestUtils.jsonResponseToErrors(accountByEmailResponse.getBodyPayload())).isEmpty();
    Map<String, Object> account =
        TestUtils.jsonResponseToMap(accountByEmailResponse.getBodyPayload(), "getAccountByEmail");
    Assertions.assertThat(account).containsEntry("__typename", "User");

    // Then — SharedTarget union: always User
    Assertions.assertThat(TestUtils.jsonResponseToErrors(shareTargetResponse.getBodyPayload())).isEmpty();
    Map<String, Object> node = TestUtils.jsonResponseToMap(shareTargetResponse.getBodyPayload(), "getNode");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> shares = (List<Map<String, Object>>) node.get("shares");
    Assertions.assertThat(shares).hasSize(1);
    @SuppressWarnings("unchecked")
    Map<String, Object> shareTarget = (Map<String, Object>) shares.get(0).get("share_target");
    Assertions.assertThat(shareTarget).containsEntry("__typename", "User");
  }
}
