// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InFlightUploadApiIT extends AbstractFilesIT {

  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String REQUESTER_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String INFLIGHT_ID = "00000000-0000-0000-0000-00000000f1f1";

  private String folderId;
  private String okId;

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", REQUESTER_ID);
  }

  @BeforeEach
  void seedFolderWithOneCompleteAndOneInFlightFile() throws SQLException {
    folderId = seedFolder("INFLIGHT", LOCAL_ROOT, REQUESTER_COOKIE);
    okId = seedFile("ok.txt", folderId, "ok".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    seedInconsistentNode(
        INFLIGHT_ID,
        REQUESTER_ID,
        REQUESTER_ID,
        folderId,
        "inflight.txt",
        NodeType.TEXT,
        "LOCAL_ROOT," + folderId,
        0L,
        "text/plain");
    try (Connection c = jdbcConnection();
        PreparedStatement ps = c.prepareStatement("DELETE FROM revision WHERE node_id = ?")) {
      ps.setString(1, INFLIGHT_ID);
      ps.executeUpdate();
    }
  }

  private static JsonNode body(Response response) {
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    try {
      return new ObjectMapper().readTree(response.getBody().asString());
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  private static List<String> errorCodes(JsonNode body) {
    List<String> codes = new ArrayList<>();
    body.path("errors")
        .forEach(
            e -> {
              JsonNode code = e.path("extensions").path("errorCode");
              if (!code.isMissingNode() && !code.isNull()) {
                codes.add(code.asText());
              }
            });
    return codes;
  }

  private static JsonNode versionNotFoundError(JsonNode body) {
    for (JsonNode e : body.path("errors")) {
      if ("FILE_VERSION_NOT_FOUND".equals(e.path("extensions").path("errorCode").asText())) {
        return e;
      }
    }
    throw new AssertionError("no FILE_VERSION_NOT_FOUND error in " + body);
  }

  private static void assertInFlightError(JsonNode body) {
    Assertions.assertThat(errorCodes(body)).containsExactly("FILE_VERSION_NOT_FOUND");
    JsonNode error = versionNotFoundError(body);
    Assertions.assertThat(error.get("message").asText())
        .isEqualTo("Could not find version: 1 for node with id " + INFLIGHT_ID);
    Assertions.assertThat(error.path("extensions").path("nodeId").asText()).isEqualTo(INFLIGHT_ID);
    Assertions.assertThat(error.path("extensions").path("fileVersion").asInt()).isEqualTo(1);
  }

  @Test
  void givenAnInFlightUploadChildrenShouldNullOnlyThatElementAndKeepTheRestOfThePage() {
    String query =
        GraphqlCommandBuilder.aQueryBuilder("getNode")
            .withString("node_id", folderId)
            .withWantedResultFormat(
                "{ id ... on Folder { children(limit: 10, sort: NAME_ASC) { nodes { id name ... on"
                    + " File { size mime_type version } } page_token } } }")
            .build();

    JsonNode body = body(graphql(query, REQUESTER_COOKIE));

    JsonNode nodes = body.path("data").path("getNode").path("children").path("nodes");
    Assertions.assertThat(nodes).hasSize(2);
    Assertions.assertThat(nodes.get(0).isNull()).isTrue();
    Assertions.assertThat(nodes.get(1).path("id").asText()).isEqualTo(okId);
    Assertions.assertThat(nodes.get(1).path("size").asInt()).isEqualTo(2);
    assertInFlightError(body);
  }

  @Test
  void givenAnInFlightUploadGetNodeShouldReturnTheBaseFieldsAlongsideTheError() {
    String query =
        GraphqlCommandBuilder.aQueryBuilder("getNode")
            .withString("node_id", INFLIGHT_ID)
            .withWantedResultFormat("{ id name type }")
            .build();

    JsonNode body = body(graphql(query, REQUESTER_COOKIE));

    JsonNode node = body.path("data").path("getNode");
    Assertions.assertThat(node.path("id").asText()).isEqualTo(INFLIGHT_ID);
    Assertions.assertThat(node.path("name").asText()).isEqualTo("inflight");
    assertInFlightError(body);
  }
}
