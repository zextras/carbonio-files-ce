// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.response.Response;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RaceVersionCapIT extends AbstractFilesIT {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final String OWNER_ID = "63111111-1111-1111-1111-111111111111";
  private static final String OWNER_COOKIE = "ZM_AUTH_TOKEN=race-version-cap-owner";
  private static final String MAX_NUMBER_OF_VERSIONS = "max-number-of-versions";

  private static final String REVISION_ROW_LOCK =
      "SELECT 1 FROM revision WHERE node_id = ? AND version = 1 FOR UPDATE";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService()
        .registerToken("race-version-cap-owner", OWNER_ID);
  }

  @BeforeEach
  void capVersionsToThree() {
    setApplicationConfig(MAX_NUMBER_OF_VERSIONS, "3");
  }

  @AfterEach
  void restoreVersionCap() {
    clearApplicationConfig(MAX_NUMBER_OF_VERSIONS, "30");
  }

  private static Response keepVersions(String nodeId, int version) {
    return graphql(
        GraphqlCommandBuilder.aMutationBuilder("keepVersions")
            .withString("node_id", nodeId)
            .withListOfIntegers("versions", new int[] {version})
            .withBoolean("keep_forever", true)
            .withWantedResultFormat("")
            .build(),
        OWNER_COOKIE);
  }

  private static List<String> errorCodes(Response response) throws Exception {
    JsonNode root = OBJECT_MAPPER.readTree(response.getBody().asString());
    List<String> codes = new ArrayList<>();
    if (root.has("errors")) {
      for (JsonNode error : root.get("errors")) {
        codes.add(error.get("extensions").get("errorCode").asText());
      }
    }
    return codes;
  }

  private static int keptForeverCount(String nodeId) throws Exception {
    try (Connection connection = jdbcConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT count(*) FROM revision WHERE node_id = ? AND keep_forever")) {
      statement.setString(1, nodeId);
      try (ResultSet rows = statement.executeQuery()) {
        rows.next();
        return rows.getInt(1);
      }
    }
  }

  @Test
  void givenTwoKeepVersionsAtTheCapTogetherThenTheCapHolds() throws Exception {
    byte[] content = "v".getBytes();
    String file = seedFile("race-version-cap.txt", LOCAL_ROOT, content, OWNER_COOKIE);
    seedVersion(file, content, "race-version-cap.txt", OWNER_COOKIE);

    List<CompletableFuture<Response>> pending = new ArrayList<>();
    try (Connection holder = holdRow(REVISION_ROW_LOCK, file)) {
      try {
        CompletableFuture<Response> first =
            CompletableFuture.supplyAsync(() -> keepVersions(file, 1), RACE_EXECUTOR);
        pending.add(first);
        awaitBlockedBy(holder, 1);
        CompletableFuture<Response> second =
            CompletableFuture.supplyAsync(() -> keepVersions(file, 2), RACE_EXECUTOR);
        pending.add(second);
        awaitBlockedOrDone(holder, 2, first, second);
      } finally {
        holder.rollback();
      }
      for (CompletableFuture<Response> future : pending) {
        future.get(30, TimeUnit.SECONDS);
      }
    } finally {
      for (CompletableFuture<Response> future : pending) {
        try {
          future.get(30, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
      }
    }

    List<String> codes = new ArrayList<>();
    for (CompletableFuture<Response> future : pending) {
      codes.addAll(errorCodes(future.get()));
    }
    Assertions.assertThat(keptForeverCount(file)).isEqualTo(1);
    Assertions.assertThat(codes).containsExactly("VERSIONS_LIMIT_REACHED");
  }
}
