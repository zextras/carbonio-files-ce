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
 * {@code com.zextras.carbonio.files.acceptance.DeadFieldsDocumentingApiIT} rewritten as an
 * out-of-process {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}. Documents four
 * dead/no-op schema surfaces so the current, actual behaviour is pinned as a contract: none of
 * these branches are reachable-but-untested application logic — they are genuinely dead or no-op,
 * so these tests add no branch coverage; they exist purely to lock in behaviour.
 *
 * <h2>Findings documented here</h2>
 *
 * <ul>
 *   <li><b>{@code getUserById} has no bound resolver.</b> {@code GraphQLProvider} wires {@code
 *       Constants.GraphQL.Queries.GET_USER} (the string {@code "getUser"}) to {@code
 *       UserDataFetcher#getUserFetcher}, but the schema's query is named {@code getUserById} —
 *       there is no {@code GET_USER_BY_ID} constant at all. Consequently {@code getUserById} falls
 *       through to graphql-java's default {@code PropertyDataFetcher} against the (null) root
 *       Query source object, which always returns {@code null} for ANY {@code user_id} value —
 *       and since NO {@code InputFieldsController} rule is bound to this field either, not even a
 *       malformed/empty id trips a validation error first.
 *   <li><b>{@code Node.share(share_target_id)} has no bound resolver</b> on {@code File}/{@code
 *       Folder} either — same default-{@code PropertyDataFetcher}-on-a-{@code Map}-without-that-key
 *       fallthrough, always {@code null}, no validation, no matter the argument.
 *   <li><b>{@code Share.sorts} is a no-op.</b> {@code ShareDataFetcher#getSharesFetcher} never
 *       calls {@code environment.getArgument(...SORTS...)} at all — passing any {@code sorts}
 *       value changes nothing.
 *   <li><b>The {@code DistributionList} union member is never constructed.</b> {@code
 *       Constants.GraphQL.ENTITY_TYPE} is set to {@code Types.USER} in {@code
 *       UserDataFetcher#convertUserToDataFetcherResult} and NOWHERE in the whole {@code src/main}
 *       tree is it ever set to {@code Types.DISTRIBUTION_LIST} — so {@code
 *       getAccountTypeResolver}'s {@code DistributionList} branch is unreachable dead code, on both
 *       union fields that use it ({@code Account}: {@code getAccountByEmail}; {@code
 *       SharedTarget}: {@code Share.share_target}).
 * </ul>
 *
 * <p>All 4 methods and their assertions are preserved verbatim; only the seeding (API calls
 * capturing server-generated ids, replacing the 12 {@code DatabasePopulator} calls) and transport
 * changed.
 */
class DeadFieldsDocumentingApiIT extends AbstractFilesIT {

  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String REQUESTER_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String TARGET_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String TARGET_C = "cccccccc-cccc-cccc-cccc-cccccccccccc";
  // NOTE: NOT "dddd...dddd"/"eeee...eeee" — those two ids are deliberately claimed elsewhere in
  // the suite (UserResolversApiIT's ghost creator/owner ids, which must stay UNREGISTERED with
  // user-management for the whole suite run, since MockUserManagementService is a global
  // singleton); registering either here as a real user broke that class's "unresolvable" fixture.
  private static final String TARGET_D = "66666666-6666-6666-6666-666666666666";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", REQUESTER_ID);
    FilesStackTestResource.getUserManagementService().registerToken("fake-token-b", TARGET_B);
    FilesStackTestResource.getUserManagementService().registerToken("fake-token-c", TARGET_C);
    FilesStackTestResource.getUserManagementService().registerToken("fake-token-d", TARGET_D);
  }

  private static Response execute(String bodyPayload) {
    return graphql(bodyPayload, REQUESTER_COOKIE);
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
      Response response = execute(bodyPayload);

      // Then
      Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
      Assertions.assertThat(TestUtils.jsonResponseToErrors(response.getBody().asString())).isEmpty();
      Assertions.assertThat(
              TestUtils.jsonResponseToValue(response.getBody().asString(), "getUserById"))
          .isEmpty();
    }
  }

  @Test
  void givenAnyShareTargetIdNodeShareFieldShouldAlwaysReturnNull() {
    // Given — a node the requester owns (so getNode itself succeeds trivially), and three
    // meaningfully different share_target_id combinations: the requester's own id, a registered
    // other user, and an id that resolves to nobody at all.
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);

    for (String shareTargetId :
        List.of(REQUESTER_ID, TARGET_B, "ffffffff-ffff-ffff-ffff-ffffffffffff")) {
      String bodyPayload =
          GraphqlCommandBuilder.aQueryBuilder("getNode")
              .withString("node_id", nodeId)
              .withWantedResultFormat(
                  "{ id share(share_target_id: \\\"" + shareTargetId + "\\\") { permission } }")
              .build();

      // When
      Response response = execute(bodyPayload);

      // Then
      Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
      Assertions.assertThat(TestUtils.jsonResponseToErrors(response.getBody().asString())).isEmpty();
      Map<String, Object> node = TestUtils.jsonResponseToMap(response.getBody().asString(), "getNode");
      Assertions.assertThat(node).containsEntry("id", nodeId);
      Assertions.assertThat(node.get("share")).isNull();
    }
  }

  @SuppressWarnings("unchecked")
  @Test
  void givenSortsArgumentSharesOrderShouldBeIdenticalToOmittingIt() {
    // Given — one node shared with three distinct, distinguishable targets, inserted in a fixed
    // order (B, then C, then D), with a clock tick between each share to guarantee distinct
    // creation timestamps.
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    seedShare(nodeId, TARGET_B, ACL.SharePermission.READ_ONLY, REQUESTER_COOKIE);
    tickClock();
    seedShare(nodeId, TARGET_C, ACL.SharePermission.READ_ONLY, REQUESTER_COOKIE);
    tickClock();
    seedShare(nodeId, TARGET_D, ACL.SharePermission.READ_ONLY, REQUESTER_COOKIE);

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
    Response withSortsResponse = execute(withSortsPayload);
    Response withoutSortsResponse = execute(withoutSortsPayload);

    // Then — both must be error-free (all three targets are registered UM users) and, crucially,
    // the ORDER of target ids must be IDENTICAL between the two calls: `sorts` changed nothing.
    Assertions.assertThat(TestUtils.jsonResponseToErrors(withSortsResponse.getBody().asString())).isEmpty();
    Assertions.assertThat(TestUtils.jsonResponseToErrors(withoutSortsResponse.getBody().asString())).isEmpty();

    List<String> withSortsOrder = shareTargetIds(withSortsResponse);
    List<String> withoutSortsOrder = shareTargetIds(withoutSortsResponse);

    Assertions.assertThat(withSortsOrder).containsExactlyInAnyOrder(TARGET_B, TARGET_C, TARGET_D);
    Assertions.assertThat(withSortsOrder).isEqualTo(withoutSortsOrder);
  }

  @SuppressWarnings("unchecked")
  private static List<String> shareTargetIds(Response response) {
    Map<String, Object> node = TestUtils.jsonResponseToMap(response.getBody().asString(), "getNode");
    List<Map<String, Object>> shares = (List<Map<String, Object>>) node.get("shares");
    return shares.stream()
        .map(share -> (Map<String, Object>) share.get("share_target"))
        .map(shareTarget -> (String) shareTarget.get("id"))
        .toList();
  }

  @Test
  void givenTheOnlyTwoUnionFieldsInTheSchemaNeitherEverResolvesToADistributionList() {
    // Given — the Account union (getAccountByEmail) and the SharedTarget union
    // (Share.share_target) are the ONLY two places in the schema a DistributionList could ever
    // appear. Both share the exact same type resolver (UserDataFetcher#getAccountTypeResolver),
    // which is unconditionally wired to Types.USER by every account-producing code path.
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    seedShare(nodeId, TARGET_B, ACL.SharePermission.READ_ONLY, REQUESTER_COOKIE);

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
    Response accountByEmailResponse = execute(accountByEmailPayload);
    Response shareTargetResponse = execute(shareTargetPayload);

    // Then — Account union: always User
    Assertions.assertThat(TestUtils.jsonResponseToErrors(accountByEmailResponse.getBody().asString()))
        .isEmpty();
    Map<String, Object> account =
        TestUtils.jsonResponseToMap(accountByEmailResponse.getBody().asString(), "getAccountByEmail");
    Assertions.assertThat(account).containsEntry("__typename", "User");

    // Then — SharedTarget union: always User
    Assertions.assertThat(TestUtils.jsonResponseToErrors(shareTargetResponse.getBody().asString()))
        .isEmpty();
    Map<String, Object> node = TestUtils.jsonResponseToMap(shareTargetResponse.getBody().asString(), "getNode");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> shares = (List<Map<String, Object>>) node.get("shares");
    Assertions.assertThat(shares).hasSize(1);
    @SuppressWarnings("unchecked")
    Map<String, Object> shareTarget = (Map<String, Object>) shares.get(0).get("share_target");
    Assertions.assertThat(shareTarget).containsEntry("__typename", "User");
  }
}
