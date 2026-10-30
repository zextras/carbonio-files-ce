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
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SharePermissionPolicyApiIT extends AbstractFilesIT {

  private static final String OWNER_ID = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeee11";
  private static final String HOLDER_ID = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeee12";
  private static final String TARGET_ID = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeee13";
  private static final String OWNER_COOKIE = "ZM_AUTH_TOKEN=policy-b-owner";
  private static final String HOLDER_COOKIE = "ZM_AUTH_TOKEN=policy-b-holder";
  private static final String NOT_ALLOWED = "SHARE_PERMISSION_NOT_ALLOWED";
  private static final String CREATION_ERROR = "SHARE_CREATION_ERROR";
  private static final String NOT_FOUND = "SHARE_NOT_FOUND";
  private static final String WRITE_ERROR = "NODE_WRITE_ERROR";
  private static final long FAR_FUTURE = 4102444800000L;

  @BeforeAll
  static void registerAccounts() {
    var userManagement = FilesStackTestResource.getUserManagementService();
    userManagement.registerAccount("policy-b-owner", OWNER_ID, "policy-b-cos", false);
    userManagement.registerAccount("policy-b-holder", HOLDER_ID, "policy-b-cos-h", false);
    userManagement.registerAccount("policy-b-target", TARGET_ID, "policy-b-cos-t", false);
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
      String nodeId, String target, SharePermission permission, Long expiresAt, String cookie) {
    GraphqlCommandBuilder builder =
        GraphqlCommandBuilder.aMutationBuilder("updateShares")
            .withString("node_id", nodeId)
            .withListOfStrings("share_target_ids", new String[] {target});
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

  private static void assertNoErrors(Response response) {
    assertThat(response.getStatusCode()).isEqualTo(200);
    assertThat(TestUtils.jsonResponseToErrors(response.getBody().asString())).isEmpty();
  }

  private static void assertError(Response response, String code) {
    assertThat(response.getStatusCode()).isEqualTo(200);
    assertThat(TestUtils.jsonResponseToErrorCodes(response.getBody().asString()))
        .containsExactly(code);
  }

  private static void assertNotAllowed(Response response, SharePermission permission) {
    assertError(response, NOT_ALLOWED);
    assertThat(response.jsonPath().getString("errors[0].extensions.permission"))
        .isEqualTo(permission.name());
  }

  private static String newFile() {
    return seedFile(
        "file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
  }

  private static void capWrite() throws SQLException {
    setHierarchicalConfig("account", OWNER_ID, "share-write-permission-enabled", "false");
  }

  private static void capReshare() throws SQLException {
    setHierarchicalConfig("account", OWNER_ID, "share-reshare-permission-enabled", "false");
  }

  private static Short rights(String nodeId, String target) throws SQLException {
    try (Connection connection = jdbcConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT rights FROM share WHERE node_id = ? AND target_uuid = ?")) {
      statement.setString(1, nodeId);
      statement.setString(2, target);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? rows.getShort(1) : null;
      }
    }
  }

  private static Long expireDate(String nodeId, String target) throws SQLException {
    try (Connection connection = jdbcConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT expire_date FROM share WHERE node_id = ? AND target_uuid = ?")) {
      statement.setString(1, nodeId);
      statement.setString(2, target);
      try (ResultSet rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        long value = rows.getLong(1);
        return rows.wasNull() ? null : value;
      }
    }
  }

  private static long collaborationLinkCount(String nodeId) throws SQLException {
    try (Connection connection = jdbcConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT count(*) FROM collaboration_link WHERE node_id = ?")) {
      statement.setString(1, nodeId);
      try (ResultSet rows = statement.executeQuery()) {
        rows.next();
        return rows.getLong(1);
      }
    }
  }

  @Test
  void withWriteCappedOnlyPermissionsWithoutWriteCanBeGranted() throws Exception {
    String nodeId = newFile();
    capWrite();

    assertNoErrors(createShare(nodeId, HOLDER_ID, SharePermission.READ_ONLY, OWNER_COOKIE));
    assertNoErrors(createShare(nodeId, TARGET_ID, SharePermission.READ_AND_SHARE, OWNER_COOKIE));
    assertThat(rights(nodeId, HOLDER_ID)).isEqualTo(SharePermission.READ_ONLY.encode());
    assertThat(rights(nodeId, TARGET_ID)).isEqualTo(SharePermission.READ_AND_SHARE.encode());

    String rejected = newFile();
    assertNotAllowed(
        createShare(rejected, HOLDER_ID, SharePermission.READ_AND_WRITE, OWNER_COOKIE),
        SharePermission.READ_AND_WRITE);
    assertNotAllowed(
        createShare(rejected, TARGET_ID, SharePermission.READ_WRITE_AND_SHARE, OWNER_COOKIE),
        SharePermission.READ_WRITE_AND_SHARE);
    assertThat(rights(rejected, HOLDER_ID)).isNull();
    assertThat(rights(rejected, TARGET_ID)).isNull();
    assertThat(rights(nodeId, HOLDER_ID)).isEqualTo(SharePermission.READ_ONLY.encode());
    assertThat(rights(nodeId, TARGET_ID)).isEqualTo(SharePermission.READ_AND_SHARE.encode());
  }

  @Test
  void withReshareCappedOnlyPermissionsWithoutShareCanBeGranted() throws Exception {
    String nodeId = newFile();
    capReshare();

    assertNoErrors(createShare(nodeId, HOLDER_ID, SharePermission.READ_AND_WRITE, OWNER_COOKIE));
    assertThat(rights(nodeId, HOLDER_ID)).isEqualTo(SharePermission.READ_AND_WRITE.encode());

    String rejected = newFile();
    assertNotAllowed(
        createShare(rejected, HOLDER_ID, SharePermission.READ_AND_SHARE, OWNER_COOKIE),
        SharePermission.READ_AND_SHARE);
    assertNotAllowed(
        createShare(rejected, TARGET_ID, SharePermission.READ_WRITE_AND_SHARE, OWNER_COOKIE),
        SharePermission.READ_WRITE_AND_SHARE);
    assertThat(rights(rejected, HOLDER_ID)).isNull();
    assertThat(rights(rejected, TARGET_ID)).isNull();
  }

  @Test
  void withBothCappedOnlyReadOnlyCanBeGranted() throws Exception {
    String nodeId = newFile();
    capWrite();
    capReshare();

    assertNoErrors(createShare(nodeId, HOLDER_ID, SharePermission.READ_ONLY, OWNER_COOKIE));
    assertThat(rights(nodeId, HOLDER_ID)).isEqualTo(SharePermission.READ_ONLY.encode());
    for (SharePermission denied :
        new SharePermission[] {
          SharePermission.READ_AND_WRITE,
          SharePermission.READ_AND_SHARE,
          SharePermission.READ_WRITE_AND_SHARE
        }) {
      assertNotAllowed(createShare(nodeId, TARGET_ID, denied, OWNER_COOKIE), denied);
    }
    assertThat(rights(nodeId, TARGET_ID)).isNull();
  }

  @Test
  void updateSharesChecksTheResultingPermission() throws Exception {
    String nodeId = newFile();
    assertNoErrors(
        createShare(nodeId, HOLDER_ID, SharePermission.READ_WRITE_AND_SHARE, OWNER_COOKIE));
    capWrite();

    assertNoErrors(updateShares(nodeId, HOLDER_ID, null, FAR_FUTURE, OWNER_COOKIE));
    assertThat(rights(nodeId, HOLDER_ID)).isEqualTo(SharePermission.READ_WRITE_AND_SHARE.encode());
    assertThat(expireDate(nodeId, HOLDER_ID)).isEqualTo(FAR_FUTURE);

    assertNotAllowed(
        updateShares(nodeId, HOLDER_ID, SharePermission.READ_AND_WRITE, null, OWNER_COOKIE),
        SharePermission.READ_AND_WRITE);
    assertThat(rights(nodeId, HOLDER_ID)).isEqualTo(SharePermission.READ_WRITE_AND_SHARE.encode());

    assertNoErrors(updateShares(nodeId, HOLDER_ID, SharePermission.READ_ONLY, null, OWNER_COOKIE));
    assertThat(rights(nodeId, HOLDER_ID)).isEqualTo(SharePermission.READ_ONLY.encode());

    assertNotAllowed(
        updateShares(nodeId, HOLDER_ID, SharePermission.READ_WRITE_AND_SHARE, null, OWNER_COOKIE),
        SharePermission.READ_WRITE_AND_SHARE);
    assertThat(rights(nodeId, HOLDER_ID)).isEqualTo(SharePermission.READ_ONLY.encode());
  }

  @Test
  void existingSharesAboveTheCapAreNotTouched() throws Exception {
    String nodeId = newFile();
    assertNoErrors(
        createShare(nodeId, HOLDER_ID, SharePermission.READ_WRITE_AND_SHARE, OWNER_COOKIE));
    capWrite();
    capReshare();

    Response response =
        graphql(
            GraphqlCommandBuilder.aQueryBuilder("getShare")
                .withString("node_id", nodeId)
                .withString("share_target_id", HOLDER_ID)
                .withWantedResultFormat("{ permission }")
                .build(),
            OWNER_COOKIE);
    assertNoErrors(response);
    assertThat(
            TestUtils.jsonResponseToMap(response.getBody().asString(), "getShare")
                .get("permission"))
        .isEqualTo("READ_WRITE_AND_SHARE");
  }

  @Test
  void collaborationLinksAreCappedToo() throws Exception {
    String nodeId = newFile();
    capWrite();

    assertNotAllowed(
        createCollaborationLink(nodeId, SharePermission.READ_AND_WRITE, OWNER_COOKIE),
        SharePermission.READ_AND_WRITE);
    assertThat(collaborationLinkCount(nodeId)).isZero();
    assertNoErrors(createCollaborationLink(nodeId, SharePermission.READ_ONLY, OWNER_COOKIE));
    assertThat(collaborationLinkCount(nodeId)).isEqualTo(1);
  }

  @Test
  void collaborationLinksWithTheShareBitAreCappedByTheReshareCap() throws Exception {
    String nodeId = newFile();
    capReshare();

    assertNotAllowed(
        createCollaborationLink(nodeId, SharePermission.READ_AND_SHARE, OWNER_COOKIE),
        SharePermission.READ_AND_SHARE);
    assertThat(collaborationLinkCount(nodeId)).isZero();
  }

  @Test
  void aHolderCannotGrantMoreThanItHolds() throws Exception {
    String first = newFile();
    String second = newFile();
    assertNoErrors(createShare(first, HOLDER_ID, SharePermission.READ_AND_SHARE, OWNER_COOKIE));
    assertNoErrors(createShare(second, HOLDER_ID, SharePermission.READ_AND_SHARE, OWNER_COOKIE));

    assertNoErrors(createShare(first, TARGET_ID, SharePermission.READ_ONLY, HOLDER_COOKIE));
    assertNoErrors(createShare(second, TARGET_ID, SharePermission.READ_AND_SHARE, HOLDER_COOKIE));

    String third = newFile();
    assertNoErrors(createShare(third, HOLDER_ID, SharePermission.READ_AND_SHARE, OWNER_COOKIE));
    assertError(
        createShare(third, TARGET_ID, SharePermission.READ_AND_WRITE, HOLDER_COOKIE),
        CREATION_ERROR);
    assertError(
        createShare(third, TARGET_ID, SharePermission.READ_WRITE_AND_SHARE, HOLDER_COOKIE),
        CREATION_ERROR);
    assertThat(rights(third, TARGET_ID)).isNull();
  }

  @Test
  void aHolderCannotRaiseAShareAboveItsOwnPermission() throws Exception {
    String nodeId = newFile();
    assertNoErrors(createShare(nodeId, HOLDER_ID, SharePermission.READ_AND_SHARE, OWNER_COOKIE));
    assertNoErrors(createShare(nodeId, TARGET_ID, SharePermission.READ_ONLY, OWNER_COOKIE));

    assertError(
        updateShares(nodeId, TARGET_ID, SharePermission.READ_WRITE_AND_SHARE, null, HOLDER_COOKIE),
        NOT_FOUND);
    assertThat(rights(nodeId, TARGET_ID)).isEqualTo(SharePermission.READ_ONLY.encode());

    assertNoErrors(
        updateShares(nodeId, TARGET_ID, SharePermission.READ_AND_SHARE, null, HOLDER_COOKIE));
    assertThat(rights(nodeId, TARGET_ID)).isEqualTo(SharePermission.READ_AND_SHARE.encode());
  }

  @Test
  void aFullHolderCanStillReshareEverything() throws Exception {
    String nodeId = newFile();
    assertNoErrors(
        createShare(nodeId, HOLDER_ID, SharePermission.READ_WRITE_AND_SHARE, OWNER_COOKIE));

    assertNoErrors(
        createShare(nodeId, TARGET_ID, SharePermission.READ_WRITE_AND_SHARE, HOLDER_COOKIE));
    assertThat(rights(nodeId, TARGET_ID)).isEqualTo(SharePermission.READ_WRITE_AND_SHARE.encode());
  }

  @Test
  void collaborationLinksNeedTheShareBitAndAreBoundedByIt() throws Exception {
    String readOnly = newFile();
    assertNoErrors(createShare(readOnly, HOLDER_ID, SharePermission.READ_ONLY, OWNER_COOKIE));
    assertError(
        createCollaborationLink(readOnly, SharePermission.READ_ONLY, HOLDER_COOKIE), WRITE_ERROR);
    assertThat(collaborationLinkCount(readOnly)).isZero();

    String reshare = newFile();
    assertNoErrors(createShare(reshare, HOLDER_ID, SharePermission.READ_AND_SHARE, OWNER_COOKIE));
    assertNoErrors(createCollaborationLink(reshare, SharePermission.READ_ONLY, HOLDER_COOKIE));
    assertThat(collaborationLinkCount(reshare)).isEqualTo(1);
    assertError(
        createCollaborationLink(reshare, SharePermission.READ_AND_WRITE, HOLDER_COOKIE),
        WRITE_ERROR);
    assertThat(collaborationLinkCount(reshare)).isEqualTo(1);
  }

  @Test
  void theCosCapsTheWritePermissionAndTheAccountTierReEnablesIt() throws Exception {
    String nodeId = newFile();
    setHierarchicalConfig("cos", "policy-b-cos", "share-write-permission-enabled", "false");

    assertNotAllowed(
        createShare(nodeId, HOLDER_ID, SharePermission.READ_AND_WRITE, OWNER_COOKIE),
        SharePermission.READ_AND_WRITE);
    assertThat(rights(nodeId, HOLDER_ID)).isNull();

    setHierarchicalConfig("account", OWNER_ID, "share-write-permission-enabled", "true");
    assertNoErrors(createShare(nodeId, HOLDER_ID, SharePermission.READ_AND_WRITE, OWNER_COOKIE));
    assertThat(rights(nodeId, HOLDER_ID)).isEqualTo(SharePermission.READ_AND_WRITE.encode());
  }

  @Test
  void theCapIsReadFromTheCallerAndNotFromTheOwner() throws Exception {
    String nodeId = newFile();
    assertNoErrors(
        createShare(nodeId, HOLDER_ID, SharePermission.READ_WRITE_AND_SHARE, OWNER_COOKIE));
    capWrite();

    assertNotAllowed(
        createShare(nodeId, TARGET_ID, SharePermission.READ_AND_WRITE, OWNER_COOKIE),
        SharePermission.READ_AND_WRITE);
    assertNoErrors(createShare(nodeId, TARGET_ID, SharePermission.READ_AND_WRITE, HOLDER_COOKIE));
    assertThat(rights(nodeId, TARGET_ID)).isEqualTo(SharePermission.READ_AND_WRITE.encode());
  }

  @Test
  void policyIsCheckedBeforeNodePermissions() throws Exception {
    String nodeId = newFile();
    assertNoErrors(createShare(nodeId, HOLDER_ID, SharePermission.READ_AND_SHARE, OWNER_COOKIE));
    setHierarchicalConfig("account", HOLDER_ID, "share-write-permission-enabled", "false");

    assertNotAllowed(
        createShare(nodeId, TARGET_ID, SharePermission.READ_AND_WRITE, HOLDER_COOKIE),
        SharePermission.READ_AND_WRITE);
  }
}
