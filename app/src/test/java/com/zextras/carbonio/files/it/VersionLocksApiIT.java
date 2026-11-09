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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
    Assertions.assertThat(elapsedMillis)
        .isBetween(NODE_LOCK_WAIT_MILLIS, NODE_LOCK_WAIT_MILLIS + 2_500L);
    Assertions.assertThat(download(file, COOKIE).statusCode()).isEqualTo(200);
  }

  private static Response keepVersions(String nodeId, int... versions) {
    return graphql(
        GraphqlCommandBuilder.aMutationBuilder("keepVersions")
            .withString("node_id", nodeId)
            .withListOfIntegers("versions", versions)
            .withBoolean("keep_forever", true)
            .withWantedResultFormat("")
            .build(),
        COOKIE);
  }

  private static Response deleteVersions(String nodeId, int... versions) {
    GraphqlCommandBuilder builder =
        GraphqlCommandBuilder.aMutationBuilder("deleteVersions").withString("node_id", nodeId);
    if (versions != null) {
      builder = builder.withListOfIntegers("versions", versions);
    }
    return graphql(builder.withWantedResultFormat("").build(), COOKIE);
  }

  @SuppressWarnings("unchecked")
  private static List<Object> resultOf(Response response, String field) {
    return (List<Object>)
        TestUtils.jsonResponseToValue(response.getBody().asString(), field).orElse(List.of());
  }

  private static List<String> errorCodes(Response response) {
    return TestUtils.jsonResponseToErrorCodes(response.getBody().asString());
  }

  private static long sizeOf(String nodeId) throws SQLException {
    try (Connection connection = jdbcConnection();
        PreparedStatement statement =
            connection.prepareStatement("SELECT size FROM node WHERE node_id = ?")) {
      statement.setString(1, nodeId);
      try (ResultSet row = statement.executeQuery()) {
        row.next();
        return row.getLong(1);
      }
    }
  }

  private static Connection holdRevision(String nodeId, int version) throws SQLException {
    Connection holder = jdbcConnection();
    try {
      holder.setAutoCommit(false);
      try (PreparedStatement statement =
          holder.prepareStatement(
              "SELECT 1 FROM revision WHERE node_id = ? AND version = ? FOR SHARE")) {
        statement.setString(1, nodeId);
        statement.setInt(2, version);
        statement.executeQuery().close();
      }
    } catch (SQLException | RuntimeException e) {
      holder.close();
      throw e;
    }
    return holder;
  }

  @Test
  void keepThenDeleteKeepsTheVersion() throws Exception {
    String file = seedFile("k.txt", LOCAL_ROOT, "one".getBytes(), COOKIE);
    seedVersion(file, "two".getBytes(), "k.txt", COOKIE);

    Response keepResponse;
    Response deleteResponse;
    try (Connection holder = holdNode(file, "FOR NO KEY UPDATE")) {
      CompletableFuture<Response> first = async(() -> keepVersions(file, 1));
      awaitBlockedBy(holder, 1);
      CompletableFuture<Response> second = async(() -> deleteVersions(file, 1));
      awaitBlockedBy(holder, 2);
      holder.rollback();
      keepResponse = first.get(60, TimeUnit.SECONDS);
      deleteResponse = second.get(60, TimeUnit.SECONDS);
    }

    Assertions.assertThat(errorMessages(keepResponse)).isEmpty();
    Assertions.assertThat(resultOf(keepResponse, "keepVersions")).containsExactly((Object) 1);
    Assertions.assertThat(errorCodes(deleteResponse)).containsExactly("FILE_VERSION_NOT_FOUND");
    Assertions.assertThat(resultOf(deleteResponse, "deleteVersions")).isEmpty();
    Assertions.assertThat(download(file, 1, COOKIE).statusCode()).isEqualTo(200);
  }

  @Test
  void deleteVersionsDecidesOnTheStateAfterAnUpload() throws Exception {
    String folder = seedFolder("p", LOCAL_ROOT, COOKIE);
    String file = seedFile("d.txt", folder, "one".getBytes(), COOKIE);

    Response uploadResponse;
    Response deleteResponse;
    try (Connection holder = holdVersionTwoSlot(file)) {
      CompletableFuture<Response> first =
          async(() -> uploadVersion(file, "two".getBytes(), "d.txt", false, COOKIE));
      awaitBlockedBy(holder, 1);
      CompletableFuture<Response> second = async(() -> deleteVersions(file, (int[]) null));
      awaitBlockedBy(holder, 2);
      holder.rollback();
      uploadResponse = first.get(60, TimeUnit.SECONDS);
      deleteResponse = second.get(60, TimeUnit.SECONDS);
    }

    Assertions.assertThat(uploadResponse.statusCode()).isEqualTo(200);
    Assertions.assertThat(errorMessages(deleteResponse)).isEmpty();
    Assertions.assertThat(resultOf(deleteResponse, "deleteVersions")).containsExactly((Object) 1);
    Assertions.assertThat(download(file, 2, COOKIE).statusCode()).isEqualTo(200);
    Assertions.assertThat(download(file, 1, COOKIE).statusCode()).isEqualTo(404);
    Assertions.assertThat(download(file, COOKIE).asByteArray()).isEqualTo("two".getBytes());
  }

  @Test
  void concurrentKeepsRespectTheCap() throws Exception {
    setApplicationConfig("max-number-of-versions", "3");
    try {
      String file = seedFile("c.txt", LOCAL_ROOT, "one".getBytes(), COOKIE);
      seedVersion(file, "two".getBytes(), "c.txt", COOKIE);

      Response firstResponse;
      Response secondResponse;
      try (Connection holder = holdNode(file, "FOR NO KEY UPDATE")) {
        CompletableFuture<Response> first = async(() -> keepVersions(file, 1));
        awaitBlockedBy(holder, 1);
        CompletableFuture<Response> second = async(() -> keepVersions(file, 2));
        awaitBlockedBy(holder, 2);
        holder.rollback();
        firstResponse = first.get(60, TimeUnit.SECONDS);
        secondResponse = second.get(60, TimeUnit.SECONDS);
      }

      Assertions.assertThat(errorMessages(firstResponse)).isEmpty();
      Assertions.assertThat(resultOf(firstResponse, "keepVersions")).containsExactly((Object) 1);
      Assertions.assertThat(errorCodes(secondResponse)).containsExactly("VERSIONS_LIMIT_REACHED");
      Assertions.assertThat(resultOf(secondResponse, "keepVersions")).isEmpty();
    } finally {
      clearApplicationConfig("max-number-of-versions", "30");
    }
  }

  @Test
  void deleteOfAFolderBehindAVersionConfirmOfAChildDeletesBothBlobs() throws Exception {
    MockStoragesService storages = FilesStackTestResource.getStoragesService();
    String folder = seedFolder("p4", LOCAL_ROOT, COOKIE);
    String file = seedFile("f4.txt", folder, "one".getBytes(), COOKIE);

    Response uploadResponse;
    Response deleteResponse;
    try (Connection holder = holdVersionTwoSlot(file)) {
      CompletableFuture<Response> first =
          async(() -> uploadVersion(file, "two".getBytes(), "f4.txt", false, COOKIE));
      awaitBlockedBy(holder, 1);
      CompletableFuture<Response> second = async(() -> delete(folder));
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
  void overwriteIgnoresParentLocksAndIsNotBounded() throws Exception {
    String folder = seedFolder("p5", LOCAL_ROOT, COOKIE);
    String file = seedFile("o.txt", folder, "one".getBytes(), COOKIE);
    byte[] first = "first overwrite".getBytes();
    byte[] second = "the second overwrite, longer".getBytes();

    try (Connection parentHolder = holdNode(folder, "FOR NO KEY UPDATE")) {
      Response response =
          async(() -> uploadVersion(file, first, "o.txt", true, COOKIE)).get(30, TimeUnit.SECONDS);
      Assertions.assertThat(response.statusCode()).isEqualTo(200);
      Assertions.assertThat(download(file, COOKIE).asByteArray()).isEqualTo(first);
      Assertions.assertThat(sizeOf(file)).isEqualTo(first.length);

      Response blockedResponse;
      try (Connection revisionHolder = holdRevision(file, 1)) {
        CompletableFuture<Response> overwrite =
            async(() -> uploadVersion(file, second, "o.txt", true, COOKIE));
        awaitBlockedBy(revisionHolder, 1);
        Assertions.assertThatThrownBy(
                () -> overwrite.get(NODE_LOCK_WAIT_MILLIS + 1_000L, TimeUnit.MILLISECONDS))
            .isInstanceOf(TimeoutException.class);
        awaitBlockedBy(revisionHolder, 1);
        revisionHolder.rollback();
        blockedResponse = overwrite.get(60, TimeUnit.SECONDS);
      }
      Assertions.assertThat(blockedResponse.statusCode()).isEqualTo(200);
      Assertions.assertThat(download(file, COOKIE).asByteArray()).isEqualTo(second);
      Assertions.assertThat(sizeOf(file)).isEqualTo(second.length);
      parentHolder.rollback();
    }
  }

  @Test
  void uploadVersionGivesUpAfterTheBoundAndDiscardsTheBlob() throws Exception {
    MockStoragesService storages = FilesStackTestResource.getStoragesService();
    String folder = seedFolder("p6", LOCAL_ROOT, COOKIE);
    String file = seedFile("u.txt", folder, "one".getBytes(), COOKIE);

    Response uploadResponse;
    long elapsedMillis;
    Connection holder = holdNode(folder, "FOR NO KEY UPDATE");
    try {
      long start = System.nanoTime();
      uploadResponse =
          async(() -> uploadVersion(file, "two".getBytes(), "u.txt", false, COOKIE))
              .get(30, TimeUnit.SECONDS);
      elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    } finally {
      holder.rollback();
      holder.close();
    }

    Assertions.assertThat(uploadResponse.statusCode()).isEqualTo(500);
    Assertions.assertThat(elapsedMillis)
        .isBetween(NODE_LOCK_WAIT_MILLIS, NODE_LOCK_WAIT_MILLIS + 2_500L);
    Assertions.assertThat(download(file, 2, COOKIE).statusCode()).isEqualTo(404);
    Assertions.assertThat(download(file, 1, COOKIE).statusCode()).isEqualTo(200);
    Assertions.assertThat(storages.has(file, 2)).isFalse();
    Assertions.assertThat(tombstoneRowsForNode(file)).isZero();
    Assertions.assertThat(pendingReservations(file)).isEmpty();
  }

  @Test
  void deleteOfAFolderBehindACloneConfirmDeletesBothBlobs() throws Exception {
    MockStoragesService storages = FilesStackTestResource.getStoragesService();
    String folder = seedFolder("p7", LOCAL_ROOT, COOKIE);
    String file = seedFile("f7.txt", folder, "one".getBytes(), COOKIE);

    Response cloneResponse;
    Response deleteResponse;
    try (Connection holder = holdVersionTwoSlot(file)) {
      CompletableFuture<Response> first =
          async(
              () ->
                  graphql(
                      GraphqlCommandBuilder.aMutationBuilder("cloneVersion")
                          .withString("node_id", file)
                          .withInteger("version", 1)
                          .withWantedResultFormat("{ id version }")
                          .build(),
                      COOKIE));
      awaitBlockedBy(holder, 1);
      CompletableFuture<Response> second = async(() -> delete(folder));
      awaitBlockedBy(holder, 2);
      holder.rollback();
      cloneResponse = first.get(60, TimeUnit.SECONDS);
      deleteResponse = second.get(60, TimeUnit.SECONDS);
    }

    Assertions.assertThat(errorMessages(cloneResponse)).isEmpty();
    Assertions.assertThat(errorMessages(deleteResponse)).isEmpty();
    Assertions.assertThat(storages.has(file, 1)).isFalse();
    Assertions.assertThat(storages.has(file, 2)).isFalse();
    Assertions.assertThat(tombstoneRowsForNode(file)).isZero();
  }
}
