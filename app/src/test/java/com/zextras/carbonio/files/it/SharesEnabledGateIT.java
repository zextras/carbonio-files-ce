// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Gate IT: proves the Share create/edit/find ops (createShare / updateShares / getShare + the
 * shares field) return SHARES_DISABLED (HTTP 200 + GraphQL error) when the requester has
 * shares-enabled=false in carbonio_account_config, while the same ops succeed for a user without
 * that override (per-user isolation) and no override leaves the default enabled. Intentionally NOT
 * gated (see the *_notGated cases): deleteShares (removing an existing share must always work),
 * collaboration links, and the REST /invite (a separate feature that does not use the gated Share
 * ops).
 */
class SharesEnabledGateIT extends AbstractFilesIT {

  private static final String DISABLED_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String ENABLED_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String TARGET_ID = "cccccccc-cccc-cccc-cccc-cccccccccccc";

  private static final String DISABLED_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String ENABLED_COOKIE = "ZM_AUTH_TOKEN=fake-token-b";
  private static final String TARGET_COOKIE = "ZM_AUTH_TOKEN=fake-token-c";

  private static final String SHARES_DISABLED_MSG = "Sharing is disabled for this user";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", DISABLED_ID);
    FilesStackTestResource.getUserManagementService().registerToken("fake-token-b", ENABLED_ID);
    FilesStackTestResource.getUserManagementService().registerToken("fake-token-c", TARGET_ID);
  }

  @AfterEach
  void cleanAccountConfig() throws SQLException {
    try (Connection connection = jdbcConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "DELETE FROM carbonio_account_config WHERE account_id = ?")) {
      statement.setString(1, DISABLED_ID);
      statement.executeUpdate();
    }
  }

  private void disableSharesFor(String userId) throws SQLException {
    try (Connection connection = jdbcConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO carbonio_account_config (account_id, config_key, value)"
                    + " VALUES (?, 'shares-enabled', 'false')"
                    + " ON CONFLICT (account_id, config_key) DO UPDATE SET value = 'false'")) {
      statement.setString(1, userId);
      statement.executeUpdate();
    }
  }

  private List<String> errors(Response response) {
    return TestUtils.jsonResponseToErrors(response.getBody().asString());
  }

  private Response createShare(String nodeId, String targetUserId, String cookie) {
    String body =
        GraphqlCommandBuilder.aMutationBuilder("createShare")
            .withString("node_id", nodeId)
            .withString("share_target_id", targetUserId)
            .withEnum("permission", SharePermission.READ_ONLY)
            .withWantedResultFormat("{ created_at }")
            .build();
    return graphql(body, cookie);
  }

  private Response getShare(String nodeId, String targetUserId, String cookie) {
    String body =
        GraphqlCommandBuilder.aQueryBuilder("getShare")
            .withString("node_id", nodeId)
            .withString("share_target_id", targetUserId)
            .withWantedResultFormat("{ permission }")
            .build();
    return graphql(body, cookie);
  }

  private Response updateShares(String nodeId, String targetUserId, String cookie) {
    String body =
        GraphqlCommandBuilder.aMutationBuilder("updateShares")
            .withString("node_id", nodeId)
            .withListOfStrings("share_target_ids", new String[] {targetUserId})
            .withEnum("permission", SharePermission.READ_AND_SHARE)
            .withWantedResultFormat("{ created_at }")
            .build();
    return graphql(body, cookie);
  }

  private Response deleteShares(String nodeId, String targetUserId, String cookie) {
    String body =
        GraphqlCommandBuilder.aMutationBuilder("deleteShares")
            .withString("node_id", nodeId)
            .withListOfStrings("share_target_ids", new String[] {targetUserId})
            .withWantedResultFormat("")
            .build();
    return graphql(body, cookie);
  }

  private Response createCollaborationLink(String nodeId, String cookie) {
    String body =
        GraphqlCommandBuilder.aMutationBuilder("createCollaborationLink")
            .withString("node_id", nodeId)
            .withEnumLiteral("permission", "READ_AND_SHARE")
            .withWantedResultFormat("{ url }")
            .build();
    return graphql(body, cookie);
  }

  private Response getCollaborationLinks(String nodeId, String cookie) {
    String body =
        GraphqlCommandBuilder.aQueryBuilder("getCollaborationLinks")
            .withString("node_id", nodeId)
            .withWantedResultFormat("{ url }")
            .build();
    return graphql(body, cookie);
  }

  private Response nodeShares(String nodeId, String cookie) {
    String body =
        GraphqlCommandBuilder.aQueryBuilder("getNode")
            .withString("node_id", nodeId)
            .withWantedResultFormat("{ shares(limit: 10) { created_at } }")
            .build();
    return graphql(body, cookie);
  }

  /** Extracts the 8-char invitation id from a createCollaborationLink url field. */
  private String invitationId(Response createLinkResponse) {
    Map<String, Object> data =
        TestUtils.jsonResponseToMap(
            createLinkResponse.getBody().asString(), "createCollaborationLink");
    String url = (String) data.get("url");
    return url.substring(url.length() - 8);
  }

  private Response clickInvite(String invitationId, String cookie) {
    var request = RestAssured.given().redirects().follow(false);
    if (cookie != null) {
      request = request.header("Cookie", cookie);
    }
    return request.get("/invite/" + invitationId);
  }

  // --- Tests: disabled user gets SHARES_DISABLED on every gated operation ---

  @Test
  void disabledUser_createShare_returnsSharesDisabled() throws SQLException {
    disableSharesFor(DISABLED_ID);
    // DISABLED user owns the node — they have READ_AND_SHARE, so without the gate createShare
    // would succeed. Gate must fire before the permission check.
    String nodeId =
        seedFile(
            "file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), DISABLED_COOKIE);

    Response response = createShare(nodeId, TARGET_ID, DISABLED_COOKIE);

    assertThat(response.getStatusCode()).isEqualTo(200);
    assertThat(errors(response)).hasSize(1).containsExactly(SHARES_DISABLED_MSG);
  }

  @Test
  void disabledUser_getShare_returnsSharesDisabled() throws SQLException {
    disableSharesFor(DISABLED_ID);
    // Node owned by ENABLED, which seeds a share for TARGET. DISABLED gets READ_ONLY via seedShare
    // so getNode succeeds, but the gate fires before permission check.
    String nodeId =
        seedFile(
            "file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), ENABLED_COOKIE);
    seedShare(nodeId, TARGET_ID, SharePermission.READ_ONLY, ENABLED_COOKIE);
    seedShare(nodeId, DISABLED_ID, SharePermission.READ_ONLY, ENABLED_COOKIE);

    Response response = getShare(nodeId, TARGET_ID, DISABLED_COOKIE);

    assertThat(response.getStatusCode()).isEqualTo(200);
    assertThat(errors(response)).hasSize(1).containsExactly(SHARES_DISABLED_MSG);
  }

  @Test
  void disabledUser_updateShares_returnsSharesDisabled() throws SQLException {
    disableSharesFor(DISABLED_ID);
    // DISABLED gets READ_AND_SHARE (owner-level) via ENABLED user, so without the gate updateShares
    // would succeed. Gate fires before permission check.
    String nodeId =
        seedFile(
            "file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), ENABLED_COOKIE);
    seedShare(nodeId, TARGET_ID, SharePermission.READ_ONLY, ENABLED_COOKIE);
    seedShare(nodeId, DISABLED_ID, SharePermission.READ_AND_SHARE, ENABLED_COOKIE);

    Response response = updateShares(nodeId, TARGET_ID, DISABLED_COOKIE);

    assertThat(response.getStatusCode()).isEqualTo(200);
    assertThat(errors(response)).hasSize(1).containsExactly(SHARES_DISABLED_MSG);
  }

  @Test
  void disabledUser_deleteShares_notGated() throws SQLException {
    disableSharesFor(DISABLED_ID);
    // delete must ALWAYS work: if a user's sharing is turned off after they created shares, they
    // must still be able to remove them (existing shares must not become unmanageable).
    String nodeId =
        seedFile(
            "file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), ENABLED_COOKIE);
    seedShare(nodeId, TARGET_ID, SharePermission.READ_ONLY, ENABLED_COOKIE);
    seedShare(nodeId, DISABLED_ID, SharePermission.READ_AND_SHARE, ENABLED_COOKIE);

    Response response = deleteShares(nodeId, TARGET_ID, DISABLED_COOKIE);

    assertThat(response.getStatusCode()).isEqualTo(200);
    assertThat(errors(response)).doesNotContain(SHARES_DISABLED_MSG);
  }

  @Test
  void disabledUser_createCollaborationLink_notGated() throws SQLException {
    disableSharesFor(DISABLED_ID);
    // Collaboration links are a separate feature (not a gated Share op): a shares-disabled user can
    // still create one for a node they own.
    String nodeId =
        seedFile(
            "file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), DISABLED_COOKIE);

    Response response = createCollaborationLink(nodeId, DISABLED_COOKIE);

    assertThat(response.getStatusCode()).isEqualTo(200);
    assertThat(errors(response)).doesNotContain(SHARES_DISABLED_MSG);
  }

  @Test
  void disabledUser_getCollaborationLinks_notGated() throws SQLException {
    disableSharesFor(DISABLED_ID);
    String nodeId =
        seedFile(
            "file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), DISABLED_COOKIE);

    Response response = getCollaborationLinks(nodeId, DISABLED_COOKIE);

    assertThat(response.getStatusCode()).isEqualTo(200);
    assertThat(errors(response)).doesNotContain(SHARES_DISABLED_MSG);
  }

  @Test
  void disabledUser_nodeSharesField_returnsSharesDisabled() throws SQLException {
    disableSharesFor(DISABLED_ID);
    // DISABLED owns the node so getNode itself succeeds; the shares field resolver is blocked.
    String nodeId =
        seedFile(
            "file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), DISABLED_COOKIE);

    Response response = nodeShares(nodeId, DISABLED_COOKIE);

    assertThat(response.getStatusCode()).isEqualTo(200);
    assertThat(errors(response)).hasSize(1).containsExactly(SHARES_DISABLED_MSG);
  }

  @Test
  void disabledUser_inviteRest_notGated() throws SQLException {
    disableSharesFor(DISABLED_ID);
    String nodeId =
        seedFile(
            "file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), ENABLED_COOKIE);
    Response createLink = createCollaborationLink(nodeId, ENABLED_COOKIE);
    String invId = invitationId(createLink);

    Response response = clickInvite(invId, DISABLED_COOKIE);

    // /invite creates the share for the clicker via its own service path (not the gated createShare
    // op), so a shares-disabled user can still accept an invite -> 307 redirect, not blocked.
    assertThat(response.getStatusCode()).isEqualTo(307);
  }

  // --- Test: enabled user B is NOT blocked (per-user isolation) ---

  @Test
  void enabledUser_createShare_succeedsWhenOtherUserDisabled() throws SQLException {
    disableSharesFor(DISABLED_ID);
    String nodeId =
        seedFile(
            "file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), ENABLED_COOKIE);

    Response response = createShare(nodeId, TARGET_ID, ENABLED_COOKIE);

    assertThat(response.getStatusCode()).isEqualTo(200);
    assertThat(errors(response)).isEmpty();
  }

  // --- Test: no override (default true) → user can share normally ---

  @Test
  void noConfig_createShare_succeedsForDisabledIdWithoutOverride() {
    String nodeId =
        seedFile(
            "file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), DISABLED_COOKIE);

    Response response = createShare(nodeId, TARGET_ID, DISABLED_COOKIE);

    assertThat(response.getStatusCode()).isEqualTo(200);
    assertThat(errors(response)).isEmpty();
  }
}
