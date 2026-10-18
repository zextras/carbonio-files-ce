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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code com.zextras.carbonio.files.acceptance.FlagNodesApiIT} rewritten as an out-of-process
 * {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}. All 4 methods and their assertions
 * are preserved verbatim; only the seeding mechanism (API calls capturing server-generated ids) and
 * transport changed.
 */
class FlagNodesApiIT extends AbstractFilesIT {

  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OTHER_USER_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String REQUESTER_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String OTHER_COOKIE = "ZM_AUTH_TOKEN=fake-token-b";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", REQUESTER_ID);
    FilesStackTestResource.getUserManagementService().registerToken("fake-token-b", OTHER_USER_ID);
  }

  private Response flagNodes(String[] nodeIds, String cookie) {
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("flagNodes")
            .withListOfStrings("node_ids", nodeIds)
            .withBoolean("flag", true)
            .withWantedResultFormat("")
            .build();
    return graphql(bodyPayload, cookie);
  }

  @Test
  void givenANodeFlagNodesShouldFlagIt() {
    // Given
    String nodeId =
        seedFile(
            "aFile.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);

    // When
    Response response = flagNodes(new String[] {nodeId}, REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Map<String, Object> page =
        TestUtils.jsonResponseToMap(response.getBody().asString(), "flagNodes");
    List<String> nodes = (List<String>) page.get("data");

    Assertions.assertThat(nodes).hasSize(1);
    Assertions.assertThat(nodes.get(0)).isEqualTo(nodeId);
  }

  @Test
  void givenConcurrentFlagNodesOnTheSameNodeEveryCallShouldSucceed() throws Exception {
    String nodeId =
        seedFile(
            "aFile.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    int requests = 10;
    ExecutorService pool = Executors.newFixedThreadPool(requests);
    try {
      CountDownLatch start = new CountDownLatch(1);
      List<Future<Response>> calls = new ArrayList<>();
      for (int i = 0; i < requests; i++) {
        calls.add(
            pool.submit(
                () -> {
                  start.await();
                  return flagNodes(new String[] {nodeId}, REQUESTER_COOKIE);
                }));
      }
      start.countDown();

      for (Future<Response> call : calls) {
        Response response = call.get(60, TimeUnit.SECONDS);
        Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
        Assertions.assertThat(TestUtils.jsonResponseToErrors(response.getBody().asString()))
            .isEmpty();
      }
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void givenAFlagClearedWhileFlagNodesWaitsForTheNodeLockFlagNodesShouldSetTheFlag()
      throws Exception {
    String nodeId =
        seedFile(
            "aFile.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    seedFlag(nodeId, REQUESTER_COOKIE);

    Response response;
    try (Connection holder = jdbcConnection()) {
      holder.setAutoCommit(false);
      try (PreparedStatement lockNode =
              holder.prepareStatement("SELECT node_id FROM node WHERE node_id = ? FOR UPDATE");
          PreparedStatement clearFlag =
              holder.prepareStatement(
                  "UPDATE custom SET star = false WHERE node_id = ? AND user_id = ?")) {
        lockNode.setString(1, nodeId);
        try (ResultSet node = lockNode.executeQuery()) {
          Assertions.assertThat(node.next()).isTrue();
        }
        clearFlag.setString(1, nodeId);
        clearFlag.setString(2, REQUESTER_ID);
        Assertions.assertThat(clearFlag.executeUpdate()).isEqualTo(1);
      }
      CompletableFuture<Response> flag =
          CompletableFuture.supplyAsync(() -> flagNodes(new String[] {nodeId}, REQUESTER_COOKIE));
      awaitBlockedBy(holder);
      holder.commit();
      response = flag.get(60, TimeUnit.SECONDS);
    }

    Map<String, Object> page =
        TestUtils.jsonResponseToMap(response.getBody().asString(), "flagNodes");
    Assertions.assertThat((List<String>) page.get("data")).containsExactly(nodeId);
    try (Connection connection = jdbcConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT star FROM custom WHERE node_id = ? AND user_id = ?")) {
      statement.setString(1, nodeId);
      statement.setString(2, REQUESTER_ID);
      try (ResultSet row = statement.executeQuery()) {
        Assertions.assertThat(row.next()).isTrue();
        Assertions.assertThat(row.getBoolean("star")).isTrue();
      }
    }
  }

  @Test
  void givenANotExistingNodeFlagNodesShouldReturn200WithAnErrorMessage() {
    // Given
    String nonExistentId = "00000000-0000-0000-0000-000000000001";

    // When
    Response response = flagNodes(new String[] {nonExistentId}, REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errorCodes = TestUtils.jsonResponseToErrorCodes(response.getBody().asString());
    Assertions.assertThat(errorCodes).containsExactly("NODE_WRITE_ERROR");
  }

  @Test
  void givenANodeAndAUserWithoutPermissionsFlagNodesShouldReturn200WithAnErrorMessage() {
    // Given — node owned by a DIFFERENT user, never shared with the requester
    String nodeId =
        seedFile(
            "notMine.txt", LOCAL_ROOT, "notmine".getBytes(StandardCharsets.UTF_8), OTHER_COOKIE);

    // When
    Response response = flagNodes(new String[] {nodeId}, REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errorCodes = TestUtils.jsonResponseToErrorCodes(response.getBody().asString());
    Assertions.assertThat(errorCodes).containsExactly("NODE_WRITE_ERROR");
  }

  @Test
  void givenTwoNodesWithOneNotExistingNodeFlagNodesShouldReturn200WithAnErrorMessage() {
    // Given
    String nodeId =
        seedFile(
            "aFile.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    String nonExistentId = "00000000-0000-0000-0000-000000000004";

    // When
    Response response = flagNodes(new String[] {nodeId, nonExistentId}, REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errorCodes = TestUtils.jsonResponseToErrorCodes(response.getBody().asString());
    Assertions.assertThat(errorCodes).containsExactly("NODE_WRITE_ERROR");
  }
}
