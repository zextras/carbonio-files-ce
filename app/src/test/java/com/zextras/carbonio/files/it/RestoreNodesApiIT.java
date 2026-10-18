// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code com.zextras.carbonio.files.acceptance.RestoreNodesApiIT} rewritten as an out-of-process
 * {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}. Both methods and their assertions are
 * preserved verbatim; only the seeding mechanism (API calls capturing server-generated ids, real
 * {@code trashNodes} mutation instead of the seam's direct backdoor trash) and transport changed.
 */
class RestoreNodesApiIT extends AbstractFilesIT {

  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String REQUESTER_COOKIE = "ZM_AUTH_TOKEN=fake-token";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", REQUESTER_ID);
  }

  private Response restoreNodes(String[] nodeIds, String cookie) {
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("restoreNodes")
            .withListOfStrings("node_ids", nodeIds)
            .withWantedResultFormat("{ id name }")
            .build();
    return graphql(bodyPayload, cookie);
  }

  @Test
  void givenATrashedNodeRestoreNodesShouldRestoreThatNode() {
    // Given
    String nodeId =
        seedFile(
            "fake.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    seedTrashed(nodeId, REQUESTER_COOKIE);

    // When
    Response response = restoreNodes(new String[] {nodeId}, REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Map<String, Object> page =
        TestUtils.jsonResponseToMap(response.getBody().asString(), "restoreNodes");
    List<Map<String, Object>> nodes = (List<Map<String, Object>>) page.get("data");

    Assertions.assertThat(nodes).hasSize(1);
    // folders always on top
    Assertions.assertThat(nodes.get(0)).containsEntry("id", nodeId).containsEntry("name", "fake");
  }

  @Test
  void
      givenTwoFilesOnWithTheSameNameAndOneIsTrashedBothWithSameParentDirectoryRestoreNodeShouldRestoreFileWithDifferentNameFromAlreadyExisting() {
    // Given
    String trashedNodeId =
        seedFile(
            "fake.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    seedTrashed(trashedNodeId, REQUESTER_COOKIE);

    // a second, NOT trashed, node with the SAME full name already occupies LOCAL_ROOT
    seedFile("fake.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);

    // When
    Response response = restoreNodes(new String[] {trashedNodeId}, REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Map<String, Object> page =
        TestUtils.jsonResponseToMap(response.getBody().asString(), "restoreNodes");
    List<Map<String, Object>> nodes = (List<Map<String, Object>>) page.get("data");

    Assertions.assertThat(nodes).hasSize(1);
    // folders always on top
    Assertions.assertThat(nodes.get(0))
        .containsEntry("id", trashedNodeId)
        .containsEntry("name", "fake (1)");
  }

  @Test
  void givenANodeRestoredByAConcurrentRequestRestoreNodesShouldReportItAsAWriteError()
      throws Exception {
    // Given
    String folderP = seedFolder("p", LOCAL_ROOT, REQUESTER_COOKIE);
    String nodeN =
        seedFile("n.txt", folderP, "content".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    seedTrashed(nodeN, REQUESTER_COOKIE);

    Response response;
    try (Connection restorer = jdbcConnection()) {
      restorer.setAutoCommit(false);
      try (PreparedStatement lock =
          restorer.prepareStatement("SELECT node_id FROM node WHERE node_id = ? FOR UPDATE")) {
        lock.setString(1, nodeN);
        lock.execute();
      }
      try (PreparedStatement update =
          restorer.prepareStatement(
              "UPDATE node SET folder_id = ?, ancestor_ids = ? WHERE node_id = ?")) {
        update.setString(1, folderP);
        update.setString(2, LOCAL_ROOT + "," + folderP);
        update.setString(3, nodeN);
        update.executeUpdate();
      }
      try (PreparedStatement delete =
          restorer.prepareStatement("DELETE FROM trashed WHERE node_id = ?")) {
        delete.setString(1, nodeN);
        delete.executeUpdate();
      }

      // When
      CompletableFuture<Response> loser =
          CompletableFuture.supplyAsync(() -> restoreNodes(new String[] {nodeN}, REQUESTER_COOKIE));
      awaitBlockedBy(restorer);
      restorer.commit();
      response = loser.get(60, TimeUnit.SECONDS);
    }

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToErrorCodes(response.getBody().asString()))
        .containsExactly("NODE_WRITE_ERROR");
    Assertions.assertThat(response.jsonPath().getList("data.restoreNodes"))
        .containsExactly((Object) null);
    try (Connection connection = jdbcConnection();
        PreparedStatement statement =
            connection.prepareStatement("SELECT name, folder_id FROM node WHERE node_id = ?")) {
      statement.setString(1, nodeN);
      try (ResultSet row = statement.executeQuery()) {
        Assertions.assertThat(row.next()).isTrue();
        Assertions.assertThat(row.getString(1)).isEqualTo("n.txt");
        Assertions.assertThat(row.getString(2).trim()).isEqualTo(folderP);
        Assertions.assertThat(row.next()).isFalse();
      }
    }
  }
}
