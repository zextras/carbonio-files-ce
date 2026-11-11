// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
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
import java.util.function.Supplier;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class UploadCopyLocksApiIT extends AbstractFilesIT {

  private static final String USER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OTHER_USER_ID = "dddddddd-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String COOKIE = "ZM_AUTH_TOKEN=fake-token";

  private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(6);

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
    FilesStackTestResource.getStoragesService().releaseCopies();
    for (CompletableFuture<?> future : started) {
      try {
        future.get(60, TimeUnit.SECONDS);
      } catch (Exception ignored) {
      }
    }
  }

  private static Response nodesMutation(String mutation, String... nodeIds) {
    return graphql(
        GraphqlCommandBuilder.aMutationBuilder(mutation)
            .withListOfStrings("node_ids", nodeIds)
            .withWantedResultFormat("")
            .build(),
        COOKIE);
  }

  private static Response copy(String destinationId, String... nodeIds) {
    return graphql(
        GraphqlCommandBuilder.aMutationBuilder("copyNodes")
            .withListOfStrings("node_ids", nodeIds)
            .withString("destination_id", destinationId)
            .withWantedResultFormat("{ id }")
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

  private static int sharesUnder(String root) throws SQLException {
    return count(
        "SELECT count(*) FROM share s JOIN node c ON c.node_id = s.node_id WHERE c.node_id = ?"
            + " OR c.ancestor_ids LIKE '%' || ? || '%'",
        root, root);
  }

  private static List<String> errorMessages(Response response) {
    return TestUtils.jsonResponseToErrors(response.getBody().asString());
  }

  private static String scalar(String sql, String... params) throws SQLException {
    try (Connection connection = jdbcConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) {
        statement.setString(i + 1, params[i]);
      }
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? rows.getString(1) : null;
      }
    }
  }

  private static int count(String sql, String... params) throws SQLException {
    return Integer.parseInt(scalar(sql, params));
  }

  private static void assertNothingLeftBehind() throws SQLException {
    Assertions.assertThat(FilesStackTestResource.getStoragesService().blobCount()).isZero();
    Assertions.assertThat(count("SELECT count(*) FROM tombstone")).isZero();
    Assertions.assertThat(pendingReservationsWithoutNode()).isEmpty();
  }

  @Test
  void uploadBehindATrashIsRefusedAndDiscardsTheBlob() throws Exception {
    String parent = seedFolder("P", LOCAL_ROOT, COOKIE);

    Response trashResponse;
    Response uploadResponse;
    try (Connection holder = holdNode(parent, "FOR NO KEY UPDATE")) {
      CompletableFuture<Response> first = async(() -> nodesMutation("trashNodes", parent));
      awaitBlockedBy(holder, 1);
      CompletableFuture<Response> second =
          async(() -> upload(parent, null, "x".getBytes(), "up.txt", COOKIE));
      awaitBlockedBy(holder, 2);
      holder.rollback();
      trashResponse = first.get(60, TimeUnit.SECONDS);
      uploadResponse = second.get(60, TimeUnit.SECONDS);
    }

    Assertions.assertThat(errorMessages(trashResponse)).isEmpty();
    Assertions.assertThat(uploadResponse.statusCode()).isEqualTo(404);
    Assertions.assertThat(count("SELECT count(*) FROM node WHERE folder_id = ?", parent)).isZero();
    assertNothingLeftBehind();
  }

  @Test
  void uploadBehindADeleteIsRefusedAndDiscardsTheBlob() throws Exception {
    String parent = seedFolder("P", LOCAL_ROOT, COOKIE);

    Response deleteResponse;
    Response uploadResponse;
    try (Connection holder = holdNode(parent, "FOR NO KEY UPDATE")) {
      CompletableFuture<Response> first = async(() -> nodesMutation("deleteNodes", parent));
      awaitBlockedBy(holder, 1);
      CompletableFuture<Response> second =
          async(() -> upload(parent, null, "x".getBytes(), "up.txt", COOKIE));
      awaitBlockedBy(holder, 2);
      holder.rollback();
      deleteResponse = first.get(60, TimeUnit.SECONDS);
      uploadResponse = second.get(60, TimeUnit.SECONDS);
    }

    Assertions.assertThat(errorMessages(deleteResponse)).isEmpty();
    Assertions.assertThat(uploadResponse.statusCode()).isEqualTo(404);
    assertNothingLeftBehind();
  }

  @Test
  void uploadIntoAFolderIsNotBlockedByALongOperationOnASubFolder() throws Exception {
    String parent = seedFolder("P", LOCAL_ROOT, COOKIE);
    String sub = seedFolder("B", parent, COOKIE);

    Response uploadResponse;
    try (Connection holder = holdNode(parent, "FOR SHARE")) {
      holdAlso(holder, sub, "FOR NO KEY UPDATE");
      uploadResponse =
          async(() -> upload(parent, null, "x".getBytes(), "up.txt", COOKIE))
              .get(30, TimeUnit.SECONDS);
      holder.rollback();
    }

    Assertions.assertThat(uploadResponse.statusCode()).isEqualTo(200);
    Assertions.assertThat(
            count("SELECT count(*) FROM node WHERE folder_id = ? AND name = 'up.txt'", parent))
        .isEqualTo(1);
  }

  @Test
  void uploadGivesUpAfterTheBound() throws Exception {
    String parent = seedFolder("P", LOCAL_ROOT, COOKIE);

    Response uploadResponse;
    long elapsedMillis;
    Connection holder = holdNode(parent, "FOR NO KEY UPDATE");
    try {
      long start = System.nanoTime();
      uploadResponse =
          async(() -> upload(parent, null, "x".getBytes(), "up.txt", COOKIE))
              .get(30, TimeUnit.SECONDS);
      elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    } finally {
      holder.rollback();
      holder.close();
    }

    Assertions.assertThat(uploadResponse.statusCode()).isEqualTo(500);
    Assertions.assertThat(elapsedMillis)
        .isBetween(NODE_LOCK_WAIT_MILLIS, NODE_LOCK_WAIT_MILLIS + 2_500L);
    Assertions.assertThat(count("SELECT count(*) FROM node WHERE folder_id = ?", parent)).isZero();
    assertNothingLeftBehind();
  }

  @Test
  void folderCopyIntoADestinationTrashedDuringTheCopyIsRefusedBelowTheTrashedFolder()
      throws Exception {
    MockStoragesService storages = FilesStackTestResource.getStoragesService();
    String source = seedFolder("S", LOCAL_ROOT, COOKIE);
    seedFile("a.txt", source, "a".getBytes(), COOKIE);
    String destination = seedFolder("D", LOCAL_ROOT, COOKIE);

    storages.holdCopies();
    CompletableFuture<Response> copying = async(() -> copy(destination, source));
    storages.awaitHeldCopy();
    Response trashResponse = nodesMutation("trashNodes", destination);
    storages.releaseCopies();
    Response copyResponse = copying.get(60, TimeUnit.SECONDS);

    Assertions.assertThat(errorMessages(trashResponse)).isEmpty();
    Assertions.assertThat(errorMessages(copyResponse)).isNotEmpty();
    String copiedRoot =
        scalar("SELECT node_id FROM node WHERE folder_id = ? AND name = 'S'", destination);
    Assertions.assertThat(copiedRoot).isNotNull();
    Assertions.assertThat(count("SELECT count(*) FROM node WHERE folder_id = ?", copiedRoot))
        .isZero();
    Assertions.assertThat(FilesStackTestResource.getStoragesService().blobCount()).isEqualTo(1);
  }

  @Test
  void aCopyIntoADestinationMovedUnderTheSourceDuringTheLockWaitIsRefused() throws Exception {
    String source = seedFolder("A", LOCAL_ROOT, COOKIE);
    String destination = seedFolder("B", LOCAL_ROOT, COOKIE);
    String inside = seedFolder("C", destination, COOKIE);

    Response copyResponse;
    try (Connection holder = holdNode(destination, "FOR NO KEY UPDATE")) {
      CompletableFuture<Response> copying = async(() -> copy(destination, source));
      awaitBlockedBy(holder, 1);
      try (PreparedStatement moveDestination =
              holder.prepareStatement(
                  "UPDATE node SET folder_id = ?,"
                      + " ancestor_ids = (SELECT trim(ancestor_ids) || ',' || trim(node_id)"
                      + " FROM node WHERE node_id = ?) WHERE node_id = ?");
          PreparedStatement moveInside =
              holder.prepareStatement(
                  "UPDATE node SET ancestor_ids = (SELECT trim(ancestor_ids) || ',' ||"
                      + " trim(node_id) FROM node WHERE node_id = ?) WHERE node_id = ?")) {
        moveDestination.setString(1, source);
        moveDestination.setString(2, source);
        moveDestination.setString(3, destination);
        moveDestination.executeUpdate();
        moveInside.setString(1, destination);
        moveInside.setString(2, inside);
        moveInside.executeUpdate();
      }
      holder.commit();
      copyResponse = copying.get(60, TimeUnit.SECONDS);
    }

    Assertions.assertThat(errorMessages(copyResponse)).isNotEmpty();
    Assertions.assertThat(
            scalar("SELECT folder_id FROM node WHERE node_id = ?", destination).trim())
        .isEqualTo(source.trim());
    Assertions.assertThat(count("SELECT count(*) FROM node WHERE name = 'A'")).isEqualTo(1);
    Assertions.assertThat(count("SELECT count(*) FROM node WHERE folder_id = ?", destination))
        .isEqualTo(1);
    Assertions.assertThat(count("SELECT count(*) FROM node WHERE folder_id = ?", inside)).isZero();
  }

  @Test
  void aRevokedShareOfTheDestinationIsNotAddedToTheCopy() throws Exception {
    String destination = seedFolder("D", LOCAL_ROOT, COOKIE);
    seedShare(destination, OTHER_USER_ID, ACL.SharePermission.READ_ONLY, COOKIE);
    String file = seedFile("x.txt", LOCAL_ROOT, "x".getBytes(), COOKIE);

    Response copyResponse;
    try (Connection holder = holdNode(destination, "FOR NO KEY UPDATE")) {
      CompletableFuture<Response> copying = async(() -> copy(destination, file));
      awaitBlockedBy(holder, 1);
      try (PreparedStatement revoke =
          holder.prepareStatement("DELETE FROM share WHERE node_id = ? AND target_uuid = ?")) {
        revoke.setString(1, destination);
        revoke.setString(2, OTHER_USER_ID);
        revoke.executeUpdate();
      }
      holder.commit();
      copyResponse = copying.get(60, TimeUnit.SECONDS);
    }

    Assertions.assertThat(errorMessages(copyResponse)).isEmpty();
    String copied =
        scalar("SELECT node_id FROM node WHERE folder_id = ? AND name = 'x.txt'", destination);
    Assertions.assertThat(copied).isNotNull();
    Assertions.assertThat(count("SELECT count(*) FROM share WHERE node_id = ?", copied)).isZero();
  }

  @Test
  void aCopyMovedAwayDuringTheCopyDoesNotGetTheDestinationShares() throws Exception {
    MockStoragesService storages = FilesStackTestResource.getStoragesService();
    String destination = seedFolder("D", LOCAL_ROOT, COOKIE);
    seedShare(destination, OTHER_USER_ID, ACL.SharePermission.READ_ONLY, COOKIE);
    String elsewhere = seedFolder("X", LOCAL_ROOT, COOKIE);
    String source = seedFolder("S", LOCAL_ROOT, COOKIE);
    seedFile("a.txt", source, "a".getBytes(), COOKIE);

    storages.holdCopies();
    CompletableFuture<Response> copying = async(() -> copy(destination, source));
    storages.awaitHeldCopy();
    String copiedRoot =
        scalar("SELECT node_id FROM node WHERE folder_id = ? AND name = 'S'", destination);
    Response moveResponse = move(elsewhere, copiedRoot);
    storages.releaseCopies();
    Response copyResponse = copying.get(60, TimeUnit.SECONDS);

    Assertions.assertThat(errorMessages(moveResponse)).isEmpty();
    Assertions.assertThat(errorMessages(copyResponse)).isEmpty();
    Assertions.assertThat(scalar("SELECT folder_id FROM node WHERE node_id = ?", copiedRoot).trim())
        .isEqualTo(elsewhere.trim());
    Assertions.assertThat(sharesUnder(copiedRoot)).isZero();
  }

  @Test
  void copyGivesUpOnTheNodeBehindTheBoundAndKeepsTheSavedOnes() throws Exception {
    MockStoragesService storages = FilesStackTestResource.getStoragesService();
    String destination = seedFolder("D", LOCAL_ROOT, COOKIE);
    seedShare(destination, OTHER_USER_ID, ACL.SharePermission.READ_ONLY, COOKIE);
    seedFolder("S", destination, COOKIE);
    String source = seedFolder("S", LOCAL_ROOT, COOKIE);
    seedFile("a.txt", source, "a".getBytes(), COOKIE);
    String file = seedFile("y.txt", LOCAL_ROOT, "y".getBytes(), COOKIE);

    storages.holdCopies();
    CompletableFuture<Response> copying = async(() -> copy(destination, source, file));
    storages.awaitHeldCopy();
    Response copyResponse;
    Connection holder = jdbcConnection();
    holder.setAutoCommit(false);
    try (PreparedStatement advisory =
        holder.prepareStatement(
            "SELECT pg_advisory_xact_lock(hashtextextended('files-children:' || ?, 0))")) {
      advisory.setString(1, destination.trim());
      advisory.execute();
    }
    try {
      storages.releaseCopies();
      copyResponse = copying.get(30, TimeUnit.SECONDS);
    } finally {
      holder.rollback();
      holder.close();
    }

    Assertions.assertThat(TestUtils.jsonResponseToErrorCodes(copyResponse.getBody().asString()))
        .containsExactly("NODE_COPY_ERROR");
    Assertions.assertThat(copyResponse.jsonPath().getString("errors[0].extensions.nodeId"))
        .isEqualTo(file);
    Assertions.assertThat(
            count("SELECT count(*) FROM node WHERE folder_id = ? AND name = 'y.txt'", destination))
        .isZero();
    String copiedRoot =
        scalar("SELECT node_id FROM node WHERE folder_id = ? AND name = 'S (1)'", destination);
    Assertions.assertThat(copyResponse.jsonPath().getList("data.copyNodes.id"))
        .containsExactly(copiedRoot);
    Assertions.assertThat(
            count("SELECT count(*) FROM node WHERE folder_id = ? AND name = 'a.txt'", copiedRoot))
        .isEqualTo(1);
    Assertions.assertThat(
            count(
                "SELECT count(*) FROM share WHERE node_id = ? AND target_uuid = ?",
                copiedRoot,
                OTHER_USER_ID))
        .isEqualTo(1);
  }
}
