// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.response.Response;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

class TreeLocksApiIT extends AbstractFilesIT {

  private static final String USER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String OTHER_USER_ID = "dddddddd-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OTHER_COOKIE = "ZM_AUTH_TOKEN=other-token";
  private static final String TRASH_ROOT = "TRASH_ROOT";
  private static final String NODE_WRITE_ERROR = "NODE_WRITE_ERROR";

  private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(12);

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", USER_ID);
    FilesStackTestResource.getUserManagementService().registerToken("other-token", OTHER_USER_ID);
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

  private static Response nodesMutation(String mutation, String... nodeIds) {
    return graphql(
        GraphqlCommandBuilder.aMutationBuilder(mutation)
            .withListOfStrings("node_ids", nodeIds)
            .withWantedResultFormat(mutation.equals("trashNodes") ? "" : "{ id }")
            .build(),
        COOKIE);
  }

  private static Response trash(String... nodeIds) {
    return nodesMutation("trashNodes", nodeIds);
  }

  private static Response restore(String... nodeIds) {
    return nodesMutation("restoreNodes", nodeIds);
  }

  private static Response delete(String... nodeIds) {
    return graphql(
        GraphqlCommandBuilder.aMutationBuilder("deleteNodes")
            .withListOfStrings("node_ids", nodeIds)
            .withWantedResultFormat("")
            .build(),
        COOKIE);
  }

  private static Response move(String destinationId, String... nodeIds) {
    return graphql(
        GraphqlCommandBuilder.aMutationBuilder("moveNodes")
            .withListOfStrings("node_ids", nodeIds)
            .withString("destination_id", destinationId)
            .withWantedResultFormat("{ id }")
            .build(),
        COOKIE);
  }

  private static List<String> errorCodes(Response response) {
    return TestUtils.jsonResponseToErrorCodes(response.getBody().asString());
  }

  private static List<String> errorMessages(Response response) {
    return TestUtils.jsonResponseToErrors(response.getBody().asString());
  }

  private static String name(Response response) {
    Map<String, Object> folder =
        TestUtils.jsonResponseToMap(response.getBody().asString(), "createFolder");
    return (String) folder.get("name");
  }

