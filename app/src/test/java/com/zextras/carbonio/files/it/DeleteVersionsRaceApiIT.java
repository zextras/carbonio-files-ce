// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
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
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class DeleteVersionsRaceApiIT extends AbstractFilesIT {

  private static final String OWNER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String COOKIE = "ZM_AUTH_TOKEN=fake-token";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", OWNER_ID);
  }

  private static Response deleteVersions(String nodeId, int... versions) {
    GraphqlCommandBuilder builder =
        GraphqlCommandBuilder.aMutationBuilder("deleteVersions").withString("node_id", nodeId);
    if (versions.length > 0) {
      builder = builder.withListOfIntegers("versions", versions);
    }
    return graphql(builder.withWantedResultFormat("").build(), COOKIE);
  }

  @SuppressWarnings("unchecked")
  private static List<Integer> deleted(Response response) {
    return (List<Integer>)
        TestUtils.jsonResponseToValue(response.getBody().asString(), "deleteVersions")
            .orElse(List.of());
  }

  private static String fileWithThreeVersions() {
    String nodeId = seedFile("file.txt", LOCAL_ROOT, "v1".getBytes(StandardCharsets.UTF_8), COOKIE);
    seedVersion(nodeId, "v2".getBytes(StandardCharsets.UTF_8), "file.txt", COOKIE);
    seedVersion(nodeId, "v3".getBytes(StandardCharsets.UTF_8), "file.txt", COOKIE);
    return nodeId;
  }

  private static List<Integer> remainingVersions(String nodeId) throws SQLException {
    try (Connection connection = jdbcConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT version FROM revision WHERE node_id = ? ORDER BY version")) {
      statement.setString(1, nodeId);
      try (ResultSet rows = statement.executeQuery()) {
        List<Integer> versions = new ArrayList<>();
        while (rows.next()) {
          versions.add(rows.getInt(1));
        }
        return versions;
      }
    }
  }

  private static int currentVersion(String nodeId) throws SQLException {
    try (Connection connection = jdbcConnection();
        PreparedStatement statement =
            connection.prepareStatement("SELECT current_version FROM node WHERE node_id = ?")) {
      statement.setString(1, nodeId);
      try (ResultSet rows = statement.executeQuery()) {
        rows.next();
        return rows.getInt(1);
      }
    }
  }

  private static void update(Connection connection, String sql, String nodeId) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, nodeId);
      statement.executeUpdate();
    }
  }

  private static final String KEEP_FIRST_VERSION =
      "UPDATE revision SET keep_forever = true WHERE node_id = ? AND version = 1";

  @Test
  void givenAVersionKeptForeverWhileDeleteVersionsIsInFlightItShouldNotBeDeleted()
      throws Exception {
    String nodeId = fileWithThreeVersions();

    Response response;
    try (Connection holder = jdbcConnection()) {
      holder.setAutoCommit(false);
      try {
        update(holder, KEEP_FIRST_VERSION, nodeId);
        CompletableFuture<Response> deleting =
            CompletableFuture.supplyAsync(() -> deleteVersions(nodeId, 1, 2));
        awaitBlockedBy(holder);
        holder.commit();
        response = deleting.get(60, TimeUnit.SECONDS);
      } finally {
        holder.rollback();
      }
    }

    Assertions.assertThat(deleted(response)).containsExactly(2);
    Assertions.assertThat(remainingVersions(nodeId)).containsExactly(1, 3);
    Assertions.assertThat(tombstoneRowsForNode(nodeId)).isZero();
  }

  @Test
  void givenAVersionKeptForeverWhileDeleteAllVersionsIsInFlightItShouldNotBeDeleted()
      throws Exception {
    String nodeId = fileWithThreeVersions();

    Response response;
    try (Connection holder = jdbcConnection()) {
      holder.setAutoCommit(false);
      try {
        update(holder, KEEP_FIRST_VERSION, nodeId);
        CompletableFuture<Response> deleting =
            CompletableFuture.supplyAsync(() -> deleteVersions(nodeId));
        awaitBlockedBy(holder);
        holder.commit();
        response = deleting.get(60, TimeUnit.SECONDS);
      } finally {
        holder.rollback();
      }
    }

    Assertions.assertThat(deleted(response)).containsExactly(2);
    Assertions.assertThat(remainingVersions(nodeId)).containsExactly(1, 3);
  }

  @Test
  void givenANewCurrentVersionWhileDeleteVersionsIsInFlightItShouldNotBeDeleted() throws Exception {
    String nodeId = fileWithThreeVersions();

    Response response;
    try (Connection holder = jdbcConnection()) {
      holder.setAutoCommit(false);
      try {
        try (Statement statement = holder.createStatement()) {
          statement.execute("LOCK TABLE revision IN ACCESS EXCLUSIVE MODE");
        }
        CompletableFuture<Response> deleting =
            CompletableFuture.supplyAsync(() -> deleteVersions(nodeId));
        awaitBlockedBy(holder);
        update(
            holder,
            "INSERT INTO revision (node_id, version, mime_type, size, digest, editor_id,"
                + " timestamp, is_autosave, keep_forever)"
                + " SELECT node_id, 4, mime_type, size, digest, editor_id, timestamp,"
                + " is_autosave, keep_forever FROM revision WHERE node_id = ? AND version = 3",
            nodeId);
        update(holder, "UPDATE node SET current_version = 4 WHERE node_id = ?", nodeId);
        holder.commit();
        response = deleting.get(60, TimeUnit.SECONDS);
      } finally {
        holder.rollback();
      }
    }

    Assertions.assertThat(deleted(response)).containsExactly(3, 2, 1);
    Assertions.assertThat(remainingVersions(nodeId)).containsExactly(4);
    Assertions.assertThat(currentVersion(nodeId)).isEqualTo(4);
    Assertions.assertThat(tombstoneRowsForNode(nodeId)).isZero();
  }
}
