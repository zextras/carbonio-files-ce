// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import com.zextras.carbonio.files.it.support.MockStoragesService;
import io.restassured.response.Response;
import java.sql.Connection;
import java.sql.PreparedStatement;
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

class VersionLocksApiIT extends AbstractFilesIT {

  private static final String USER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String COOKIE = "ZM_AUTH_TOKEN=fake-token";

  private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(4);

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

  private static Response delete(String... nodeIds) {
    return graphql(
        GraphqlCommandBuilder.aMutationBuilder("deleteNodes")
            .withListOfStrings("node_ids", nodeIds)
            .withWantedResultFormat("")
            .build(),
        COOKIE);
  }

  private static List<String> errorMessages(Response response) {
    return TestUtils.jsonResponseToErrors(response.getBody().asString());
  }

  private static Connection holdVersionTwoSlot(String nodeId) throws SQLException {
    Connection holder = jdbcConnection();
    try {
      holder.setAutoCommit(false);
      try (PreparedStatement revision =
          holder.prepareStatement(
              "INSERT INTO revision (node_id, version, mime_type, size, digest, editor_id,"
                  + " timestamp, is_autosave, keep_forever, cloned_from_version)"
                  + " VALUES (?, 2, 'text/plain', 3, '', ?, 1, false, false, NULL)")) {
        revision.setString(1, nodeId);
        revision.setString(2, USER_ID);
        revision.executeUpdate();
      }
    } catch (SQLException | RuntimeException e) {
      holder.close();
      throw e;
    }
    return holder;
  }

  @Test
  void deleteBehindAVersionUploadDeletesBothBlobs() throws Exception {
    MockStoragesService storages = FilesStackTestResource.getStoragesService();
    String file = seedFile("f.txt", LOCAL_ROOT, "one".getBytes(), COOKIE);

    Response uploadResponse;
    Response deleteResponse;
    try (Connection holder = holdVersionTwoSlot(file)) {
      CompletableFuture<Response> first =
          async(() -> uploadVersion(file, "two".getBytes(), "f.txt", false, COOKIE));
      awaitBlockedBy(holder, 1);
      CompletableFuture<Response> second = async(() -> delete(file));
      awaitBlockedBy(holder, 2);
      holder.rollback();
      uploadResponse = first.get(60, TimeUnit.SECONDS);
      deleteResponse = second.get(60, TimeUnit.SECONDS);
    }

    Assertions.assertThat(uploadResponse.statusCode()).isEqualTo(200);
    Assertions.assertThat(errorMessages(deleteResponse)).isEmpty();
    Assertions.assertThat(storages.has(file, 1)).isFalse();
    Assertions.assertThat(storages.has(file, 2)).isFalse();
    Assertions.assertThat(tombstoneRowsForNode(file)).isZero();
  }

  @Test
  void deleteGivesUpAfterTheBoundAndDeletesNothing() throws Exception {
    String file = seedFile("g.txt", LOCAL_ROOT, "one".getBytes(), COOKIE);

    Response deleteResponse;
    long elapsedMillis;
    Connection holder = holdNode(file, "FOR NO KEY UPDATE");
    try {
      long start = System.nanoTime();
      deleteResponse = async(() -> delete(file)).get(30, TimeUnit.SECONDS);
      elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    } finally {
      holder.rollback();
      holder.close();
    }

    Assertions.assertThat(TestUtils.jsonResponseToErrorCodes(deleteResponse.getBody().asString()))
        .containsExactly("NODE_WRITE_ERROR");
    Assertions.assertThat(elapsedMillis).isBetween(5_000L, 15_000L);
    Assertions.assertThat(download(file, COOKIE).statusCode()).isEqualTo(200);
  }
}
