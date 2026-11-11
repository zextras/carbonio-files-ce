// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class NoChildrenInTrashApiIT extends AbstractFilesIT {

  private static final String USER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String COOKIE = "ZM_AUTH_TOKEN=fake-token";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", USER_ID);
  }

  private static int children(String folderId) throws SQLException {
    try (Connection connection = jdbcConnection();
        PreparedStatement statement =
            connection.prepareStatement("SELECT count(*) FROM node WHERE folder_id = ?")) {
      statement.setString(1, folderId);
      try (ResultSet rows = statement.executeQuery()) {
        rows.next();
        return rows.getInt(1);
      }
    }
  }

  private static Response createFolderMutation(String destinationId) {
    return graphql(
        GraphqlCommandBuilder.aMutationBuilder("createFolder")
            .withString("destination_id", destinationId)
            .withString("name", "refused")
            .withWantedResultFormat("{ id }")
            .build(),
        COOKIE);
  }

  private static Response copyMutation(String destinationId, String nodeId) {
    return graphql(
        GraphqlCommandBuilder.aMutationBuilder("copyNodes")
            .withListOfStrings("node_ids", new String[] {nodeId})
            .withString("destination_id", destinationId)
            .withWantedResultFormat("{ id }")
            .build(),
        COOKIE);
  }

  private static Response internalUpload(String parentId) {
    return RestAssured.given()
        .header("Filename", Base64.getEncoder().encodeToString("up.txt".getBytes()))
        .header("ParentId", parentId)
        .body("x".getBytes())
        .post("/internal/accounts/" + USER_ID + "/upload");
  }

  private static Response internalCreateFolder(String destinationId) {
    return RestAssured.given()
        .contentType(ContentType.JSON)
        .body(Map.of("userId", USER_ID, "destinationId", destinationId, "name", "refused"))
        .post("/internal/folders");
  }

  private static String trashedTop() {
    String top = seedFolder("trashed-top", LOCAL_ROOT, COOKIE);
    seedTrashed(top, COOKIE);
    return top;
  }

  private static String nestedInTrashed() {
    String top = seedFolder("trashed-with-nested", LOCAL_ROOT, COOKIE);
    String nested = seedFolder("nested", top, COOKIE);
    seedTrashed(top, COOKIE);
    return nested;
  }

  private void assertCreateFolderRefused(String destination) throws SQLException {
    Response response = createFolderMutation(destination);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(response.getBody().asString()))
        .isNotEmpty();
    Assertions.assertThat(children(destination)).isZero();
  }

  private void assertUploadRefused(String destination) throws SQLException {
    Assertions.assertThat(upload(destination, null, "x".getBytes(), "up.txt", COOKIE).statusCode())
        .isEqualTo(404);
    Assertions.assertThat(internalUpload(destination).statusCode()).isEqualTo(404);
    Assertions.assertThat(children(destination)).isZero();
    Assertions.assertThat(FilesStackTestResource.getStoragesService().blobCount()).isZero();
    Assertions.assertThat(pendingReservationsWithoutNode()).isEmpty();
  }

  private void assertCopyRefused(String destination) throws SQLException {
    String file = seedFile("source.txt", LOCAL_ROOT, "c".getBytes(), COOKIE);
    String folder = seedFolder("source-folder", LOCAL_ROOT, COOKIE);

    Assertions.assertThat(
            TestUtils.jsonResponseToErrors(copyMutation(destination, file).getBody().asString()))
        .isNotEmpty();
    Assertions.assertThat(
            TestUtils.jsonResponseToErrors(copyMutation(destination, folder).getBody().asString()))
        .isNotEmpty();
    Assertions.assertThat(children(destination)).isZero();
  }

  @Test
  void createFolderInATrashedTopFolderIsRefused() throws SQLException {
    assertCreateFolderRefused(trashedTop());
  }

  @Test
  void createFolderInAFolderNestedInTheTrashIsRefused() throws SQLException {
    assertCreateFolderRefused(nestedInTrashed());
  }

  @Test
  void internalCreateFolderInTheTrashIsNotFound() throws SQLException {
    String top = trashedTop();
    String nested = nestedInTrashed();

    Assertions.assertThat(internalCreateFolder(top).statusCode()).isEqualTo(404);
    Assertions.assertThat(internalCreateFolder(nested).statusCode()).isEqualTo(404);
    Assertions.assertThat(children(top)).isZero();
    Assertions.assertThat(children(nested)).isZero();
  }

  @Test
  void uploadInATrashedTopFolderIsRefused() throws SQLException {
    assertUploadRefused(trashedTop());
  }

  @Test
  void uploadInAFolderNestedInTheTrashIsRefused() throws SQLException {
    assertUploadRefused(nestedInTrashed());
  }

  @Test
  void copyIntoATrashedTopFolderIsRefused() throws SQLException {
    assertCopyRefused(trashedTop());
  }

  @Test
  void copyIntoAFolderNestedInTheTrashIsRefused() throws SQLException {
    assertCopyRefused(nestedInTrashed());
  }
}
