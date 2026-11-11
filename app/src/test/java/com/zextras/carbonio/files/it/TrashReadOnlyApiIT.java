// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import com.zextras.carbonio.files.it.support.MockStoragesService;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A node in the trash (the trashed top or anything inside a trashed folder) is read-only: nothing
 * is added or modified, only removed.
 */
class TrashReadOnlyApiIT extends AbstractFilesIT {

  private static final String OWNER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OWNER_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String TARGET_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String TARGET_COOKIE = "ZM_AUTH_TOKEN=fake-token-b";
  private static final String OTHER_ID = "cccccccc-cccc-cccc-cccc-cccccccccccc";

  private static final String TRASH_ROOT = "TRASH_ROOT";
  private static final String NODE_WRITE_ERROR = "NODE_WRITE_ERROR";
  private static final List<String> TABLES_BY_NODE =
      List.of(
          "node",
          "custom",
          "share",
          "link",
          "collaboration_link",
          "revision",
          "activity",
          "tombstone",
          "version_reservation",
          "overwrite_lease");

  private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(4);

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", OWNER_ID);
    FilesStackTestResource.getUserManagementService().registerToken("fake-token-b", TARGET_ID);
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

  private record Scene(
      String file, String trashedId, String linkId, String collaborationId, String invitationId) {}

  private static Scene scene(boolean nested) {
    String folder = nested ? seedFolder("trashed-parent", LOCAL_ROOT, OWNER_COOKIE) : null;
    String file = seedFile("doc.txt", nested ? folder : LOCAL_ROOT, "one".getBytes(), OWNER_COOKIE);
    seedVersion(file, "two".getBytes(), "doc.txt", OWNER_COOKIE);
    seedFlag(file, OWNER_COOKIE);
    seedShare(file, TARGET_ID, SharePermission.READ_ONLY, OWNER_COOKIE);
    String linkId = seedLink(file, OWNER_COOKIE);
    Map<String, Object> collaboration =
        TestUtils.jsonResponseToMap(
            graphql(
                    GraphqlCommandBuilder.aMutationBuilder("createCollaborationLink")
                        .withString("node_id", file)
                        .withEnumLiteral("permission", SharePermission.READ_AND_SHARE.name())
                        .withWantedResultFormat("{ id url }")
                        .build(),
                    OWNER_COOKIE)
                .getBody()
                .asString(),
            "createCollaborationLink");
    String url = (String) collaboration.get("url");
    String trashedId = nested ? folder : file;
    seedTrashed(trashedId, OWNER_COOKIE);
    return new Scene(
        file, trashedId, linkId, (String) collaboration.get("id"), url.substring(url.length() - 8));
  }

  private static String snapshot(String nodeId) throws SQLException {
    StringBuilder state = new StringBuilder();
    try (Connection connection = jdbcConnection()) {
      for (String table : TABLES_BY_NODE) {
        try (PreparedStatement statement =
            connection.prepareStatement(
                "SELECT coalesce(string_agg(t::text, '|' ORDER BY t::text), '') FROM "
                    + table
                    + " t WHERE node_id = ?")) {
          statement.setString(1, nodeId);
          try (ResultSet rows = statement.executeQuery()) {
            rows.next();
            state.append(table).append('=').append(rows.getString(1)).append('\n');
          }
        }
      }
    }
    return state.toString();
  }

  private static String updatedAt(String nodeId) throws SQLException {
    return column("SELECT updated_timestamp FROM node WHERE node_id = ?", nodeId);
  }

