// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PermissionGuardsApiIT extends AbstractFilesIT {

  private static final String TRASH_ROOT = "TRASH_ROOT";
  private static final String PADDED_LOCAL_ROOT = String.format("%-36s", "LOCAL_ROOT");
  private static final String A_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String B_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String C_ID = "cccccccc-cccc-cccc-cccc-cccccccccccc";
  private static final String A_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String B_COOKIE = "ZM_AUTH_TOKEN=fake-token-b";
  private static final String C_COOKIE = "ZM_AUTH_TOKEN=fake-token-c";
  private static final byte[] CONTENT = "content".getBytes(StandardCharsets.UTF_8);

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", A_ID);
    FilesStackTestResource.getUserManagementService().registerToken("fake-token-b", B_ID);
    FilesStackTestResource.getUserManagementService().registerToken("fake-token-c", C_ID);
  }

  @AfterEach
  void removeRowsAttachedToRoots() throws SQLException {
    try (Connection connection = jdbcConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(
          "DELETE FROM node WHERE owner_id IS NULL AND node_id NOT IN ('LOCAL_ROOT',"
              + " 'TRASH_ROOT')");
      statement.execute("DELETE FROM share WHERE node_id IN ('LOCAL_ROOT', 'TRASH_ROOT')");
      statement.execute(
          "DELETE FROM collaboration_link WHERE node_id IN ('LOCAL_ROOT', 'TRASH_ROOT')");
      statement.execute("UPDATE node SET name = 'ROOT' WHERE node_id = 'LOCAL_ROOT'");
      statement.execute("UPDATE node SET name = 'TRASH' WHERE node_id = 'TRASH_ROOT'");
    }
  }

  @Test
  void createShareOnLocalRootIsRejected() throws SQLException {
    String fileOfA = seedFile("a.txt", LOCAL_ROOT, CONTENT, A_COOKIE);

    Response response = createShare(LOCAL_ROOT, B_ID, SharePermission.READ_ONLY, B_COOKIE);

    assertErrorCodes(response, "SHARE_CREATION_ERROR");
    Assertions.assertThat(shareRow(LOCAL_ROOT, B_ID)).isEmpty();
    Assertions.assertThat(shareRow(fileOfA, B_ID)).isEmpty();
  }

  @Test
  void createShareOnTrashRootIsRejected() throws SQLException {
    Response response = createShare(TRASH_ROOT, B_ID, SharePermission.READ_ONLY, B_COOKIE);

    assertErrorCodes(response, "SHARE_CREATION_ERROR");
    Assertions.assertThat(shareRow(TRASH_ROOT, B_ID)).isEmpty();
  }

  @Test
  void createShareTargetingTheRequesterIsRejected() throws SQLException {
    String folder = seedFolder("f", LOCAL_ROOT, A_COOKIE);
    String child = seedFile("x.txt", folder, CONTENT, A_COOKIE);
    seedShare(folder, B_ID, SharePermission.READ_AND_SHARE, A_COOKIE);

    Response response = createShare(child, B_ID, SharePermission.READ_WRITE_AND_SHARE, B_COOKIE);

    assertErrorCodes(response, "SHARE_CREATION_ERROR");
    Assertions.assertThat(shareRow(child, B_ID))
        .contains(new ShareRow(SharePermission.READ_AND_SHARE.encode(), false));
  }

  @Test
  void createShareAboveTheRequesterPermissionIsRejected() throws SQLException {
    String file = seedFile("x.txt", LOCAL_ROOT, CONTENT, A_COOKIE);
    seedShare(file, B_ID, SharePermission.READ_AND_SHARE, A_COOKIE);

    Response response = createShare(file, C_ID, SharePermission.READ_AND_WRITE, B_COOKIE);

    assertErrorCodes(response, "SHARE_CREATION_ERROR");
    Assertions.assertThat(shareRow(file, C_ID)).isEmpty();
  }

  @Test
  void createShareWithinTheRequesterPermissionSucceeds() throws SQLException {
    String file = seedFile("x.txt", LOCAL_ROOT, CONTENT, A_COOKIE);
    seedShare(file, B_ID, SharePermission.READ_AND_SHARE, A_COOKIE);

    Response response = createShare(file, C_ID, SharePermission.READ_ONLY, B_COOKIE);

    assertNoErrors(response);
    Assertions.assertThat(shareRow(file, C_ID))
        .contains(new ShareRow(SharePermission.READ_ONLY.encode(), true));
  }

  @Test
  void updateSharesRaisingOwnPermissionIsRejected() throws SQLException {
    String file = seedFile("x.txt", LOCAL_ROOT, CONTENT, A_COOKIE);
    seedShare(file, B_ID, SharePermission.READ_AND_SHARE, A_COOKIE);

    Response response =
        updateShares(file, List.of(B_ID), SharePermission.READ_WRITE_AND_SHARE, B_COOKIE);

    assertErrorCodes(response, "SHARE_NOT_FOUND");
    Assertions.assertThat(shareRow(file, B_ID))
        .contains(new ShareRow(SharePermission.READ_AND_SHARE.encode(), true));
  }

  @Test
  void updateSharesLoweringOwnPermissionSucceeds() throws SQLException {
    String file = seedFile("x.txt", LOCAL_ROOT, CONTENT, A_COOKIE);
    seedShare(file, B_ID, SharePermission.READ_WRITE_AND_SHARE, A_COOKIE);

    Response response = updateShares(file, List.of(B_ID), SharePermission.READ_ONLY, B_COOKIE);

    assertNoErrors(response);
    Assertions.assertThat(shareRow(file, B_ID))
        .contains(new ShareRow(SharePermission.READ_ONLY.encode(), true));
  }

  @Test
  void updateSharesAboveTheRequesterPermissionIsRejected() throws SQLException {
    String file = seedFile("x.txt", LOCAL_ROOT, CONTENT, A_COOKIE);
    seedShare(file, B_ID, SharePermission.READ_AND_SHARE, A_COOKIE);
    seedShare(file, C_ID, SharePermission.READ_ONLY, A_COOKIE);

    Response response = updateShares(file, List.of(C_ID), SharePermission.READ_AND_WRITE, B_COOKIE);

    assertErrorCodes(response, "SHARE_NOT_FOUND");
    Assertions.assertThat(shareRow(file, C_ID))
        .contains(new ShareRow(SharePermission.READ_ONLY.encode(), true));
  }

  @Test
  void updateSharesOnRootIsRejected() throws SQLException {
    insertShare(LOCAL_ROOT, C_ID, SharePermission.READ_ONLY);

    Response response =
        updateShares(LOCAL_ROOT, List.of(C_ID), SharePermission.READ_WRITE_AND_SHARE, B_COOKIE);

    assertErrorCodes(response, "SHARE_NOT_FOUND");
    Assertions.assertThat(shareRow(LOCAL_ROOT, C_ID))
        .contains(new ShareRow(SharePermission.READ_ONLY.encode(), true));
  }

  @Test
  void deleteSharesOnRootIsRejected() throws SQLException {
    insertShare(LOCAL_ROOT, C_ID, SharePermission.READ_ONLY);

    Response response = deleteShares(LOCAL_ROOT, List.of(C_ID), B_COOKIE);

    assertErrorCodes(response, "SHARE_NOT_FOUND");
    Assertions.assertThat(shareRow(LOCAL_ROOT, C_ID)).isPresent();
  }

  @Test
  void createCollaborationLinkOnRootIsRejected() throws SQLException {
    Response response = createCollaborationLink(LOCAL_ROOT, SharePermission.READ_ONLY, B_COOKIE);

    assertErrorCodes(response, "NODE_WRITE_ERROR");
    Assertions.assertThat(collaborationLinkCount(LOCAL_ROOT)).isZero();
  }

  @Test
  void invitationOfALinkOnRootSharesNothing() throws SQLException {
    String fileOfA = seedFile("a.txt", LOCAL_ROOT, CONTENT, A_COOKIE);
    String invitationId = insertCollaborationLink(LOCAL_ROOT, SharePermission.READ_ONLY);

    Response response =
        RestAssured.given()
            .redirects()
            .follow(false)
            .header("Cookie", C_COOKIE)
            .get("/invite/" + invitationId);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(404);
    Assertions.assertThat(shareRow(LOCAL_ROOT, C_ID)).isEmpty();
    Assertions.assertThat(shareRow(fileOfA, C_ID)).isEmpty();
  }

  @Test
  void updateNodeOnRootIsRejected() throws SQLException {
    Response response = updateNodeName(LOCAL_ROOT, "renamed", B_COOKIE);

    assertErrorCodes(response, "NODE_NOT_FOUND");
    Assertions.assertThat(nodeName(LOCAL_ROOT)).isEqualTo("ROOT");
  }

  @Test
  void createFolderInTrashRootIsRejected() throws SQLException {
    Response response = createFolder(TRASH_ROOT, "f", A_COOKIE);

    assertErrorCodes(response, "NODE_NOT_FOUND");
    Assertions.assertThat(childrenCount(TRASH_ROOT)).isZero();
  }

  @Test
  void moveNodesToTrashRootIsRejected() throws SQLException {
    String folder = seedFolder("f", LOCAL_ROOT, A_COOKIE);

    Response response = moveNodes(List.of(folder), TRASH_ROOT, A_COOKIE);

    assertErrorCodes(response, "NODE_WRITE_ERROR");
    Assertions.assertThat(parentOf(folder)).isEqualTo(LOCAL_ROOT);
  }

  @Test
  void copyNodesToTrashRootIsRejected() throws SQLException {
    String file = seedFile("x.txt", LOCAL_ROOT, CONTENT, A_COOKIE);

    Response response = copyNodes(List.of(file), TRASH_ROOT, A_COOKIE);

    assertErrorCodes(response, "NODE_WRITE_ERROR");
    Assertions.assertThat(childrenCount(TRASH_ROOT)).isZero();
  }

  @Test
  void copyNodesSkipsARootSource() throws SQLException {
    String folder = seedFolder("f", LOCAL_ROOT, A_COOKIE);

    Response response = copyNodes(List.of(LOCAL_ROOT), folder, A_COOKIE);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(errorCodes(response)).isNotEmpty();
    Assertions.assertThat(childrenCount(folder)).isZero();
  }

  @Test
  void uploadToTrashRootIsRejected() throws SQLException {
    Response response = upload(TRASH_ROOT, null, CONTENT, "x.txt", A_COOKIE);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(404);
    Assertions.assertThat(childrenCount(TRASH_ROOT)).isZero();
    Assertions.assertThat(nodesWithoutOwnerOtherThanRoots()).isZero();
  }

  @Test
  void uploadIntoAFileIsRejected() throws SQLException {
    String file = seedFile("x.txt", LOCAL_ROOT, CONTENT, A_COOKIE);

    Response response = upload(file, null, CONTENT, "y.txt", A_COOKIE);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(404);
    Assertions.assertThat(childrenCount(file)).isZero();
  }

  @Test
  void folderUploadAndMoveIntoLocalRootStillWork() throws SQLException {
    Response folderResponse = createFolder(LOCAL_ROOT, "f", A_COOKIE);
    assertNoErrors(folderResponse);
    String folder =
        (String)
            TestUtils.jsonResponseToMap(folderResponse.getBody().asString(), "createFolder")
                .get("id");
    String nested = seedFile("nested.txt", folder, CONTENT, A_COOKIE);

    Response uploadResponse = upload(LOCAL_ROOT, null, CONTENT, "top.txt", A_COOKIE);
    Response moveResponse = moveNodes(List.of(nested), LOCAL_ROOT, A_COOKIE);

    Assertions.assertThat(uploadResponse.getStatusCode()).isEqualTo(200);
    assertNoErrors(moveResponse);
    Assertions.assertThat(parentOf(nested)).isEqualTo(LOCAL_ROOT);
  }

  @Test
  void sharesOfARootAreNotInherited() throws SQLException {
    insertShare(LOCAL_ROOT, C_ID, SharePermission.READ_ONLY);

    String folder = seedFolder("f", LOCAL_ROOT, A_COOKIE);
    String file = seedFile("x.txt", LOCAL_ROOT, CONTENT, A_COOKIE);

    Assertions.assertThat(shareRow(folder, C_ID)).isEmpty();
    Assertions.assertThat(shareRow(file, C_ID)).isEmpty();
  }

  @Test
  void downloadMultipleOfTrashRootIsNotAServerError() {
    seedFile("x.txt", LOCAL_ROOT, CONTENT, A_COOKIE);

    Response response = downloadMultiple(List.of(TRASH_ROOT), A_COOKIE);

    Assertions.assertThat(response.getStatusCode()).isLessThan(500);
  }

  @Test
  void createShareOnPaddedLocalRootIsRejected() throws SQLException {
    String fileOfA = seedFile("a.txt", LOCAL_ROOT, CONTENT, A_COOKIE);

    Response response = createShare(PADDED_LOCAL_ROOT, B_ID, SharePermission.READ_ONLY, B_COOKIE);

    Assertions.assertThat(TestUtils.jsonResponseToErrors(response.getBody().asString()))
        .isNotEmpty();
    Assertions.assertThat(shareRow(LOCAL_ROOT, B_ID)).isEmpty();
    Assertions.assertThat(shareRow(fileOfA, B_ID)).isEmpty();
  }

  @Test
  void updateNodeOnPaddedRootIsRejected() throws SQLException {
    Response response = updateNodeName(PADDED_LOCAL_ROOT, "renamed", B_COOKIE);

    Assertions.assertThat(TestUtils.jsonResponseToErrors(response.getBody().asString()))
        .isNotEmpty();
    Assertions.assertThat(nodeName(LOCAL_ROOT)).isEqualTo("ROOT");
  }

  @Test
  void uploadToPaddedLocalRootNeverCreatesANodeWithoutOwner() throws SQLException {
    upload(PADDED_LOCAL_ROOT, null, CONTENT, "x.txt", A_COOKIE);

    Assertions.assertThat(nodesWithoutOwnerOtherThanRoots()).isZero();
  }

  @Test
  void updateSharesLoweringAnotherCollaboratorWithinItsPermissionSucceeds() throws SQLException {
    String file = seedFile("x.txt", LOCAL_ROOT, CONTENT, A_COOKIE);
    seedShare(file, B_ID, SharePermission.READ_AND_SHARE, A_COOKIE);
    seedShare(file, C_ID, SharePermission.READ_WRITE_AND_SHARE, A_COOKIE);

    Response response = updateShares(file, List.of(C_ID), SharePermission.READ_AND_WRITE, B_COOKIE);

    assertNoErrors(response);
    Assertions.assertThat(shareRow(file, C_ID))
        .contains(new ShareRow(SharePermission.READ_AND_WRITE.encode(), true));
  }

  @Test
  void createCollaborationLinkWithoutShareRightIsRejected() throws SQLException {
    String file = seedFile("x.txt", LOCAL_ROOT, CONTENT, A_COOKIE);
    seedShare(file, B_ID, SharePermission.READ_AND_WRITE, A_COOKIE);

    Response response = createCollaborationLink(file, SharePermission.READ_AND_WRITE, B_COOKIE);

    assertErrorCodes(response, "NODE_WRITE_ERROR");
    Assertions.assertThat(collaborationLinkCount(file)).isZero();
  }

  @Test
  void deleteSharesOfOwnShareOnRootIsRejected() throws SQLException {
    insertShare(LOCAL_ROOT, C_ID, SharePermission.READ_ONLY);

    Response response = deleteShares(LOCAL_ROOT, List.of(C_ID), C_COOKIE);

    assertErrorCodes(response, "SHARE_NOT_FOUND");
    Assertions.assertThat(shareRow(LOCAL_ROOT, C_ID)).isPresent();
  }

  private Response createShare(
      String nodeId, String targetUserId, SharePermission permission, String cookie) {
    return graphql(
        GraphqlCommandBuilder.aMutationBuilder("createShare")
            .withString("node_id", nodeId)
            .withString("share_target_id", targetUserId)
            .withEnum("permission", permission)
            .withWantedResultFormat("{ created_at }")
            .build(),
        cookie);
  }

  private Response updateShares(
      String nodeId, List<String> targetUserIds, SharePermission permission, String cookie) {
    return graphql(
        GraphqlCommandBuilder.aMutationBuilder("updateShares")
            .withString("node_id", nodeId)
            .withListOfStrings("share_target_ids", targetUserIds.toArray(String[]::new))
            .withEnum("permission", permission)
            .withWantedResultFormat("{ permission }")
            .build(),
        cookie);
  }

  private Response deleteShares(String nodeId, List<String> targetUserIds, String cookie) {
    return graphql(
        GraphqlCommandBuilder.aMutationBuilder("deleteShares")
            .withString("node_id", nodeId)
            .withListOfStrings("share_target_ids", targetUserIds.toArray(String[]::new))
            .withWantedResultFormat("")
            .build(),
        cookie);
  }

  private Response createCollaborationLink(
      String nodeId, SharePermission permission, String cookie) {
    return graphql(
        GraphqlCommandBuilder.aMutationBuilder("createCollaborationLink")
            .withString("node_id", nodeId)
            .withEnumLiteral("permission", permission.name())
            .withWantedResultFormat("{ url }")
            .build(),
        cookie);
  }

  private Response updateNodeName(String nodeId, String name, String cookie) {
    return graphql(
        GraphqlCommandBuilder.aMutationBuilder("updateNode")
            .withString("node_id", nodeId)
            .withString("name", name)
            .withWantedResultFormat("{ id name }")
            .build(),
        cookie);
  }

  private Response createFolder(String parentId, String name, String cookie) {
    return graphql(
        GraphqlCommandBuilder.aMutationBuilder("createFolder")
            .withString("destination_id", parentId)
            .withString("name", name)
            .withWantedResultFormat("{ id }")
            .build(),
        cookie);
  }

  private Response moveNodes(List<String> nodeIds, String destinationId, String cookie) {
    return graphql(
        GraphqlCommandBuilder.aMutationBuilder("moveNodes")
            .withListOfStrings("node_ids", nodeIds.toArray(String[]::new))
            .withString("destination_id", destinationId)
            .withWantedResultFormat("{ id }")
            .build(),
        cookie);
  }

  private Response copyNodes(List<String> nodeIds, String destinationId, String cookie) {
    return graphql(
        GraphqlCommandBuilder.aMutationBuilder("copyNodes")
            .withListOfStrings("node_ids", nodeIds.toArray(String[]::new))
            .withString("destination_id", destinationId)
            .withWantedResultFormat("{ id }")
            .build(),
        cookie);
  }

  private static void assertErrorCodes(Response response, String expectedCode) {
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(errorCodes(response)).containsOnly(expectedCode);
  }

  @SuppressWarnings("unchecked")
  private static List<String> errorCodes(Response response) {
    try {
      Map<String, Object> body =
          new ObjectMapper().readValue(response.getBody().asString(), Map.class);
      List<Map<String, Object>> errors = (List<Map<String, Object>>) body.get("errors");
      return errors == null
          ? List.of()
          : errors.stream()
              .map(error -> (Map<String, Object>) error.get("extensions"))
              .filter(extensions -> extensions != null && extensions.get("errorCode") != null)
              .map(extensions -> String.valueOf(extensions.get("errorCode")))
              .toList();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static void assertNoErrors(Response response) {
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(response.getBody().asString())).isEmpty();
  }

  private record ShareRow(short rights, boolean direct) {}

  private static Optional<ShareRow> shareRow(String nodeId, String targetUserId)
      throws SQLException {
    try (Connection connection = jdbcConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT rights, direct FROM share WHERE node_id = ? AND target_uuid = ?")) {
      statement.setString(1, nodeId);
      statement.setString(2, targetUserId);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next()
            ? Optional.of(new ShareRow(rows.getShort(1), rows.getBoolean(2)))
            : Optional.empty();
      }
    }
  }

  private static void insertShare(String nodeId, String targetUserId, SharePermission permission)
      throws SQLException {
    try (Connection connection = jdbcConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO share (node_id, rights, timestamp, target_uuid, direct)"
                    + " VALUES (?, ?, ?, ?, TRUE)")) {
      statement.setString(1, nodeId);
      statement.setShort(2, permission.encode());
      statement.setLong(3, System.currentTimeMillis());
      statement.setString(4, targetUserId);
      statement.executeUpdate();
    }
  }

  private static String insertCollaborationLink(String nodeId, SharePermission permission)
      throws SQLException {
    String invitationId = "rootlink";
    try (Connection connection = jdbcConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO collaboration_link (id, node_id, invitation_id, created_at,"
                    + " permissions) VALUES (?, ?, ?, ?, ?)")) {
      statement.setObject(1, UUID.randomUUID());
      statement.setString(2, nodeId);
      statement.setString(3, invitationId);
      statement.setTimestamp(4, new Timestamp(System.currentTimeMillis()));
      statement.setShort(5, permission.encode());
      statement.executeUpdate();
    }
    return invitationId;
  }

  private static int collaborationLinkCount(String nodeId) throws SQLException {
    return count("SELECT count(*) FROM collaboration_link WHERE node_id = ?", nodeId);
  }

  private static int childrenCount(String parentId) throws SQLException {
    return count("SELECT count(*) FROM node WHERE folder_id = ?", parentId);
  }

  private static int nodesWithoutOwnerOtherThanRoots() throws SQLException {
    return count(
        "SELECT count(*) FROM node WHERE owner_id IS NULL AND node_id NOT IN (?, 'TRASH_ROOT')",
        LOCAL_ROOT);
  }

  private static String nodeName(String nodeId) throws SQLException {
    return singleString("SELECT name FROM node WHERE node_id = ?", nodeId);
  }

  private static String parentOf(String nodeId) throws SQLException {
    return singleString("SELECT trim(folder_id) FROM node WHERE node_id = ?", nodeId);
  }

  private static int count(String sql, String parameter) throws SQLException {
    try (Connection connection = jdbcConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, parameter);
      try (ResultSet rows = statement.executeQuery()) {
        rows.next();
        return rows.getInt(1);
      }
    }
  }

  private static String singleString(String sql, String parameter) throws SQLException {
    try (Connection connection = jdbcConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, parameter);
      try (ResultSet rows = statement.executeQuery()) {
        rows.next();
        return rows.getString(1);
      }
    }
  }
}