  private static String column(String column, String sql, String param) throws SQLException {
    try (Connection connection = jdbcConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, param);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? rows.getString(column) : null;
      }
    }
  }

  private static String folderId(String nodeId) throws SQLException {
    return column("folder_id", "SELECT folder_id FROM node WHERE node_id = ?", nodeId);
  }

  private static String ancestors(String nodeId) throws SQLException {
    return column("ancestor_ids", "SELECT ancestor_ids FROM node WHERE node_id = ?", nodeId);
  }

  private static int count(String sql, String param) throws SQLException {
    return Integer.parseInt(column("count", sql, param));
  }

  @Test
  void createFolderBehindADeleteFindsTheFolderGone() throws Exception {
    String parent = seedFolder("P", LOCAL_ROOT, COOKIE);

    Response deleteResponse;
    Response createResponse;
    try (Connection holder = holdNode(parent, "FOR NO KEY UPDATE")) {
      CompletableFuture<Response> first = async(() -> delete(parent));
      awaitBlockedBy(holder, 1);
      CompletableFuture<Response> second = async(() -> createFolder(parent, "child"));
      awaitBlockedBy(holder, 2);
      holder.commit();
      deleteResponse = first.get(60, TimeUnit.SECONDS);
      createResponse = second.get(60, TimeUnit.SECONDS);
    }

    Assertions.assertThat(errorMessages(deleteResponse)).isEmpty();
    Assertions.assertThat(errorCodes(createResponse)).containsExactly(NODE_WRITE_ERROR);
    Assertions.assertThat(count("SELECT count(*) FROM node WHERE folder_id = ?", parent)).isZero();
  }

  @Test
  void trashDoesNotWriteANodeSharedWhileTheRequestWasWaiting() throws Exception {
    String mine = seedFolder("mine", LOCAL_ROOT, COOKIE);
    String theirs = seedFolder("theirs", LOCAL_ROOT, OTHER_COOKIE);

    Response response;
    try (Connection holderOfMine = holdNode(mine, "FOR NO KEY UPDATE")) {
      CompletableFuture<Response> request = async(() -> trash(mine, theirs));
      awaitBlockedBy(holderOfMine, 1);
      seedShareRawJdbc(theirs, USER_ID, ACL.SharePermission.READ_AND_WRITE);
      try (Connection holderOfTheirs = holdNode(theirs, "FOR SHARE")) {
        holderOfMine.rollback();
        response = request.get(30, TimeUnit.SECONDS);
        holderOfTheirs.rollback();
      }
    }

    Assertions.assertThat(errorCodes(response)).containsExactly(NODE_WRITE_ERROR);
    Assertions.assertThat(folderId(theirs).trim()).isEqualTo(LOCAL_ROOT);
    Assertions.assertThat(folderId(mine).trim()).isEqualTo(TRASH_ROOT);
  }

  @Test
  void aNodeInsideTheTrashCannotBeTrashedAgain() throws Exception {
    String folder = seedFolder("F", LOCAL_ROOT, COOKIE);
    String child = seedFolder("child", folder, COOKIE);
    Assertions.assertThat(errorMessages(trash(folder))).isEmpty();

    Response response = trash(child);

    Assertions.assertThat(errorCodes(response)).containsExactly(NODE_WRITE_ERROR);
    Assertions.assertThat(folderId(child)).isEqualTo(folder);
    Assertions.assertThat(ancestors(child)).startsWith(TRASH_ROOT + ",");
  }

  @Test
  void createFolderBehindATrashGetsTheTrashedAncestors() throws Exception {
    String parent = seedFolder("P", LOCAL_ROOT, COOKIE);

    Response createResponse;
    try (Connection holder = holdNode(parent, "FOR NO KEY UPDATE")) {
      CompletableFuture<Response> first = async(() -> trash(parent));
      awaitBlockedBy(holder, 1);
      CompletableFuture<Response> second = async(() -> createFolder(parent, "child"));
      awaitBlockedBy(holder, 2);
      holder.commit();
      Assertions.assertThat(errorMessages(first.get(60, TimeUnit.SECONDS))).isEmpty();
      createResponse = second.get(60, TimeUnit.SECONDS);
    }

    Assertions.assertThat(errorMessages(createResponse)).isEmpty();
    String childId =
        column("node_id", "SELECT node_id FROM node WHERE folder_id = ?", parent).trim();
    Assertions.assertThat(ancestors(childId)).startsWith(TRASH_ROOT + ",");
  }

  @Test
  void sameNameFoldersInOneFolderGetDistinctNames() throws Exception {
    String parent = seedFolder("P", LOCAL_ROOT, COOKIE);

    List<CompletableFuture<Response>> pending = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      pending.add(async(() -> createFolder(parent, "same")));
    }

    Set<String> names = new HashSet<>();
    for (CompletableFuture<Response> future : pending) {
      Response response = future.get(60, TimeUnit.SECONDS);
      Assertions.assertThat(errorMessages(response)).isEmpty();
      names.add(name(response));
    }
    Assertions.assertThat(names).hasSize(10);
  }

  @Test
  void doubleTrashAnswersLikeASequentialOne() throws Exception {
    String node = seedFolder("X", LOCAL_ROOT, COOKIE);

    Response firstResponse;
    Response secondResponse;
    try (Connection holder = holdNode(node, "FOR NO KEY UPDATE")) {
      CompletableFuture<Response> first = async(() -> trash(node));
      awaitBlockedBy(holder, 1);
      CompletableFuture<Response> second = async(() -> trash(node));
      awaitBlockedBy(holder, 2);
      holder.commit();
      firstResponse = first.get(60, TimeUnit.SECONDS);
      secondResponse = second.get(60, TimeUnit.SECONDS);
    }

    Assertions.assertThat(errorMessages(firstResponse)).isEmpty();
    Assertions.assertThat(errorCodes(secondResponse)).containsExactly(NODE_WRITE_ERROR);
    Assertions.assertThat(count("SELECT count(*) FROM trashed WHERE node_id = ?", node))
        .isEqualTo(1);
  }

  @Test
  void crossingMovesLeaveNoCycle() throws Exception {
    String x = seedFolder("X", LOCAL_ROOT, COOKIE);
    String y = seedFolder("Y", LOCAL_ROOT, COOKIE);

    Response firstResponse;
    Response secondResponse;
    try (Connection holder = holdNode(x, "FOR NO KEY UPDATE")) {
      CompletableFuture<Response> first = async(() -> move(y, x));
      awaitBlockedBy(holder, 1);
      CompletableFuture<Response> second = async(() -> move(x, y));
      awaitBlockedBy(holder, 2);
      holder.commit();
      firstResponse = first.get(60, TimeUnit.SECONDS);
      secondResponse = second.get(60, TimeUnit.SECONDS);
    }

    Assertions.assertThat(errorMessages(firstResponse)).isEmpty();
    Assertions.assertThat(errorCodes(secondResponse)).containsExactly(NODE_WRITE_ERROR);
    Assertions.assertThat(folderId(y).trim()).isEqualTo(LOCAL_ROOT);
    Assertions.assertThat(ancestors(x)).isEqualTo(LOCAL_ROOT + "," + y);
  }

  @Test
  void moveOfATrashedNodeIsRefused() throws Exception {
    String x = seedFolder("X", LOCAL_ROOT, COOKIE);
    String y = seedFolder("Y", LOCAL_ROOT, COOKIE);
    seedTrashed(x, COOKIE);

    Response response = move(y, x);

    Assertions.assertThat(errorCodes(response)).containsExactly(NODE_WRITE_ERROR);
    Assertions.assertThat(folderId(x).trim()).isEqualTo(TRASH_ROOT);
    Assertions.assertThat(count("SELECT count(*) FROM trashed WHERE node_id = ?", x)).isEqualTo(1);
  }

  @Test
  void moveIntoAFolderInTheTrashIsRefused() throws Exception {
    String trashed = seedFolder("T", LOCAL_ROOT, COOKIE);
    String z = seedFolder("Z", LOCAL_ROOT, COOKIE);
    seedTrashed(trashed, COOKIE);

    Response response = move(trashed, z);

    Assertions.assertThat(errorCodes(response)).containsExactly(NODE_WRITE_ERROR);
    Assertions.assertThat(folderId(z).trim()).isEqualTo(LOCAL_ROOT);
    Assertions.assertThat(ancestors(z)).isEqualTo(LOCAL_ROOT);
  }

  @Test
  void restoreBehindAMoveOfTheOriginalParentUsesItsNewPlace() throws Exception {
    String p = seedFolder("P", LOCAL_ROOT, COOKIE);
    String q = seedFolder("Q", LOCAL_ROOT, COOKIE);
    String n = seedFolder("N", p, COOKIE);
    seedTrashed(n, COOKIE);

    Response restoreResponse;
    try (Connection holder = holdNode(p, "FOR NO KEY UPDATE")) {
      CompletableFuture<Response> first = async(() -> move(q, p));
      awaitBlockedBy(holder, 1);
      CompletableFuture<Response> second = async(() -> restore(n));
      awaitBlockedBy(holder, 2);
      holder.commit();
      Assertions.assertThat(errorMessages(first.get(60, TimeUnit.SECONDS))).isEmpty();
      restoreResponse = second.get(60, TimeUnit.SECONDS);
    }

    Assertions.assertThat(errorMessages(restoreResponse)).isEmpty();
    Assertions.assertThat(folderId(n).trim()).isEqualTo(p);
    Assertions.assertThat(ancestors(n)).isEqualTo(LOCAL_ROOT + "," + q + "," + p);
  }

  private static void ageNode(String nodeId, long days) throws SQLException {
    try (Connection connection = jdbcConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "UPDATE node SET updated_timestamp = ? WHERE node_id = ?")) {
      statement.setLong(1, System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days));
      statement.setString(2, nodeId);
      statement.executeUpdate();
    }
  }

  @Test
  void restoreOfATrashedNodeWithinTheRetentionSucceeds() throws Exception {
    String n = seedFolder("N", LOCAL_ROOT, COOKIE);
    seedTrashed(n, COOKIE);
    ageNode(n, 29);

    Response response = restore(n);

    Assertions.assertThat(errorMessages(response)).isEmpty();
    Assertions.assertThat(folderId(n).trim()).isEqualTo(LOCAL_ROOT);
  }

  @Test
  void moveOfADescendantOfATrashedFolderIsRefused() throws Exception {
    String p = seedFolder("P", LOCAL_ROOT, COOKIE);
    String c = seedFolder("C", p, COOKIE);
    String y = seedFolder("Y", LOCAL_ROOT, COOKIE);
    seedTrashed(p, COOKIE);

    Response response = move(y, c);

    Assertions.assertThat(errorCodes(response)).containsExactly(NODE_WRITE_ERROR);
    Assertions.assertThat(folderId(c).trim()).isEqualTo(p);
  }

  @Test
  void moveIntoADescendantOfATrashedFolderIsRefused() throws Exception {
    String p = seedFolder("P", LOCAL_ROOT, COOKIE);
    String c = seedFolder("C", p, COOKIE);
    String z = seedFolder("Z", LOCAL_ROOT, COOKIE);
    seedTrashed(p, COOKIE);

    Response response = move(c, z);

    Assertions.assertThat(errorCodes(response)).containsExactly(NODE_WRITE_ERROR);
    Assertions.assertThat(folderId(z).trim()).isEqualTo(LOCAL_ROOT);
  }

  @Test
  void restoreOfAnExpiredTrashedNodeIsRefused() throws Exception {
    String n = seedFolder("N", LOCAL_ROOT, COOKIE);
    seedTrashed(n, COOKIE);
    ageNode(n, 31);

    Response response = restore(n);

    Assertions.assertThat(errorCodes(response)).containsExactly(NODE_WRITE_ERROR);
    Assertions.assertThat(folderId(n).trim()).isEqualTo(TRASH_ROOT);
  }

  @Test
  void twoConcurrentRestoresOfTheSameNodeRestoreItOnce() throws Exception {
    String p = seedFolder("P", LOCAL_ROOT, COOKIE);
    String n = seedFolder("N", p, COOKIE);
    String child = seedFolder("child", n, COOKIE);
    seedTrashed(n, COOKIE);

    Response firstResponse;
    Response secondResponse;
    try (Connection holder = holdNode(n, "FOR NO KEY UPDATE")) {
      CompletableFuture<Response> first = async(() -> restore(n));
      CompletableFuture<Response> second = async(() -> restore(n));
      awaitBlockedBy(holder, 2);
      holder.commit();
      firstResponse = first.get(60, TimeUnit.SECONDS);
      secondResponse = second.get(60, TimeUnit.SECONDS);
    }

    List<Response> responses = List.of(firstResponse, secondResponse);
    Assertions.assertThat(responses.stream().filter(r -> errorMessages(r).isEmpty()).count())
        .isEqualTo(1);
    Assertions.assertThat(
            responses.stream()
                .filter(r -> !errorMessages(r).isEmpty())
                .map(TreeLocksApiIT::errorCodes)
                .toList())
        .containsExactly(List.of(NODE_WRITE_ERROR));
    Assertions.assertThat(count("SELECT count(*) FROM trashed WHERE node_id = ?", n)).isZero();
    Assertions.assertThat(count("SELECT count(*) FROM node WHERE node_id = ?", n)).isEqualTo(1);
    Assertions.assertThat(folderId(n).trim()).isEqualTo(p);
    Assertions.assertThat(ancestors(n)).isEqualTo(LOCAL_ROOT + "," + p);
    Assertions.assertThat(folderId(child).trim()).isEqualTo(n);
    Assertions.assertThat(ancestors(child)).isEqualTo(LOCAL_ROOT + "," + p + "," + n);
  }

  @Test
  void createFolderIsNotBlockedByALongOperationOnASubFolder() throws Exception {
    String parent = seedFolder("P", LOCAL_ROOT, COOKIE);
    String sub = seedFolder("B", parent, COOKIE);

    Response response;
    try (Connection holder = holdNode(parent, "FOR SHARE")) {
      holdAlso(holder, sub, "FOR NO KEY UPDATE");
      response = async(() -> createFolder(parent, "child")).get(30, TimeUnit.SECONDS);
      holder.rollback();
    }

    Assertions.assertThat(errorMessages(response)).isEmpty();
    Assertions.assertThat(
            count("SELECT count(*) FROM node WHERE folder_id = ? AND name = 'child'", parent))
        .isEqualTo(1);
  }

  @Test
  void createFolderGivesUpAfterTheBound() throws Exception {
    String parent = seedFolder("P", LOCAL_ROOT, COOKIE);

    Response response;
    long elapsedMillis;
    try (Connection holder = holdNode(parent, "FOR NO KEY UPDATE")) {
      long start = System.nanoTime();
      response = createFolder(parent, "late");
      elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      holder.rollback();
    }

    Assertions.assertThat(errorMessages(response)).first().isEqualTo("System error");
    Assertions.assertThat(elapsedMillis)
        .isBetween(NODE_LOCK_WAIT_MILLIS, NODE_LOCK_WAIT_MILLIS + 2_500L);
    Assertions.assertThat(count("SELECT count(*) FROM node WHERE folder_id = ?", parent)).isZero();
  }
}
