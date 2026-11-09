// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

class ShareLocksApiIT extends AbstractFilesIT {

  private static final String OWNER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OWNER_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String USER2_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String USER2_COOKIE = "ZM_AUTH_TOKEN=fake-token-2";
  private static final String USER3_ID = "cccccccc-cccc-cccc-cccc-cccccccccccc";

  private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(12);

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", OWNER_ID);
    FilesStackTestResource.getUserManagementService().registerToken("fake-token-2", USER2_ID);
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
        OWNER_COOKIE);
  }

  private static Response createShare(
      String nodeId, String targetId, ACL.SharePermission permission, String cookie) {
    return graphql(
        GraphqlCommandBuilder.aMutationBuilder("createShare")
            .withString("node_id", nodeId)
            .withString("share_target_id", targetId)
            .withEnum("permission", permission)
            .withWantedResultFormat("{ created_at }")
            .build(),
        cookie);
  }

  private static Response deleteShares(String nodeId, String... targetIds) {
    return deleteSharesAs(OWNER_COOKIE, nodeId, targetIds);
  }

  private static Response deleteSharesAs(String cookie, String nodeId, String... targetIds) {
    return graphql(
        GraphqlCommandBuilder.aMutationBuilder("deleteShares")
            .withString("node_id", nodeId)
            .withListOfStrings("share_target_ids", targetIds)
            .withWantedResultFormat("")
            .build(),
        cookie);
  }

  private static Response move(String destinationId, String... nodeIds) {
    return graphql(
        GraphqlCommandBuilder.aMutationBuilder("moveNodes")
            .withListOfStrings("node_ids", nodeIds)
            .withString("destination_id", destinationId)
            .withWantedResultFormat("{ id }")
            .build(),
        OWNER_COOKIE);
  }

  private static List<String> errorMessages(Response response) {
    return TestUtils.jsonResponseToErrors(response.getBody().asString());
  }

  private static String createdFolderId(Response response) {
    Map<String, Object> folder =
        TestUtils.jsonResponseToMap(response.getBody().asString(), "createFolder");
    return (String) folder.get("id");
  }

  private static String createCollaborationLink(String nodeId) {
    Response response =
        graphql(
            GraphqlCommandBuilder.aMutationBuilder("createCollaborationLink")
                .withString("node_id", nodeId)
                .withEnumLiteral("permission", ACL.SharePermission.READ_ONLY.name())
                .withWantedResultFormat("{ url }")
                .build(),
            OWNER_COOKIE);
    String url =
        (String)
            TestUtils.jsonResponseToMap(response.getBody().asString(), "createCollaborationLink")
                .get("url");
    return url.substring(url.length() - 8);
  }

  private static Response clickInvite(String invitationId, String cookie) {
    return RestAssured.given()
        .redirects()
        .follow(false)
        .header("Cookie", cookie)
        .get("/invite/" + invitationId);
  }

  private static int shareRows(String nodeId, String targetId) throws SQLException {
    return Integer.parseInt(
        value(
            "SELECT count(*) AS n FROM share WHERE node_id = ? AND target_uuid = ?",
            "n",
            nodeId,
            targetId));
  }

  private static boolean directShare(String nodeId, String targetId) throws SQLException {
    return Boolean.parseBoolean(
        value(
            "SELECT direct FROM share WHERE node_id = ? AND target_uuid = ?",
            "direct",
            nodeId,
            targetId));
  }

  private static String value(String sql, String column, String... params) throws SQLException {
    try (Connection connection = jdbcConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) {
        statement.setString(i + 1, params[i]);
      }
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next() ? rows.getString(column) : null;
      }
    }
  }

  @Test
  void revokeThenCreateChildLeavesNoShare() throws Exception {
    String s = seedFolder("S", LOCAL_ROOT, OWNER_COOKIE);
    seedShare(s, USER2_ID, ACL.SharePermission.READ_AND_WRITE, OWNER_COOKIE);

    Response revoke;
    Response create;
    try (Connection holder = holdNode(s, "FOR NO KEY UPDATE")) {
      CompletableFuture<Response> first = async(() -> deleteShares(s, USER2_ID));
      awaitBlockedBy(holder, 1);
      CompletableFuture<Response> second = async(() -> createFolder(s, "c"));
      awaitBlockedBy(holder, 2);
      holder.commit();
      revoke = first.get(60, TimeUnit.SECONDS);
      create = second.get(60, TimeUnit.SECONDS);
    }

    Assertions.assertThat(errorMessages(revoke)).isEmpty();
    Assertions.assertThat(errorMessages(create)).isEmpty();
    Assertions.assertThat(shareRows(s, USER2_ID)).isZero();
    Assertions.assertThat(shareRows(createdFolderId(create), USER2_ID)).isZero();
  }

  @Test
  void shareThenCreateChildInheritsIt() throws Exception {
    String s = seedFolder("S", LOCAL_ROOT, OWNER_COOKIE);

    Response share;
    Response create;
    try (Connection holder = holdNode(s, "FOR NO KEY UPDATE")) {
      CompletableFuture<Response> first =
          async(() -> createShare(s, USER2_ID, ACL.SharePermission.READ_ONLY, OWNER_COOKIE));
      awaitBlockedBy(holder, 1);
      CompletableFuture<Response> second = async(() -> createFolder(s, "c"));
      awaitBlockedBy(holder, 2);
      holder.commit();
      share = first.get(60, TimeUnit.SECONDS);
      create = second.get(60, TimeUnit.SECONDS);
    }

    Assertions.assertThat(errorMessages(share)).isEmpty();
    Assertions.assertThat(errorMessages(create)).isEmpty();
    String child = createdFolderId(create);
    Assertions.assertThat(shareRows(child, USER2_ID)).isEqualTo(1);
    Assertions.assertThat(directShare(child, USER2_ID)).isFalse();
  }

  @Test
  void revokeThenMoveOutKeepsNoShare() throws Exception {
    String s = seedFolder("S", LOCAL_ROOT, OWNER_COOKIE);
    String c = seedFolder("c", s, OWNER_COOKIE);
    String h = seedFolder("H", LOCAL_ROOT, OWNER_COOKIE);
    seedShare(s, USER2_ID, ACL.SharePermission.READ_ONLY, OWNER_COOKIE);
    Assertions.assertThat(shareRows(c, USER2_ID)).isEqualTo(1);

    Response revoke;
    Response moved;
    try (Connection holder = holdNode(s, "FOR NO KEY UPDATE")) {
      CompletableFuture<Response> first = async(() -> deleteShares(s, USER2_ID));
      awaitBlockedBy(holder, 1);
      CompletableFuture<Response> second = async(() -> move(h, c));
      awaitBlockedBy(holder, 2);
      holder.commit();
      revoke = first.get(60, TimeUnit.SECONDS);
      moved = second.get(60, TimeUnit.SECONDS);
    }

    Assertions.assertThat(errorMessages(revoke)).isEmpty();
    Assertions.assertThat(errorMessages(moved)).isEmpty();
    Assertions.assertThat(value("SELECT folder_id FROM node WHERE node_id = ?", "folder_id", c))
        .isEqualTo(h);
    Assertions.assertThat(shareRows(c, USER2_ID)).isZero();
  }

  @Test
  void inviteBehindAHolderCompletes() throws Exception {
    String s = seedFolder("S", LOCAL_ROOT, OWNER_COOKIE);
    String c = seedFolder("c", s, OWNER_COOKIE);
    String invitationId = createCollaborationLink(s);

    Response response;
    try (Connection holder = holdNode(s, "FOR NO KEY UPDATE")) {
      CompletableFuture<Response> invite = async(() -> clickInvite(invitationId, USER2_COOKIE));
      awaitBlockedBy(holder, 1);
      holder.commit();
      response = invite.get(60, TimeUnit.SECONDS);
    }

    Assertions.assertThat(response.getStatusCode()).isEqualTo(307);
    Assertions.assertThat(shareRows(s, USER2_ID)).isEqualTo(1);
    Assertions.assertThat(shareRows(c, USER2_ID)).isEqualTo(1);
  }

  @Test
  void createShareGivesUpAfterTheBound() throws Exception {
    String s = seedFolder("S", LOCAL_ROOT, OWNER_COOKIE);

    Response response;
    long elapsedMillis;
    try (Connection holder = holdNode(s, "FOR NO KEY UPDATE")) {
      long start = System.nanoTime();
      response = createShare(s, USER2_ID, ACL.SharePermission.READ_ONLY, OWNER_COOKIE);
      elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      holder.rollback();
    }

    Assertions.assertThat(errorMessages(response)).first().isEqualTo("System error");
    Assertions.assertThat(elapsedMillis)
        .isBetween(NODE_LOCK_WAIT_MILLIS, NODE_LOCK_WAIT_MILLIS + 2_500L);
    Assertions.assertThat(shareRows(s, USER2_ID)).isZero();
  }

  @Test
  void aRequesterWithoutSharePermissionDoesNotLockTheNode() throws Exception {
    String s = seedFolder("S", LOCAL_ROOT, OWNER_COOKIE);
    seedShare(s, USER2_ID, ACL.SharePermission.READ_ONLY, OWNER_COOKIE);

    Response response;
    try (Connection holder = holdNode(s, "FOR SHARE")) {
      response =
          async(() -> createShare(s, USER3_ID, ACL.SharePermission.READ_ONLY, USER2_COOKIE))
              .get(30, TimeUnit.SECONDS);
      holder.rollback();
    }

    Assertions.assertThat(errorMessages(response)).isNotEmpty();
    Assertions.assertThat(errorMessages(response)).doesNotContain("System error");
    Assertions.assertThat(shareRows(s, USER3_ID)).isZero();
  }

  @Test
  void createChildThenRevokeReachesTheChild() throws Exception {
    String s = seedFolder("S", LOCAL_ROOT, OWNER_COOKIE);
    seedShare(s, USER2_ID, ACL.SharePermission.READ_AND_WRITE, OWNER_COOKIE);

    Response create;
    Response revoke;
    try (Connection holder = holdNode(s, "FOR NO KEY UPDATE")) {
      CompletableFuture<Response> first = async(() -> createFolder(s, "c"));
      awaitBlockedBy(holder, 1);
      CompletableFuture<Response> second = async(() -> deleteShares(s, USER2_ID));
      awaitBlockedBy(holder, 2);
      holder.commit();
      create = first.get(60, TimeUnit.SECONDS);
      revoke = second.get(60, TimeUnit.SECONDS);
    }

    Assertions.assertThat(errorMessages(create)).isEmpty();
    Assertions.assertThat(errorMessages(revoke)).isEmpty();
    Assertions.assertThat(shareRows(s, USER2_ID)).isZero();
    Assertions.assertThat(shareRows(createdFolderId(create), USER2_ID)).isZero();
  }

  @Test
  void createDeepChildThenShareReachesTheChild() throws Exception {
    String s = seedFolder("S", LOCAL_ROOT, OWNER_COOKIE);
    String a = seedFolder("a", s, OWNER_COOKIE);
    String b = seedFolder("b", a, OWNER_COOKIE);

    Response create;
    Response share;
    try (Connection holder = holdNode(b, "FOR NO KEY UPDATE")) {
      CompletableFuture<Response> first = async(() -> createFolder(b, "c"));
      awaitBlockedBy(holder, 1);
      CompletableFuture<Response> second =
          async(() -> createShare(s, USER2_ID, ACL.SharePermission.READ_ONLY, OWNER_COOKIE));
      awaitBlockedBy(holder, 2);
      holder.commit();
      create = first.get(60, TimeUnit.SECONDS);
      share = second.get(60, TimeUnit.SECONDS);
    }

    Assertions.assertThat(errorMessages(create)).isEmpty();
    Assertions.assertThat(errorMessages(share)).isEmpty();
    Assertions.assertThat(shareRows(createdFolderId(create), USER2_ID)).isEqualTo(1);
  }

  @Test
  void selfRemovalWithoutAShareRowDoesNotLockTheNode() throws Exception {
    String s = seedFolder("S", LOCAL_ROOT, OWNER_COOKIE);

    Response response;
    try (Connection holder = holdNode(s, "FOR SHARE")) {
      response = async(() -> deleteSharesAs(USER2_COOKIE, s, USER2_ID)).get(30, TimeUnit.SECONDS);
      holder.rollback();
    }

    Assertions.assertThat(errorMessages(response)).isNotEmpty();
    Assertions.assertThat(errorMessages(response)).doesNotContain("System error");
  }

  @Test
  void aShareeRemovingItsInheritedShareWaitsBehindAHolderAndSucceeds() throws Exception {
    String s = seedFolder("S", LOCAL_ROOT, OWNER_COOKIE);
    String c = seedFolder("c", s, OWNER_COOKIE);
    seedShare(s, USER2_ID, ACL.SharePermission.READ_ONLY, OWNER_COOKIE);
    Assertions.assertThat(shareRows(c, USER2_ID)).isEqualTo(1);

    Response response;
    try (Connection holder = holdNode(c, "FOR NO KEY UPDATE")) {
      CompletableFuture<Response> removal = async(() -> deleteSharesAs(USER2_COOKIE, c, USER2_ID));
      awaitBlockedBy(holder, 1);
      holder.commit();
      response = removal.get(60, TimeUnit.SECONDS);
    }

    Assertions.assertThat(errorMessages(response)).isEmpty();
    Assertions.assertThat(shareRows(c, USER2_ID)).isZero();
    Assertions.assertThat(shareRows(s, USER2_ID)).isEqualTo(1);
  }
}
