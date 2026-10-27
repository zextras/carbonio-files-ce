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
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SharingTypesPolicyApiIT extends AbstractFilesIT {

  private static final String OWNER_ID = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeee01";
  private static final String RECIPIENT_ID = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeee02";
  private static final String OTHER_ID = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeee03";
  private static final String OWNER_COOKIE = "ZM_AUTH_TOKEN=policy-a-owner";
  private static final String RECIPIENT_COOKIE = "ZM_AUTH_TOKEN=policy-a-recipient";
  private static final String OTHER_COOKIE = "ZM_AUTH_TOKEN=policy-a-other";
  private static final String OWNER_COS = "policy-a-cos";
  private static final String OWNER_DOMAIN = "policy-a-dom";
  private static final String DISABLED = "SHARING_TYPE_DISABLED";
  private static final long FAR_FUTURE = 4102444800000L;

  @BeforeAll
  static void registerAccounts() {
    var userManagement = FilesStackTestResource.getUserManagementService();
    userManagement.registerAccount("policy-a-owner", OWNER_ID, OWNER_COS, OWNER_DOMAIN, false);
    userManagement.registerAccount(
        "policy-a-recipient", RECIPIENT_ID, "policy-a-cos-r", "policy-a-dom-r", false);
    userManagement.registerAccount(
        "policy-a-other", OTHER_ID, "policy-a-cos-o", "policy-a-dom-o", false);
  }

  @BeforeEach
  @AfterEach
  void cleanConfigTables() throws SQLException {
    clearHierarchicalConfig();
  }

  private static Response createShare(
      String nodeId, String target, SharePermission permission, String cookie) {
    return graphql(
        GraphqlCommandBuilder.aMutationBuilder("createShare")
            .withString("node_id", nodeId)
            .withString("share_target_id", target)
            .withEnum("permission", permission)
            .withWantedResultFormat("{ created_at }")
            .build(),
        cookie);
  }

  private static Response updateShares(
      String nodeId, String[] targets, SharePermission permission, Long expiresAt, String cookie) {
    GraphqlCommandBuilder builder =
        GraphqlCommandBuilder.aMutationBuilder("updateShares")
            .withString("node_id", nodeId)
            .withListOfStrings("share_target_ids", targets);
    if (permission != null) {
      builder = builder.withEnum("permission", permission);
    }
    if (expiresAt != null) {
      builder = builder.withEnumLiteral("expires_at", String.valueOf(expiresAt));
    }
    return graphql(builder.withWantedResultFormat("{ created_at }").build(), cookie);
  }

  private static Response createCollaborationLink(
      String nodeId, SharePermission permission, String cookie) {
    return graphql(
        GraphqlCommandBuilder.aMutationBuilder("createCollaborationLink")
            .withString("node_id", nodeId)
            .withEnumLiteral("permission", permission.name())
            .withWantedResultFormat("{ id url }")
            .build(),
        cookie);
  }

  private static Response createLink(String nodeId, String cookie) {
    return graphql(
        GraphqlCommandBuilder.aMutationBuilder("createLink")
            .withString("node_id", nodeId)
            .withWantedResultFormat("{ id url }")
            .build(),
        cookie);
  }

  private static Response updateLink(String linkId, String description, String cookie) {
    return graphql(
        GraphqlCommandBuilder.aMutationBuilder("updateLink")
            .withString("link_id", linkId)
            .withString("description", description)
            .withWantedResultFormat("{ id }")
            .build(),
        cookie);
  }

  private static Response deleteShares(String nodeId, String[] targets, String cookie) {
    return graphql(
        GraphqlCommandBuilder.aMutationBuilder("deleteShares")
            .withString("node_id", nodeId)
            .withListOfStrings("share_target_ids", targets)
            .withWantedResultFormat("")
            .build(),
        cookie);
  }

  private static Response deleteLinks(String[] ids, String cookie) {
    return graphql(
        GraphqlCommandBuilder.aMutationBuilder("deleteLinks")
            .withListOfStrings("link_ids", ids)
            .withWantedResultFormat("")
            .build(),
        cookie);
  }

  private static Response deleteCollaborationLinks(String[] ids, String cookie) {
    return graphql(
        GraphqlCommandBuilder.aMutationBuilder("deleteCollaborationLinks")
            .withListOfStrings("collaboration_link_ids", ids)
            .withWantedResultFormat("")
            .build(),
        cookie);
  }

  private static Response getPublicNode(String publicId) {
    return publicGraphql(
        GraphqlCommandBuilder.aQueryBuilder("getPublicNode")
            .withString("node_link_id", publicId)
            .withWantedResultFormat("{ id }")
            .build());
  }

  private static Response clickInvite(String invitationId, String cookie) {
    return RestAssured.given()
        .redirects()
        .follow(false)
        .header("Cookie", cookie)
        .get("/invite/" + invitationId);
  }

  private static Response postInternalLink(String userId, String nodeId) {
    return RestAssured.given()
        .contentType(ContentType.JSON)
        .body(Map.of("userId", userId, "nodeId", nodeId))
        .post("/internal/links");
  }

  private static String errorExtension(Response response, String field) {
    return response.jsonPath().getString("errors[0].extensions." + field);
  }

  private static void assertNoErrors(Response response) {
    assertThat(response.getStatusCode()).isEqualTo(200);
    assertThat(TestUtils.jsonResponseToErrors(response.getBody().asString())).isEmpty();
  }

  private static void assertDisabled(Response response, String sharingType) {
    assertThat(response.getStatusCode()).isEqualTo(200);
    assertThat(TestUtils.jsonResponseToErrorCodes(response.getBody().asString()))
        .containsExactly(DISABLED);
    assertThat(errorExtension(response, "sharingType")).isEqualTo(sharingType);
  }

  private static String newFile() {
    return seedFile(
        "file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
  }

  private static String field(Response response, String operation, String field) {
    return (String)
        TestUtils.jsonResponseToMap(response.getBody().asString(), operation).get(field);
  }

  private static String suffix(String url, int length) {
    return url.substring(url.length() - length);
  }

  private static long linkCount(String nodeId) throws SQLException {
    return count("SELECT count(*) FROM link WHERE node_id = ?", nodeId);
  }

  private static long count(String sql, String param) throws SQLException {
    try (Connection connection = jdbcConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, param);
      try (ResultSet rows = statement.executeQuery()) {
        rows.next();
        return rows.getLong(1);
      }
    }
  }

  private static long collaborationLinkCount(String nodeId) throws SQLException {
    return count("SELECT count(*) FROM collaboration_link WHERE node_id = ?", nodeId);
  }

  private static List<Object> shareRow(String nodeId, String target) throws SQLException {
    try (Connection connection = jdbcConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT rights, expire_date FROM share WHERE node_id = ? AND target_uuid = ?")) {
      statement.setString(1, nodeId);
      statement.setString(2, target);
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) {
          return List.of();
        }
        return List.of(rows.getInt(1), rows.getLong(2));
      }
    }
  }

  private static String linkDescription(String linkId) throws SQLException {
    try (Connection connection = jdbcConnection();
        PreparedStatement statement =
            connection.prepareStatement("SELECT description FROM link WHERE id = ?")) {
      statement.setString(1, linkId);
      try (ResultSet rows = statement.executeQuery()) {
        rows.next();
        return rows.getString(1);
      }
    }
  }

  @Test
  void everyTypeIsAllowedByDefault() throws Exception {
    String nodeId = newFile();

    assertNoErrors(createShare(nodeId, RECIPIENT_ID, SharePermission.READ_ONLY, OWNER_COOKIE));
    assertNoErrors(
        updateShares(nodeId, new String[] {RECIPIENT_ID}, null, FAR_FUTURE, OWNER_COOKIE));
    assertNoErrors(createCollaborationLink(nodeId, SharePermission.READ_ONLY, OWNER_COOKIE));
    Response link = createLink(nodeId, OWNER_COOKIE);
    assertNoErrors(link);
    assertNoErrors(updateLink(field(link, "createLink", "id"), "desc", OWNER_COOKIE));
    assertThat(linkDescription(field(link, "createLink", "id"))).isEqualTo("desc");
  }

  @Test
  void disabledAccountSharesRejectCreateAndUpdate() throws Exception {
    String nodeId = newFile();
    String other = newFile();
    assertNoErrors(createShare(other, RECIPIENT_ID, SharePermission.READ_ONLY, OWNER_COOKIE));
    List<Object> before = shareRow(other, RECIPIENT_ID);
    setHierarchicalConfig("account", OWNER_ID, "account-shares-enabled", "false");

    assertDisabled(
        createShare(nodeId, RECIPIENT_ID, SharePermission.READ_ONLY, OWNER_COOKIE),
        "ACCOUNT_SHARES");
    assertThat(shareRow(nodeId, RECIPIENT_ID)).isEmpty();

    assertDisabled(
        updateShares(
            other, new String[] {RECIPIENT_ID}, SharePermission.READ_AND_WRITE, null, OWNER_COOKIE),
        "ACCOUNT_SHARES");
    assertDisabled(
        updateShares(other, new String[] {RECIPIENT_ID}, null, FAR_FUTURE, OWNER_COOKIE),
        "ACCOUNT_SHARES");
    assertThat(shareRow(other, RECIPIENT_ID)).isEqualTo(before);
  }

  @Test
  void disabledCollaborationLinksRejectCreateEvenWhenOneExists() throws Exception {
    String nodeId = newFile();
    assertNoErrors(createCollaborationLink(nodeId, SharePermission.READ_ONLY, OWNER_COOKIE));
    assertThat(collaborationLinkCount(nodeId)).isEqualTo(1);
    setHierarchicalConfig("cos", OWNER_COS, "collaboration-links-enabled", "false");

    assertDisabled(
        createCollaborationLink(nodeId, SharePermission.READ_ONLY, OWNER_COOKIE),
        "COLLABORATION_LINKS");
    assertDisabled(
        createCollaborationLink(nodeId, SharePermission.READ_AND_WRITE, OWNER_COOKIE),
        "COLLABORATION_LINKS");
    assertThat(collaborationLinkCount(nodeId)).isEqualTo(1);
  }

  @Test
  void disabledPublicLinksRejectCreateAndUpdate() throws Exception {
    String nodeId = newFile();
    String linkId = seedLink(nodeId, OWNER_COOKIE);
    assertNoErrors(updateLink(linkId, "before", OWNER_COOKIE));
    setHierarchicalConfig("domain", OWNER_DOMAIN, "public-links-enabled", "false");

    assertDisabled(createLink(nodeId, OWNER_COOKIE), "PUBLIC_LINKS");
    assertThat(linkCount(nodeId)).isEqualTo(1);
    assertDisabled(updateLink(linkId, "after", OWNER_COOKIE), "PUBLIC_LINKS");
    assertThat(linkDescription(linkId)).isEqualTo("before");
  }

  @ParameterizedTest
  @ValueSource(strings = {"account", "cos", "domain", "global"})
  void everyTierDisablesAndAMoreSpecificTierReEnables(String tier) throws Exception {
    String nodeId = newFile();
    String scopeId =
        switch (tier) {
          case "account" -> OWNER_ID;
          case "cos" -> OWNER_COS;
          case "domain" -> OWNER_DOMAIN;
          default -> null;
        };
    setHierarchicalConfig(tier, scopeId, "public-links-enabled", "false");

    assertDisabled(createLink(nodeId, OWNER_COOKIE), "PUBLIC_LINKS");

    if (!"account".equals(tier)) {
      setHierarchicalConfig("account", OWNER_ID, "public-links-enabled", "true");
      assertNoErrors(createLink(nodeId, OWNER_COOKIE));
    }
  }

  @Test
  void aCosOverrideDoesNotLeakToOtherAccounts() throws Exception {
    setHierarchicalConfig("cos", OWNER_COS, "public-links-enabled", "false");
    String otherNode =
        seedFile("o.txt", LOCAL_ROOT, "c".getBytes(StandardCharsets.UTF_8), OTHER_COOKIE);

    assertNoErrors(createLink(otherNode, OTHER_COOKIE));
  }

  @Test
  void oldAndNewObjectsBehaveTheSameWhenTheTypesAreDisabled() throws Exception {
    for (String round : List.of("old", "new")) {
      String folderId = seedFolder("policy-" + round, LOCAL_ROOT, OWNER_COOKIE);
      assertNoErrors(createShare(folderId, RECIPIENT_ID, SharePermission.READ_ONLY, OWNER_COOKIE));
      Response collaboration =
          createCollaborationLink(folderId, SharePermission.READ_ONLY, OWNER_COOKIE);
      assertNoErrors(collaboration);
      Response link = createLink(folderId, OWNER_COOKIE);
      assertNoErrors(link);

      for (String key :
          List.of(
              "account-shares-enabled", "collaboration-links-enabled", "public-links-enabled")) {
        setHierarchicalConfig("account", OWNER_ID, key, "false");
      }

      assertDisabledBehaviour(
          folderId,
          field(collaboration, "createCollaborationLink", "id"),
          suffix(field(collaboration, "createCollaborationLink", "url"), 8),
          field(link, "createLink", "id"),
          suffix(field(link, "createLink", "url"), 50));

      clearHierarchicalConfig();
    }
  }

  private void assertDisabledBehaviour(
      String folderId,
      String collaborationLinkId,
      String invitationId,
      String linkId,
      String publicId)
      throws Exception {
    assertDisabled(
        createShare(folderId, OTHER_ID, SharePermission.READ_ONLY, OWNER_COOKIE), "ACCOUNT_SHARES");
    assertDisabled(
        createCollaborationLink(folderId, SharePermission.READ_AND_WRITE, OWNER_COOKIE),
        "COLLABORATION_LINKS");
    assertDisabled(createLink(folderId, OWNER_COOKIE), "PUBLIC_LINKS");
    assertDisabled(
        updateShares(
            folderId,
            new String[] {RECIPIENT_ID},
            SharePermission.READ_AND_WRITE,
            null,
            OWNER_COOKIE),
        "ACCOUNT_SHARES");
    assertDisabled(updateLink(linkId, "x", OWNER_COOKIE), "PUBLIC_LINKS");

    Response getShare =
        graphql(
            GraphqlCommandBuilder.aQueryBuilder("getShare")
                .withString("node_id", folderId)
                .withString("share_target_id", RECIPIENT_ID)
                .withWantedResultFormat("{ permission }")
                .build(),
            OWNER_COOKIE);
    assertNoErrors(getShare);
    assertThat(field(getShare, "getShare", "permission")).isEqualTo("READ_ONLY");

    Response getLinks =
        graphql(
            GraphqlCommandBuilder.aQueryBuilder("getLinks")
                .withString("node_id", folderId)
                .withWantedResultFormat("{ id }")
                .build(),
            OWNER_COOKIE);
    assertNoErrors(getLinks);
    assertThat(TestUtils.jsonResponseToList(getLinks.getBody().asString(), "getLinks"))
        .extracting(m -> m.get("id"))
        .contains(linkId);

    Response getCollaborationLinks =
        graphql(
            GraphqlCommandBuilder.aQueryBuilder("getCollaborationLinks")
                .withString("node_id", folderId)
                .withWantedResultFormat("{ id }")
                .build(),
            OWNER_COOKIE);
    assertNoErrors(getCollaborationLinks);
    assertThat(
            TestUtils.jsonResponseToList(
                getCollaborationLinks.getBody().asString(), "getCollaborationLinks"))
        .extracting(m -> m.get("id"))
        .contains(collaborationLinkId);

    Response publicNode = getPublicNode(publicId);
    assertNoErrors(publicNode);
    assertThat(field(publicNode, "getPublicNode", "id")).isEqualTo(folderId);

    assertThat(clickInvite(invitationId, OTHER_COOKIE).getStatusCode()).isEqualTo(307);
    assertThat(shareExists(folderId, OTHER_ID, OWNER_COOKIE)).isTrue();

    assertNoErrors(deleteLinks(new String[] {linkId}, OWNER_COOKIE));
    assertNoErrors(deleteCollaborationLinks(new String[] {collaborationLinkId}, OWNER_COOKIE));
    assertNoErrors(deleteShares(folderId, new String[] {RECIPIENT_ID, OTHER_ID}, OWNER_COOKIE));
    assertThat(shareExists(folderId, RECIPIENT_ID, OWNER_COOKIE)).isFalse();
  }

  @Test
  void inheritedSharesAreNotGated() throws Exception {
    String folderId = seedFolder("inherit", LOCAL_ROOT, OWNER_COOKIE);
    assertNoErrors(createShare(folderId, RECIPIENT_ID, SharePermission.READ_ONLY, OWNER_COOKIE));
    setHierarchicalConfig("account", OWNER_ID, "account-shares-enabled", "false");

    String fileId =
        seedFile("in.txt", folderId, "c".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);

    assertThat(shareExists(fileId, RECIPIENT_ID, OWNER_COOKIE)).isTrue();
  }

  @Test
  void internalLinkEndpointRejectsWithAnExplicitBody() throws Exception {
    String nodeId = newFile();
    setHierarchicalConfig("account", OWNER_ID, "public-links-enabled", "false");

    Response rejected = postInternalLink(OWNER_ID, nodeId);

    assertThat(rejected.getStatusCode()).isEqualTo(403);
    assertThat(rejected.getBody().asString())
        .isEqualTo("{\"code\":\"SHARING_TYPE_DISABLED\",\"sharingType\":\"PUBLIC_LINKS\"}");
    assertThat(linkCount(nodeId)).isZero();

    clearHierarchicalConfig();
    Response accepted = postInternalLink(OWNER_ID, nodeId);
    assertThat(accepted.getStatusCode()).isEqualTo(200);
    assertThat(accepted.jsonPath().getString("url")).isNotBlank();
  }
}
