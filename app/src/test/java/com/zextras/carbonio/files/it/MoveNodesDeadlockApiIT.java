// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.response.Response;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MoveNodesDeadlockApiIT extends AbstractFilesIT {

  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String REQUESTER_COOKIE = "ZM_AUTH_TOKEN=fake-token";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", REQUESTER_ID);
  }

  private Response moveNodes(String nodeId, String destinationId) {
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("moveNodes")
            .withListOfStrings("node_ids", new String[] {nodeId})
            .withString("destination_id", destinationId)
            .withWantedResultFormat("{ id }")
            .build();
    return graphql(bodyPayload, REQUESTER_COOKIE);
  }

  private static String nodeColumn(String column, String nodeId) throws SQLException {
    try (Connection connection = jdbcConnection();
        PreparedStatement statement =
            connection.prepareStatement("SELECT " + column + " FROM node WHERE node_id = ?")) {
      statement.setString(1, nodeId);
      try (ResultSet result = statement.executeQuery()) {
        Assertions.assertThat(result.next()).isTrue();
        return result.getString(1).trim();
      }
    }
  }

  @Test
  void givenTwoConcurrentMovesOfANestedFolderIntoTheSameDestinationBothShouldSucceed()
      throws Exception {
    // Given
    String folderX = seedFolder("x", LOCAL_ROOT, REQUESTER_COOKIE);
    String folderY = seedFolder("y", folderX, REQUESTER_COOKIE);
    String destination = seedFolder("d", LOCAL_ROOT, REQUESTER_COOKIE);

    Response moveX;
    Response moveY;
    try (Connection nameLock = jdbcConnection()) {
      nameLock.setAutoCommit(false);
      try (PreparedStatement lock =
          nameLock.prepareStatement(
              "SELECT pg_advisory_xact_lock(hashtextextended(? || '/' || ?, 0))")) {
        lock.setString(1, destination);
        lock.setString(2, REQUESTER_ID);
        lock.execute();
      }

      // When
      CompletableFuture<Response> firstMove =
          CompletableFuture.supplyAsync(() -> moveNodes(folderX, destination));
      awaitBlockedBy(nameLock, 1);
      CompletableFuture<Response> secondMove =
          CompletableFuture.supplyAsync(() -> moveNodes(folderY, destination));
      awaitBlockedBy(nameLock, 2);
      nameLock.commit();
      moveX = firstMove.get(60, TimeUnit.SECONDS);
      moveY = secondMove.get(60, TimeUnit.SECONDS);
    }

    // Then
    Assertions.assertThat(moveX.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(moveX.jsonPath().getList("errors")).isNull();
    Assertions.assertThat(moveY.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(moveY.jsonPath().getList("errors")).isNull();
    Assertions.assertThat(nodeColumn("folder_id", folderX)).isEqualTo(destination);
    Assertions.assertThat(nodeColumn("folder_id", folderY)).isEqualTo(destination);
    Assertions.assertThat(nodeColumn("ancestor_ids", folderY))
        .isEqualTo(LOCAL_ROOT + "," + destination);
  }
}
