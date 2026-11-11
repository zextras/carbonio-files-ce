// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.response.Response;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RenameLocksApiIT extends AbstractFilesIT {

  private static final String USER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String NODE_DUPLICATED = "NODE_DUPLICATED";

  private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(8);

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", USER_ID);
  }

  @AfterAll
  static void shutdown() {
    EXECUTOR.shutdownNow();
  }

  private final List<CompletableFuture<?>> started = new ArrayList<>();

  private <T> CompletableFuture<T> async(Supplier<T> call) {
    CompletableFuture<T> future = CompletableFuture.supplyAsync(call, EXECUTOR);
    started.add(future);
    return future;
  }

  @AfterEach
  void settleStartedCalls() {
    for (CompletableFuture<?> future : started) {
      try {
        future.get(60, TimeUnit.SECONDS);
      } catch (Exception ignored) {
      }
    }
  }

  private static Response createFolder(String parentId, String name) {
    return graphql(
        GraphqlCommandBuilder.aMutationBuilder("createFolder")
            .withString("destination_id", parentId)
            .withString("name", name)
            .withWantedResultFormat("{ id name }")
            .build(),
        COOKIE);
  }

  private static Response rename(String nodeId, String name) {
    return graphql(
        GraphqlCommandBuilder.aMutationBuilder("updateNode")
            .withString("node_id", nodeId)
            .withString("name", name)
            .withBoolean("flagged", false)
            .withWantedResultFormat("{ id name }")
            .build(),
        COOKIE);
  }

  private static void flag(String nodeId) {
    Response response =
        graphql(
            GraphqlCommandBuilder.aMutationBuilder("flagNodes")
                .withListOfStrings("node_ids", new String[] {nodeId})
                .withBoolean("flag", true)
                .withWantedResultFormat("")
                .build(),
            COOKIE);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(response.getBody().asString())).isEmpty();
  }

  private static List<String> errorCodes(Response response) {
    return TestUtils.jsonResponseToErrorCodes(response.getBody().asString());
  }

  private static List<String> childNames(String parentId) throws SQLException {
    try (Connection connection = jdbcConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT name FROM node WHERE folder_id = ? ORDER BY name")) {
      statement.setString(1, parentId);
      List<String> names = new ArrayList<>();
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          names.add(rows.getString(1));
        }
      }
      return names;
    }
  }

  private static Connection holdFlagRow(String nodeId) throws SQLException {
    Connection holder = jdbcConnection();
    try {
      holder.setAutoCommit(false);
      try (PreparedStatement statement =
          holder.prepareStatement("SELECT 1 FROM custom WHERE node_id = ? FOR UPDATE")) {
        statement.setString(1, nodeId);
        try (ResultSet rows = statement.executeQuery()) {
          Assertions.assertThat(rows.next()).isTrue();
        }
      }
    } catch (SQLException | RuntimeException e) {
      holder.close();
      throw e;
    }
    return holder;
  }

  private void awaitSecondWaiterOrCompletion(Connection holder, CompletableFuture<?> second)
      throws Exception {
    CompletableFuture<Void> blocked =
        CompletableFuture.runAsync(
            () -> {
              try {
                awaitBlockedBy(holder, 2);
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            },
            EXECUTOR);
    CompletableFuture.anyOf(second, blocked).get(40, TimeUnit.SECONDS);
  }

  @Test
  void createFolderBehindARenameToTheSameNameSeesTheRename() throws Exception {
    String parent = seedFolder("P", LOCAL_ROOT, COOKIE);
    String node = seedFolder("a", parent, COOKIE);
    flag(node);

    Response renameResponse;
    Response createResponse;
    try (Connection holder = holdFlagRow(node)) {
      CompletableFuture<Response> renaming = async(() -> rename(node, "x"));
      awaitBlockedBy(holder, 1);
      CompletableFuture<Response> creating = async(() -> createFolder(parent, "x"));
      awaitSecondWaiterOrCompletion(holder, creating);
      holder.commit();
      renameResponse = renaming.get(60, TimeUnit.SECONDS);
      createResponse = creating.get(60, TimeUnit.SECONDS);
    }

    Assertions.assertThat(errorCodes(renameResponse)).isEmpty();
    Assertions.assertThat(errorCodes(createResponse)).isEmpty();
    Assertions.assertThat(childNames(parent)).containsExactly("x", "x (1)");
  }

  @Test
  void twoRenamesToTheSameNameLeaveOneOfThem() throws Exception {
    String parent = seedFolder("P", LOCAL_ROOT, COOKIE);
    String first = seedFolder("a", parent, COOKIE);
    String second = seedFolder("b", parent, COOKIE);
    flag(first);

    Response firstResponse;
    Response secondResponse;
    try (Connection holder = holdFlagRow(first)) {
      CompletableFuture<Response> renamingFirst = async(() -> rename(first, "x"));
      awaitBlockedBy(holder, 1);
      CompletableFuture<Response> renamingSecond = async(() -> rename(second, "x"));
      awaitSecondWaiterOrCompletion(holder, renamingSecond);
      holder.commit();
      firstResponse = renamingFirst.get(60, TimeUnit.SECONDS);
      secondResponse = renamingSecond.get(60, TimeUnit.SECONDS);
    }

    Assertions.assertThat(errorCodes(firstResponse)).isEmpty();
    Assertions.assertThat(errorCodes(secondResponse)).containsExactly(NODE_DUPLICATED);
    Assertions.assertThat(childNames(parent)).containsExactly("b", "x");
  }
}