  private static String column(String sql, String... parameters) throws SQLException {
    try (Connection connection = jdbcConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < parameters.length; i++) {
        statement.setString(i + 1, parameters[i]);
      }
      try (ResultSet rows = statement.executeQuery()) {
        rows.next();
        return rows.getString(1);
      }
    }
  }

  private static List<String> errorCodes(Response response) {
    return TestUtils.jsonResponseToErrorCodes(response.getBody().asString());
  }

  private static List<String> errors(Response response) {
    return TestUtils.jsonResponseToErrors(response.getBody().asString());
  }

  private static Response mutation(GraphqlCommandBuilder builder, String cookie) {
    return graphql(builder.withWantedResultFormat("").build(), cookie);
  }

  private static Response mutation(GraphqlCommandBuilder builder, String format, String cookie) {
    return graphql(builder.withWantedResultFormat(format).build(), cookie);
  }

  private static Response updateNode(String nodeId, String field, Object value) {
    GraphqlCommandBuilder builder =
        GraphqlCommandBuilder.aMutationBuilder("updateNode").withString("node_id", nodeId);
    if (value instanceof Boolean flagged) {
      builder.withBoolean(field, flagged);
    } else {
      builder.withString(field, (String) value);
    }
    return graphql(builder.withWantedResultFormat("{ id }").build(), OWNER_COOKIE);
  }

  private static Response flagNodes(String nodeId, boolean flag) {
    return mutation(
        GraphqlCommandBuilder.aMutationBuilder("flagNodes")
            .withListOfStrings("node_ids", new String[] {nodeId})
            .withBoolean("flag", flag),
        OWNER_COOKIE);
  }

  private static Response cloneVersion(String nodeId) {
    return graphql(
        GraphqlCommandBuilder.aMutationBuilder("cloneVersion")
            .withString("node_id", nodeId)
            .withInteger("version", 1)
            .withWantedResultFormat("{ id }")
            .build(),
        OWNER_COOKIE);
  }

  private static Response keepVersions(String nodeId) {
    return mutation(
        GraphqlCommandBuilder.aMutationBuilder("keepVersions")
            .withString("node_id", nodeId)
            .withListOfIntegers("versions", new int[] {1})
            .withBoolean("keep_forever", true),
        OWNER_COOKIE);
  }

  private static Response createShare(String nodeId) {
    return mutation(
        GraphqlCommandBuilder.aMutationBuilder("createShare")
            .withString("node_id", nodeId)
            .withString("share_target_id", OTHER_ID)
            .withEnum("permission", SharePermission.READ_ONLY),
        "{ created_at }",
        OWNER_COOKIE);
  }

  private static Response updateShares(String nodeId) {
    return mutation(
        GraphqlCommandBuilder.aMutationBuilder("updateShares")
            .withString("node_id", nodeId)
            .withListOfStrings("share_target_ids", new String[] {TARGET_ID})
            .withEnum("permission", SharePermission.READ_AND_WRITE),
        "{ permission }",
        OWNER_COOKIE);
  }

  private static Response createLink(String nodeId) {
    return mutation(
        GraphqlCommandBuilder.aMutationBuilder("createLink").withString("node_id", nodeId),
        "{ id }",
        OWNER_COOKIE);
  }

  private static Response updateLink(String linkId) {
    return mutation(
        GraphqlCommandBuilder.aMutationBuilder("updateLink")
            .withString("link_id", linkId)
            .withString("description", "changed"),
        "{ id }",
        OWNER_COOKIE);
  }

  private static Response createCollaborationLink(String nodeId) {
    return mutation(
        GraphqlCommandBuilder.aMutationBuilder("createCollaborationLink")
            .withString("node_id", nodeId)
            .withEnumLiteral("permission", SharePermission.READ_ONLY.name()),
        "{ id }",
        OWNER_COOKIE);
  }

  private static Response internalCreateLink(String nodeId) {
    return RestAssured.given()
        .contentType(ContentType.JSON)
        .body(Map.of("userId", OWNER_ID, "nodeId", nodeId))
        .post("/internal/links");
  }

  private static Response internalUploadVersion(String nodeId, boolean overwrite) {
    return RestAssured.given()
        .header("NodeId", nodeId)
        .header(
            "Filename",
            Base64.getEncoder().encodeToString("doc.txt".getBytes(StandardCharsets.UTF_8)))
        .header("OverwriteVersion", String.valueOf(overwrite))
        .body("three".getBytes())
        .post("/internal/accounts/" + OWNER_ID + "/upload-version");
  }

  private static Response invite(String invitationId) {
    return invite(invitationId, TARGET_COOKIE);
  }

  private static Response invite(String invitationId, String cookie) {
    return RestAssured.given()
        .redirects()
        .follow(false)
        .header("Cookie", cookie)
        .get("/invite/" + invitationId);
  }

  private static String unsharedTrashedFileWithInvitation(boolean nested) {
    String folder = nested ? seedFolder("trashed-parent", LOCAL_ROOT, OWNER_COOKIE) : null;
    String file = seedFile("doc.txt", nested ? folder : LOCAL_ROOT, "one".getBytes(), OWNER_COOKIE);
    Map<String, Object> collaboration =
        TestUtils.jsonResponseToMap(
            graphql(
                    GraphqlCommandBuilder.aMutationBuilder("createCollaborationLink")
                        .withString("node_id", file)
                        .withEnumLiteral("permission", SharePermission.READ_AND_SHARE.name())
                        .withWantedResultFormat("{ url }")
                        .build(),
                    OWNER_COOKIE)
                .getBody()
                .asString(),
            "createCollaborationLink");
    String url = (String) collaboration.get("url");
    seedTrashed(nested ? folder : file, OWNER_COOKIE);
    return file + ":" + url.substring(url.length() - 8);
  }

  private static void assertGraphqlRefused(
      Response response, String expectedCode, String nodeId, String before) throws SQLException {
    Assertions.assertThat(errorCodes(response)).containsExactly(expectedCode);
    Assertions.assertThat(snapshot(nodeId)).isEqualTo(before);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void renameIsRefused(boolean nested) throws SQLException {
    Scene scene = scene(nested);
    String before = snapshot(scene.file());

    assertGraphqlRefused(
        updateNode(scene.file(), "name", "renamed"), NODE_WRITE_ERROR, scene.file(), before);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void renameWithADescriptionIsRefused(boolean nested) throws SQLException {
    Scene scene = scene(nested);
    String before = snapshot(scene.file());

    Response response =
        graphql(
            GraphqlCommandBuilder.aMutationBuilder("updateNode")
                .withString("node_id", scene.file())
                .withString("name", "renamed")
                .withString("description", "changed")
                .withWantedResultFormat("{ id }")
                .build(),
            OWNER_COOKIE);

    assertGraphqlRefused(response, NODE_WRITE_ERROR, scene.file(), before);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void descriptionStillWorksAndKeepsTheTrashDate(boolean nested) throws SQLException {
    Scene scene = scene(nested);
    String date = updatedAt(scene.file());

    Response response = updateNode(scene.file(), "description", "changed");

    Assertions.assertThat(errors(response)).isEmpty();
    Assertions.assertThat(column("SELECT description FROM node WHERE node_id = ?", scene.file()))
        .isEqualTo("changed");
    Assertions.assertThat(updatedAt(scene.file())).isEqualTo(date);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void flaggingThroughUpdateNodeIsRefused(boolean nested) throws SQLException {
    Scene scene = scene(nested);
    String before = snapshot(scene.file());

    assertGraphqlRefused(
        updateNode(scene.file(), "flagged", false), NODE_WRITE_ERROR, scene.file(), before);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void flagNodesIsRefusedInBothDirections(boolean nested) throws SQLException {
    Scene scene = scene(nested);
    String before = snapshot(scene.file());

    assertGraphqlRefused(flagNodes(scene.file(), false), NODE_WRITE_ERROR, scene.file(), before);
    assertGraphqlRefused(flagNodes(scene.file(), true), NODE_WRITE_ERROR, scene.file(), before);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void flagNodesRefusesOnlyTheTrashedNodeAndKeepsTheOthers(boolean nested) throws SQLException {
    Scene scene = scene(nested);
    String live = seedFile("live.txt", LOCAL_ROOT, "x".getBytes(), OWNER_COOKIE);
    String before = snapshot(scene.file());

    Response response =
        mutation(
            GraphqlCommandBuilder.aMutationBuilder("flagNodes")
                .withListOfStrings("node_ids", new String[] {scene.file(), live})
                .withBoolean("flag", true),
            OWNER_COOKIE);

    Assertions.assertThat(errorCodes(response)).containsExactly(NODE_WRITE_ERROR);
    Assertions.assertThat(snapshot(scene.file())).isEqualTo(before);
    Assertions.assertThat(column("SELECT count(*) FROM custom WHERE node_id = ?", live).trim())
        .isEqualTo("1");
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void cloneVersionStillWorksAndKeepsTheTrashDate(boolean nested) throws SQLException {
    Scene scene = scene(nested);
    String date = updatedAt(scene.file());

    Response response = cloneVersion(scene.file());

    Assertions.assertThat(errors(response)).isEmpty();
    Assertions.assertThat(versionRows(scene.file())).containsExactly(1, 2, 3);
    Assertions.assertThat(
            column("SELECT current_version FROM node WHERE node_id = ?", scene.file()))
        .isEqualTo("3");
    Assertions.assertThat(updatedAt(scene.file())).isEqualTo(date);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void keepVersionsStillWorksAndKeepsTheTrashDate(boolean nested) throws SQLException {
    Scene scene = scene(nested);
    String date = updatedAt(scene.file());

    Response response = keepVersions(scene.file());

    Assertions.assertThat(errors(response)).isEmpty();
    Assertions.assertThat(
            column(
                "SELECT keep_forever FROM revision WHERE node_id = ? AND version = 1",
                scene.file()))
        .isEqualTo("t");
    Assertions.assertThat(updatedAt(scene.file())).isEqualTo(date);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void createShareStillWorksAndKeepsTheTrashDate(boolean nested) throws SQLException {
    Scene scene = scene(nested);
    String date = updatedAt(scene.file());

    Response response = createShare(scene.file());

    Assertions.assertThat(errors(response)).isEmpty();
    Assertions.assertThat(
            column(
                "SELECT count(*) FROM share WHERE node_id = ? AND target_uuid = ?",
                scene.file(),
                OTHER_ID))
        .isEqualTo("1");
    Assertions.assertThat(updatedAt(scene.file())).isEqualTo(date);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void updateSharesStillWorksAndKeepsTheTrashDate(boolean nested) throws SQLException {
    Scene scene = scene(nested);
    String date = updatedAt(scene.file());

    Response response = updateShares(scene.file());

    Assertions.assertThat(errors(response)).isEmpty();
    Assertions.assertThat(
            column(
                "SELECT rights FROM share WHERE node_id = ? AND target_uuid = ?",
                scene.file(),
                TARGET_ID))
        .isNotEqualTo(String.valueOf(ACL.decode(SharePermission.READ_ONLY).encode()));
    Assertions.assertThat(updatedAt(scene.file())).isEqualTo(date);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void createLinkStillWorksAndKeepsTheTrashDate(boolean nested) throws SQLException {
    Scene scene = scene(nested);
    String date = updatedAt(scene.file());

    Response response = createLink(scene.file());

    Assertions.assertThat(errors(response)).isEmpty();
    Assertions.assertThat(column("SELECT count(*) FROM link WHERE node_id = ?", scene.file()))
        .isEqualTo("2");
    Assertions.assertThat(updatedAt(scene.file())).isEqualTo(date);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void updateLinkStillWorksAndKeepsTheTrashDate(boolean nested) throws SQLException {
    Scene scene = scene(nested);
    String date = updatedAt(scene.file());

    Response response = updateLink(scene.linkId());

    Assertions.assertThat(errors(response)).isEmpty();
    Assertions.assertThat(column("SELECT description FROM link WHERE node_id = ?", scene.file()))
        .isEqualTo("changed");
    Assertions.assertThat(updatedAt(scene.file())).isEqualTo(date);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void createCollaborationLinkStillWorksAndKeepsTheTrashDate(boolean nested) throws SQLException {
    Scene scene = scene(nested);
    String date = updatedAt(scene.file());

    Response response = createCollaborationLink(scene.file());

    Assertions.assertThat(errors(response)).isEmpty();
    Assertions.assertThat(
            column("SELECT count(*) FROM collaboration_link WHERE node_id = ?", scene.file()))
        .isEqualTo("2");
    Assertions.assertThat(updatedAt(scene.file())).isEqualTo(date);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void internalLinkCreationIsForbidden(boolean nested) throws SQLException {
    Scene scene = scene(nested);
    String before = snapshot(scene.file());

    Assertions.assertThat(internalCreateLink(scene.file()).statusCode()).isEqualTo(403);
    Assertions.assertThat(snapshot(scene.file())).isEqualTo(before);
  }

  @Test
  void internalLinkCreationStillWorksOutsideTheTrash() {
    String file = seedFile("live.txt", LOCAL_ROOT, "x".getBytes(), OWNER_COOKIE);

    Assertions.assertThat(internalCreateLink(file).statusCode()).isEqualTo(200);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void redeemingACollaborationLinkUpdatesTheShareAndKeepsTheTrashDate(boolean nested)
      throws SQLException {
    Scene scene = scene(nested);
    String date = updatedAt(scene.file());

    Assertions.assertThat(invite(scene.invitationId()).statusCode()).isEqualTo(307);
    Assertions.assertThat(
            column(
                "SELECT rights FROM share WHERE node_id = ? AND target_uuid = ?",
                scene.file(),
                TARGET_ID))
        .isNotEqualTo(String.valueOf(ACL.decode(SharePermission.READ_ONLY).encode()));
    Assertions.assertThat(updatedAt(scene.file())).isEqualTo(date);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void internalUploadVersionIsRefusedWithoutWritingTheBlob(boolean nested) throws SQLException {
    Scene scene = scene(nested);
    String before = snapshot(scene.file());
    MockStoragesService storages = FilesStackTestResource.getStoragesService();
    int blobs = storages.blobCount();

    Response internal = internalUploadVersion(scene.file(), false);

    Assertions.assertThat(internal.statusCode()).isEqualTo(404);
    Assertions.assertThat(snapshot(scene.file())).isEqualTo(before);
    Assertions.assertThat(versionRows(scene.file())).containsExactly(1, 2);
    Assertions.assertThat(storages.blobCount()).isEqualTo(blobs);
    Assertions.assertThat(storages.has(scene.file(), 3)).isFalse();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void publicUploadVersionStillWorksAndKeepsTheTrashDate(boolean nested) throws SQLException {
    Scene scene = scene(nested);
    String date = updatedAt(scene.file());

    Response response =
        uploadVersion(scene.file(), "three".getBytes(), "doc.txt", false, OWNER_COOKIE);

    Assertions.assertThat(response.statusCode()).isEqualTo(200);
    Assertions.assertThat(versionRows(scene.file())).containsExactly(1, 2, 3);
    Assertions.assertThat(
            column("SELECT current_version FROM node WHERE node_id = ?", scene.file()))
        .isEqualTo("3");
    Assertions.assertThat(FilesStackTestResource.getStoragesService().has(scene.file(), 3))
        .isTrue();
    Assertions.assertThat(updatedAt(scene.file())).isEqualTo(date);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void docsOverwriteIsRefusedBeforeAnyByteIsWritten(boolean nested) throws SQLException {
    Scene scene = scene(nested);
    String before = snapshot(scene.file());
    MockStoragesService storages = FilesStackTestResource.getStoragesService();
    int blobs = storages.blobCount();

    Response response =
        uploadVersion(scene.file(), "three".getBytes(), "doc.txt", true, OWNER_COOKIE);
    Response internal = internalUploadVersion(scene.file(), true);

    Assertions.assertThat(response.statusCode()).isEqualTo(404);
    Assertions.assertThat(internal.statusCode()).isEqualTo(404);
    Assertions.assertThat(snapshot(scene.file())).isEqualTo(before);
    Assertions.assertThat(versionRows(scene.file())).containsExactly(1, 2);
    Assertions.assertThat(storages.blobCount()).isEqualTo(blobs);
    Assertions.assertThat(download(scene.file(), OWNER_COOKIE).asString()).isEqualTo("two");
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void uploadVersionOutsideTheTrashStillWorks(boolean overwrite) {
    String file = seedFile("live.txt", LOCAL_ROOT, "one".getBytes(), OWNER_COOKIE);

    Assertions.assertThat(
            uploadVersion(file, "two".getBytes(), "live.txt", overwrite, OWNER_COOKIE).statusCode())
        .isEqualTo(200);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void deleteVersionsStillWorks(boolean nested) throws SQLException {
    Scene scene = scene(nested);
    String date = updatedAt(scene.file());

    Response response =
        mutation(
            GraphqlCommandBuilder.aMutationBuilder("deleteVersions")
                .withString("node_id", scene.file())
                .withListOfIntegers("versions", new int[] {1}),
            OWNER_COOKIE);

    Assertions.assertThat(errors(response)).isEmpty();
    Assertions.assertThat(versionRows(scene.file())).containsExactly(2);
    Assertions.assertThat(updatedAt(scene.file())).isEqualTo(date);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void deleteSharesStillWorks(boolean nested) throws SQLException {
    Scene scene = scene(nested);
    String date = updatedAt(scene.file());

    Response response =
        mutation(
            GraphqlCommandBuilder.aMutationBuilder("deleteShares")
                .withString("node_id", scene.file())
                .withListOfStrings("share_target_ids", new String[] {TARGET_ID}),
            OWNER_COOKIE);

    Assertions.assertThat(errors(response)).isEmpty();
    Assertions.assertThat(column("SELECT count(*) FROM share WHERE node_id = ?", scene.file()))
        .isEqualTo("0");
    Assertions.assertThat(updatedAt(scene.file())).isEqualTo(date);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void deleteLinksStillWorks(boolean nested) throws SQLException {
    Scene scene = scene(nested);
    String date = updatedAt(scene.file());

    Response response =
        mutation(
            GraphqlCommandBuilder.aMutationBuilder("deleteLinks")
                .withListOfStrings("link_ids", new String[] {scene.linkId()}),
            OWNER_COOKIE);

    Assertions.assertThat(errors(response)).isEmpty();
    Assertions.assertThat(column("SELECT count(*) FROM link WHERE node_id = ?", scene.file()))
        .isEqualTo("0");
    Assertions.assertThat(updatedAt(scene.file())).isEqualTo(date);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void deleteCollaborationLinksStillWorks(boolean nested) throws SQLException {
    Scene scene = scene(nested);
    String date = updatedAt(scene.file());

    Response response =
        mutation(
            GraphqlCommandBuilder.aMutationBuilder("deleteCollaborationLinks")
                .withListOfStrings(
                    "collaboration_link_ids", new String[] {scene.collaborationId()}),
            OWNER_COOKIE);

    Assertions.assertThat(errors(response)).isEmpty();
    Assertions.assertThat(
            column("SELECT count(*) FROM collaboration_link WHERE node_id = ?", scene.file()))
        .isEqualTo("0");
    Assertions.assertThat(updatedAt(scene.file())).isEqualTo(date);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void permanentDeleteStillWorks(boolean nested) throws SQLException {
    Scene scene = scene(nested);

    Response response =
        mutation(
            GraphqlCommandBuilder.aMutationBuilder("deleteNodes")
                .withListOfStrings("node_ids", new String[] {scene.file()}),
            OWNER_COOKIE);

    Assertions.assertThat(errors(response)).isEmpty();
    Assertions.assertThat(column("SELECT count(*) FROM node WHERE node_id = ?", scene.file()))
        .isEqualTo("0");
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void restoreStillWorks(boolean nested) throws SQLException {
    Scene scene = scene(nested);

    Response response =
        mutation(
            GraphqlCommandBuilder.aMutationBuilder("restoreNodes")
                .withListOfStrings("node_ids", new String[] {scene.trashedId()}),
            "{ id }",
            OWNER_COOKIE);

    Assertions.assertThat(errors(response)).isEmpty();
    Assertions.assertThat(column("SELECT ancestor_ids FROM node WHERE node_id = ?", scene.file()))
        .doesNotContain(TRASH_ROOT);
  }

  private static void trashInHolder(
      Connection holder, String nodeId, String parentId, String ancestors) throws SQLException {
    try (PreparedStatement update =
            holder.prepareStatement(
                "UPDATE node SET folder_id = ?, ancestor_ids = ? WHERE node_id = ?");
        PreparedStatement trashed =
            holder.prepareStatement("INSERT INTO trashed (node_id, parent_id) VALUES (?, ?)")) {
      update.setString(1, TRASH_ROOT);
      update.setString(2, ancestors);
      update.setString(3, nodeId);
      update.executeUpdate();
      trashed.setString(1, nodeId);
      trashed.setString(2, parentId);
      trashed.executeUpdate();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"name", "flagged"})
  void aRenameBehindATrashIsRefusedAfterTheLock(String field) throws Exception {
    String file = seedFile("doc.txt", LOCAL_ROOT, "one".getBytes(), OWNER_COOKIE);
    String name = column("SELECT name FROM node WHERE node_id = ?", file);
    String description = column("SELECT description FROM node WHERE node_id = ?", file);
    String updatedAt = column("SELECT updated_timestamp FROM node WHERE node_id = ?", file);

    Response response;
    try (Connection holder = holdNode(file, "FOR NO KEY UPDATE")) {
      CompletableFuture<Response> updating =
          async(() -> updateNode(file, field, "name".equals(field) ? "renamed" : (Object) true));
      awaitBlockedBy(holder, 1);
      trashInHolder(holder, file, LOCAL_ROOT, TRASH_ROOT);
      holder.commit();
      response = updating.get(60, TimeUnit.SECONDS);
    }

    Assertions.assertThat(errorCodes(response)).containsExactly(NODE_WRITE_ERROR);
    Assertions.assertThat(column("SELECT name FROM node WHERE node_id = ?", file)).isEqualTo(name);
    Assertions.assertThat(column("SELECT description FROM node WHERE node_id = ?", file))
        .isEqualTo(description);
    Assertions.assertThat(column("SELECT updated_timestamp FROM node WHERE node_id = ?", file))
        .isEqualTo(updatedAt);
    Assertions.assertThat(column("SELECT folder_id FROM node WHERE node_id = ?", file).trim())
        .isEqualTo(TRASH_ROOT);
  }

  @Test
  void aDescriptionBehindATrashIsSavedWithoutRefreshingTheTrashDate() throws Exception {
    String file = seedFile("doc.txt", LOCAL_ROOT, "one".getBytes(), OWNER_COOKIE);
    String date = updatedAt(file);

    Response response;
    try (Connection holder = holdNode(file, "FOR NO KEY UPDATE")) {
      CompletableFuture<Response> updating =
          async(() -> updateNode(file, "description", "changed"));
      awaitBlockedBy(holder, 1);
      trashInHolder(holder, file, LOCAL_ROOT, TRASH_ROOT);
      holder.commit();
      response = updating.get(60, TimeUnit.SECONDS);
    }

    Assertions.assertThat(errors(response)).isEmpty();
    Assertions.assertThat(column("SELECT description FROM node WHERE node_id = ?", file))
        .isEqualTo("changed");
    Assertions.assertThat(updatedAt(file)).isEqualTo(date);
  }

  @Test
  void anInternalVersionUploadBehindATrashIsDiscardedAtPublish() throws Exception {
    MockStoragesService storages = FilesStackTestResource.getStoragesService();
    String folder = seedFolder("parent", LOCAL_ROOT, OWNER_COOKIE);
    String file = seedFile("doc.txt", folder, "one".getBytes(), OWNER_COOKIE);
    int blobs = storages.blobCount();

    Response response;
    try (Connection holder = holdNode(folder, "FOR NO KEY UPDATE")) {
      CompletableFuture<Response> uploading = async(() -> internalUploadVersion(file, false));
      awaitBlockedBy(holder, 1);
      Assertions.assertThat(storages.has(file, 2)).isTrue();
      trashInHolder(holder, folder, LOCAL_ROOT, TRASH_ROOT);
      try (PreparedStatement descendants =
          holder.prepareStatement("UPDATE node SET ancestor_ids = ? WHERE node_id = ?")) {
        descendants.setString(1, TRASH_ROOT + "," + folder);
        descendants.setString(2, file);
        descendants.executeUpdate();
      }
      holder.commit();
      response = uploading.get(60, TimeUnit.SECONDS);
    }

    Assertions.assertThat(response.statusCode()).isEqualTo(404);
    Assertions.assertThat(versionRows(file)).containsExactly(1);
    Assertions.assertThat(column("SELECT current_version FROM node WHERE node_id = ?", file))
        .isEqualTo("1");
    Assertions.assertThat(storages.has(file, 2)).isFalse();
    Assertions.assertThat(storages.blobCount()).isEqualTo(blobs);
    Assertions.assertThat(pendingReservations(file)).isEmpty();
    Assertions.assertThat(tombstoneRowsForNode(file)).isZero();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void redeemingACollaborationLinkCreatesAShareAndKeepsTheTrashDate(boolean nested)
      throws SQLException {
    String[] fileAndInvitation = unsharedTrashedFileWithInvitation(nested).split(":");
    String file = fileAndInvitation[0];
    String date = updatedAt(file);

    Assertions.assertThat(invite(fileAndInvitation[1]).statusCode()).isEqualTo(307);
    Assertions.assertThat(column("SELECT count(*) FROM share WHERE node_id = ?", file))
        .isEqualTo("1");
    Assertions.assertThat(updatedAt(file)).isEqualTo(date);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void theOwnerRedeemingACollaborationLinkOnATrashedNodeChangesNothing(boolean nested)
      throws SQLException {
    String[] fileAndInvitation = unsharedTrashedFileWithInvitation(nested).split(":");
    String file = fileAndInvitation[0];
    String before = snapshot(file);

    Assertions.assertThat(invite(fileAndInvitation[1], OWNER_COOKIE).statusCode()).isEqualTo(307);
    Assertions.assertThat(snapshot(file)).isEqualTo(before);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void copyingATrashedNodeElsewhereStillWorks(boolean nested) {
    Scene scene = scene(nested);
    String destination = seedFolder("destination", LOCAL_ROOT, OWNER_COOKIE);

    Response response =
        graphql(
            GraphqlCommandBuilder.aMutationBuilder("copyNodes")
                .withListOfStrings("node_ids", new String[] {scene.file()})
                .withString("destination_id", destination)
                .withWantedResultFormat("{ id }")
                .build(),
            OWNER_COOKIE);

    Assertions.assertThat(errors(response)).isEmpty();
    Assertions.assertThat(childIds(destination, OWNER_COOKIE)).hasSize(1);
  }

  @Test
  void aCloneVersionBehindATrashCompletesAndKeepsTheTrashDate() throws Exception {
    String folder = seedFolder("parent", LOCAL_ROOT, OWNER_COOKIE);
    String file = seedFile("doc.txt", folder, "one".getBytes(), OWNER_COOKIE);
    String date = updatedAt(file);

    Response response;
    try (Connection holder = holdNode(folder, "FOR NO KEY UPDATE")) {
      CompletableFuture<Response> cloning = async(() -> cloneVersion(file));
      awaitBlockedBy(holder, 1);
      trashInHolder(holder, folder, LOCAL_ROOT, TRASH_ROOT);
      try (PreparedStatement descendants =
          holder.prepareStatement("UPDATE node SET ancestor_ids = ? WHERE node_id = ?")) {
        descendants.setString(1, TRASH_ROOT + "," + folder);
        descendants.setString(2, file);
        descendants.executeUpdate();
      }
      holder.commit();
      response = cloning.get(60, TimeUnit.SECONDS);
    }

    Assertions.assertThat(errors(response)).isEmpty();
    Assertions.assertThat(versionRows(file)).containsExactly(1, 2);
    Assertions.assertThat(updatedAt(file)).isEqualTo(date);
  }
}
