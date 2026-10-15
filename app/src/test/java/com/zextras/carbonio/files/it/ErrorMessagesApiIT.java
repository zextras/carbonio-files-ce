// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ErrorMessagesApiIT extends AbstractFilesIT {

  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OTHER_USER_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String REQUESTER_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String OTHER_COOKIE = "ZM_AUTH_TOKEN=fake-token-b";
  private static final String UNKNOWN_NODE_ID = "00000000-0000-0000-0000-00000000dead";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", REQUESTER_ID);
    FilesStackTestResource.getUserManagementService().registerToken("fake-token-b", OTHER_USER_ID);
  }

  private static JsonNode body(Response response) {
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    try {
      return new ObjectMapper().readTree(response.getBody().asString());
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  private static boolean isNullData(JsonNode body, String field) {
    JsonNode data = body.get("data");
    return data == null || data.isNull() || data.path(field).isNull();
  }

  private static JsonNode onlyError(JsonNode body) {
    Assertions.assertThat(body.get("errors")).hasSize(1);
    return body.get("errors").get(0);
  }

  private static Set<String> extensionKeys(JsonNode error) {
    Set<String> keys = new TreeSet<>();
    error.get("extensions").fieldNames().forEachRemaining(keys::add);
    return keys;
  }

  private static void assertError(
      JsonNode error, String code, String message, String... extraKeysAndValues) {
    Assertions.assertThat(error.get("message").asText()).isEqualTo(message);
    JsonNode extensions = error.get("extensions");
    Assertions.assertThat(extensions.get("classification").asText())
        .isEqualTo("DataFetchingException");
    Assertions.assertThat(extensions.get("errorCode").asText()).isEqualTo(code);
    Set<String> expectedKeys = new TreeSet<>(Set.of("classification", "errorCode"));
    for (int i = 0; i < extraKeysAndValues.length; i += 2) {
      expectedKeys.add(extraKeysAndValues[i]);
      Assertions.assertThat(extensions.get(extraKeysAndValues[i]).asText())
          .isEqualTo(extraKeysAndValues[i + 1]);
    }
    Assertions.assertThat(extensionKeys(error)).isEqualTo(expectedKeys);
  }

  @Test
  void givenAnUnknownNodeGetNodeShouldReturnNullDataAndMainsNodeNotFoundError() {
    String query =
        GraphqlCommandBuilder.aQueryBuilder("getNode")
            .withString("node_id", UNKNOWN_NODE_ID)
            .withWantedResultFormat("{ id }")
            .build();

    JsonNode body = body(graphql(query, REQUESTER_COOKIE));

    Assertions.assertThat(isNullData(body, "getNode")).isTrue();
    assertError(
        onlyError(body),
        "NODE_NOT_FOUND",
        "Could not find node with id " + UNKNOWN_NODE_ID,
        "nodeId",
        UNKNOWN_NODE_ID);
  }

  @Test
  void givenAMissingShareGetShareShouldCarryNodeIdAndUserId() {
    String nodeId =
        seedFile("s.txt", LOCAL_ROOT, "s".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    String query =
        GraphqlCommandBuilder.aQueryBuilder("getShare")
            .withString("node_id", nodeId)
            .withString("share_target_id", OTHER_USER_ID)
            .withWantedResultFormat("{ created_at }")
            .build();

    JsonNode body = body(graphql(query, REQUESTER_COOKIE));

    Assertions.assertThat(isNullData(body, "getShare")).isTrue();
    assertError(
        onlyError(body),
        "SHARE_NOT_FOUND",
        "Could not find share for node: " + nodeId + " and user " + OTHER_USER_ID,
        "nodeId",
        nodeId,
        "userId",
        OTHER_USER_ID);
  }

  @Test
  void givenADuplicateNameUpdateNodeShouldCarryNodeIdAndDestinationFolderId() {
    String folderId = seedFolder("dup-parent", LOCAL_ROOT, REQUESTER_COOKIE);
    seedFolder("taken", folderId, REQUESTER_COOKIE);
    String nodeId = seedFolder("other", folderId, REQUESTER_COOKIE);
    String mutation =
        GraphqlCommandBuilder.aMutationBuilder("updateNode")
            .withString("node_id", nodeId)
            .withString("name", "taken")
            .withWantedResultFormat("{ id }")
            .build();

    JsonNode body = body(graphql(mutation, REQUESTER_COOKIE));

    Assertions.assertThat(isNullData(body, "updateNode")).isTrue();
    assertError(
        onlyError(body),
        "NODE_DUPLICATED",
        "Trying to create a duplicate for the node "
            + nodeId
            + " in destination folder "
            + folderId,
        "nodeId",
        nodeId,
        "destinationFolderId",
        folderId);
  }

  @Test
  void givenAnUnknownLinkUpdateLinkShouldCarryTheLinkId() {
    String linkId = "11111111-1111-1111-1111-111111111111";
    String mutation =
        GraphqlCommandBuilder.aMutationBuilder("updateLink")
            .withString("link_id", linkId)
            .withString("description", "d")
            .withWantedResultFormat("{ id }")
            .build();

    JsonNode body = body(graphql(mutation, REQUESTER_COOKIE));

    Assertions.assertThat(isNullData(body, "updateLink")).isTrue();
    assertError(
        onlyError(body),
        "LINK_NOT_FOUND",
        "Could not find link with id " + linkId,
        "linkId",
        linkId);
  }

  @Test
  void givenAMissingVersionDeleteVersionsShouldEmitTheVersionAsANumberAlongsideTheData() {
    String nodeId =
        seedFile("v.txt", LOCAL_ROOT, "v1".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    seedVersion(nodeId, "v2".getBytes(StandardCharsets.UTF_8), "v.txt", REQUESTER_COOKIE);
    String mutation =
        GraphqlCommandBuilder.aMutationBuilder("deleteVersions")
            .withString("node_id", nodeId)
            .withListOfIntegers("versions", new int[] {1, 7})
            .withWantedResultFormat("")
            .build();

    JsonNode body = body(graphql(mutation, REQUESTER_COOKIE));

    List<Integer> data = new ArrayList<>();
    body.path("data").path("deleteVersions").forEach(v -> data.add(v.asInt()));
    Assertions.assertThat(data).containsExactly(1);
    JsonNode error = onlyError(body);
    assertError(
        error,
        "FILE_VERSION_NOT_FOUND",
        "Could not find version: 7 for node with id " + nodeId,
        "nodeId",
        nodeId,
        "fileVersion",
        "7");
    Assertions.assertThat(error.get("extensions").get("fileVersion").isNumber()).isTrue();
  }

  @Test
  void givenNoReadPermissionGetVersionsShouldReturnANullElementAndNodeWriteError() {
    String nodeId =
        seedFile("p.txt", LOCAL_ROOT, "p".getBytes(StandardCharsets.UTF_8), OTHER_COOKIE);
    String query =
        GraphqlCommandBuilder.aQueryBuilder("getVersions")
            .withString("node_id", nodeId)
            .withWantedResultFormat("{ version }")
            .build();

    JsonNode body = body(graphql(query, REQUESTER_COOKIE));

    JsonNode data = body.path("data").path("getVersions");
    Assertions.assertThat(data).hasSize(1);
    Assertions.assertThat(data.get(0).isNull()).isTrue();
    assertError(
        onlyError(body),
        "NODE_WRITE_ERROR",
        "There was a problem while executing requested operation on node: " + nodeId,
        "nodeId",
        nodeId);
  }

  @Test
  void givenNoPermissionGetLinksShouldReturnASingleNullElementWithoutAnError() {
    String nodeId =
        seedFile("l.txt", LOCAL_ROOT, "l".getBytes(StandardCharsets.UTF_8), OTHER_COOKIE);
    String query =
        GraphqlCommandBuilder.aQueryBuilder("getLinks")
            .withString("node_id", nodeId)
            .withWantedResultFormat("{ id }")
            .build();

    JsonNode body = body(graphql(query, REQUESTER_COOKIE));

    JsonNode links = body.path("data").path("getLinks");
    Assertions.assertThat(links).hasSize(1);
    Assertions.assertThat(links.get(0).isNull()).isTrue();
    Assertions.assertThat(body.path("errors").isMissingNode()).isTrue();
  }

  @Test
  void givenNoPermissionGetCollaborationLinksShouldReturnANullElementAndNodeWriteError() {
    String nodeId =
        seedFile("c.txt", LOCAL_ROOT, "c".getBytes(StandardCharsets.UTF_8), OTHER_COOKIE);
    String query =
        GraphqlCommandBuilder.aQueryBuilder("getCollaborationLinks")
            .withString("node_id", nodeId)
            .withWantedResultFormat("{ id }")
            .build();

    JsonNode body = body(graphql(query, REQUESTER_COOKIE));

    JsonNode links = body.path("data").path("getCollaborationLinks");
    Assertions.assertThat(links).hasSize(1);
    Assertions.assertThat(links.get(0).isNull()).isTrue();
    assertError(
        onlyError(body),
        "NODE_WRITE_ERROR",
        "There was a problem while executing requested operation on node: " + nodeId,
        "nodeId",
        nodeId);
  }

  @Test
  void givenAPartiallyFailedRestoreNodesTheDataKeepsANullSlotPerFailedNode() {
    String trashedId = seedFolder("to-restore", LOCAL_ROOT, REQUESTER_COOKIE);
    seedTrashed(trashedId, REQUESTER_COOKIE);
    String notTrashedId = seedFolder("not-trashed", LOCAL_ROOT, REQUESTER_COOKIE);
    String mutation =
        GraphqlCommandBuilder.aMutationBuilder("restoreNodes")
            .withListOfStrings("node_ids", new String[] {trashedId, notTrashedId})
            .withWantedResultFormat("{ id }")
            .build();

    JsonNode body = body(graphql(mutation, REQUESTER_COOKIE));

    JsonNode restored = body.path("data").path("restoreNodes");
    Assertions.assertThat(restored).hasSize(2);
    Assertions.assertThat(restored.get(0).path("id").asText()).isEqualTo(trashedId);
    Assertions.assertThat(restored.get(1).isNull()).isTrue();
    assertError(
        onlyError(body),
        "NODE_WRITE_ERROR",
        "There was a problem while executing requested operation on node: " + notTrashedId,
        "nodeId",
        notTrashedId);
  }

  @Test
  void givenAFailedItemInABulkOperationTheSingleErrorCarriesTheFirstFailedNodeId() {
    String okId = seedFolder("ok", LOCAL_ROOT, REQUESTER_COOKIE);
    String mutation =
        GraphqlCommandBuilder.aMutationBuilder("trashNodes")
            .withListOfStrings("node_ids", new String[] {okId, UNKNOWN_NODE_ID})
            .withWantedResultFormat("")
            .build();

    JsonNode body = body(graphql(mutation, REQUESTER_COOKIE));

    Assertions.assertThat(body.path("data").path("trashNodes")).hasSize(1);
    Assertions.assertThat(body.path("data").path("trashNodes").get(0).asText()).isEqualTo(okId);
    assertError(
        onlyError(body),
        "NODE_WRITE_ERROR",
        "There was a problem while executing requested operation on node: " + UNKNOWN_NODE_ID,
        "nodeId",
        UNKNOWN_NODE_ID);
  }

  @Test
  void givenGhostOwnerAndLastEditorEachScopedErrorCarriesTheIdentifier() throws SQLException {
    String ghostOwnerId = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeed";
    String nodeId = "00000000-0000-0000-0000-0000000000d5";
    seedInconsistentNode(
        nodeId,
        REQUESTER_ID,
        ghostOwnerId,
        LOCAL_ROOT,
        "ghost.txt",
        NodeType.TEXT,
        LOCAL_ROOT,
        1L,
        "text/plain");
    seedShareRawJdbc(nodeId, REQUESTER_ID, ACL.SharePermission.READ_ONLY);
    String query =
        GraphqlCommandBuilder.aQueryBuilder("getNode")
            .withString("node_id", nodeId)
            .withWantedResultFormat("{ id owner { id } last_editor { id } }")
            .build();

    JsonNode body = body(graphql(query, REQUESTER_COOKIE));

    JsonNode node = body.path("data").path("getNode");
    Assertions.assertThat(node.path("id").asText()).isEqualTo(nodeId);
    Assertions.assertThat(node.path("owner").isNull()).isTrue();
    Assertions.assertThat(node.path("last_editor").isNull()).isTrue();
    Assertions.assertThat(body.get("errors")).hasSize(2);
    for (JsonNode error : body.get("errors")) {
      assertError(
          error,
          "ACCOUNT_NOT_FOUND",
          "Could not find user with identifier " + ghostOwnerId,
          "identifier",
          ghostOwnerId);
    }
  }

  @Test
  void givenAnErrorWithoutExtrasNoExtraKeyIsEmittedAsNull() {
    String query = "{ getNode(node_id: \\\"short\\\") { id } }";

    JsonNode body = body(graphql(query, REQUESTER_COOKIE));

    JsonNode error = onlyError(body);
    Assertions.assertThat(extensionKeys(error))
        .containsExactlyInAnyOrder("classification", "errorCode");
    Assertions.assertThat(error.get("extensions").get("errorCode").isNull()).isTrue();
    Assertions.assertThat(error.get("extensions").get("classification").asText())
        .isEqualTo("DataFetchingException");
  }
}
