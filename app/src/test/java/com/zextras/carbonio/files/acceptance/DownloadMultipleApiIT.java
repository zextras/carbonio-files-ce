// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.acceptance;

import com.zextras.carbonio.files.FilesStackTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zextras.carbonio.files.Constants;
import com.zextras.carbonio.files.acceptance.seam.FilesTestApp;
import com.zextras.carbonio.files.acceptance.seam.impl.QuarkusFilesTestAppBuilder;
import com.zextras.carbonio.files.api.utilities.entities.PopulatorNode;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.utilities.http.HttpRequest;
import com.zextras.carbonio.files.utilities.http.HttpResponse;
import io.restassured.response.Response;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
public class DownloadMultipleApiIT {

  static FilesTestApp app;
  static ObjectMapper objectMapper = new ObjectMapper();

  @BeforeAll
  static void init() {
    app =
        QuarkusFilesTestAppBuilder.aFilesTestApp()
            .withDatabase()
            .withServiceDiscover()
            .withUserManagement(Map.of("fake-token", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"))
            .withStorages()
            .build();
  }

  @AfterEach
  void cleanUp() {
    app.backdoor().resetDatabase();
  }

  @AfterAll
  static void cleanUpAll() {
    app.close();
  }

  @Test
  void givenMultipleFilesInSameFolderTheDownloadMultipleShouldReturnZipWith200() throws Exception {
    // Given
    String folderId = "11111111-1111-1111-1111-111111111101";
    String fileId1 = "00000000-0000-0000-0000-000000000101";
    String fileId2 = "00000000-0000-0000-0000-000000000102";
    String fileId3 = "00000000-0000-0000-0000-000000000103";

    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                folderId,
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                Constants.Db.RootId.LOCAL_ROOT,
                "test-folder",
                "",
                NodeType.FOLDER,
                Constants.Db.RootId.LOCAL_ROOT,
                0L,
                null))
        .addNode(
            new PopulatorNode(
                fileId1,
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                folderId,
                "file1.txt",
                "",
                NodeType.TEXT,
                Constants.Db.RootId.LOCAL_ROOT + "," + folderId,
                10L,
                "text/plain"))
        .addNode(
            new PopulatorNode(
                fileId2,
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                folderId,
                "file2.pdf",
                "",
                NodeType.APPLICATION,
                Constants.Db.RootId.LOCAL_ROOT + "," + folderId,
                20L,
                "application/pdf"))
        .addNode(
            new PopulatorNode(
                fileId3,
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                folderId,
                "file3.jpg",
                "",
                NodeType.IMAGE,
                Constants.Db.RootId.LOCAL_ROOT + "," + folderId,
                30L,
                "image/jpeg"));

    // Mock storages responses for each file
    app.mocks().storagesServesBlob(fileId1, 1);
    app.mocks().storagesServesBlob(fileId2, 1);
    app.mocks().storagesServesBlob(fileId3, 1);

    List<String> nodeIds = List.of(fileId1, fileId2, fileId3);
    String jsonArray = objectMapper.writeValueAsString(nodeIds);
    String requestBody = "nodeIds=" + URLEncoder.encode(jsonArray, StandardCharsets.UTF_8);

    List<Map.Entry<String, String>> headers = List.of(
        Map.entry("Content-Type", "application/x-www-form-urlencoded")
    );

    final HttpRequest httpRequest = HttpRequest.of(
        "POST",
        "/download-multiple",
        "ZM_AUTH_TOKEN=fake-token",
        headers,
        requestBody
    );

    // When
    final HttpResponse httpResponse = app.sendForm(httpRequest);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Assertions.assertThat(httpResponse.getHeaders())
        .anyMatch(header ->
            header.getKey().equalsIgnoreCase("content-type") &&
                header.getValue().contains("application/zip")
        );
    Assertions.assertThat(httpResponse.getHeaders())
        .anyMatch(header ->
            header.getKey().equalsIgnoreCase("content-disposition") &&
                header.getValue().contains("attachment") &&
                header.getValue().contains("Files.zip")
        );

    // Verify storages was called for each file
    app.mocks().verifyStoragesDownloaded(fileId1, 1);
  }

  // -------------------------------------------------------- Content-Length vs chunked (decision B)

  /**
   * Locks decision B: a ZIP/multi-download response has no known length up front, so {@code
   * TransferStreaming#streamZip} enables chunked mode explicitly and never sets {@code
   * Content-Length} — the mirror image of {@link AuthenticatedDownloadApiIT}'s single-download
   * fixed-length assertion. Driven directly with RestAssured (rather than {@code app.sendForm}) so
   * the exact wire header set can be asserted precisely.
   */
  @Test
  void givenMultipleFilesTheDownloadMultipleResponseShouldBeChunkedNotFixedLength()
      throws Exception {
    // Given
    String folderId = "11111111-1111-1111-1111-111111111601";
    String fileId1 = "00000000-0000-0000-0000-000000000601";
    String fileId2 = "00000000-0000-0000-0000-000000000602";

    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                folderId,
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                Constants.Db.RootId.LOCAL_ROOT,
                "chunked-test-folder",
                "",
                NodeType.FOLDER,
                Constants.Db.RootId.LOCAL_ROOT,
                0L,
                null))
        .addNode(
            new PopulatorNode(
                fileId1,
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                folderId,
                "file1.txt",
                "",
                NodeType.TEXT,
                Constants.Db.RootId.LOCAL_ROOT + "," + folderId,
                10L,
                "text/plain"))
        .addNode(
            new PopulatorNode(
                fileId2,
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                folderId,
                "file2.txt",
                "",
                NodeType.TEXT,
                Constants.Db.RootId.LOCAL_ROOT + "," + folderId,
                20L,
                "text/plain"));

    app.mocks().storagesServesBlob(fileId1, 1);
    app.mocks().storagesServesBlob(fileId2, 1);

    List<String> nodeIds = List.of(fileId1, fileId2);
    String jsonArray = objectMapper.writeValueAsString(nodeIds);

    // When
    Response response =
        io.restassured.RestAssured.given()
            .cookie("ZM_AUTH_TOKEN", "fake-token")
            .contentType("application/x-www-form-urlencoded")
            .formParam("nodeIds", jsonArray)
            .when()
            .post("/download-multiple");

    // Then
    response.then().statusCode(200);
    Assertions.assertThat(response.getHeader("Content-Length"))
        .as("a streamed ZIP has no fixed Content-Length")
        .isNull();
    Assertions.assertThat(response.getHeader("Transfer-Encoding"))
        .as("a streamed ZIP must be chunked")
        .isEqualToIgnoringCase("chunked");
  }

  @Test
  void givenMixedNodesInSameFolderTheDownloadMultipleShouldReturnZipWith200() throws Exception {
    // Given
    String parentFolderId = "11111111-1111-1111-1111-111111111201";
    String subFolderId = "22222222-2222-2222-2222-222222222201";
    String fileId1 = "00000000-0000-0000-0000-000000000201";
    String fileId2 = "00000000-0000-0000-0000-000000000202";

    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                parentFolderId,
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                Constants.Db.RootId.LOCAL_ROOT,
                "parent-folder",
                "",
                NodeType.FOLDER,
                Constants.Db.RootId.LOCAL_ROOT,
                0L,
                null))
        .addNode(
            new PopulatorNode(
                subFolderId,
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                parentFolderId,
                "sub-folder",
                "",
                NodeType.FOLDER,
                Constants.Db.RootId.LOCAL_ROOT + "," + parentFolderId,
                0L,
                null))
        .addNode(
            new PopulatorNode(
                fileId1,
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                parentFolderId,
                "file1.txt",
                "",
                NodeType.TEXT,
                Constants.Db.RootId.LOCAL_ROOT + "," + parentFolderId,
                10L,
                "text/plain"))
        .addNode(
            new PopulatorNode(
                fileId2,
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                subFolderId,
                "file2.txt",
                "",
                NodeType.TEXT,
                Constants.Db.RootId.LOCAL_ROOT + "," + parentFolderId + "," + subFolderId,
                20L,
                "text/plain"));

    // Mock storages responses
    app.mocks().storagesServesBlob(fileId1, 1);
    app.mocks().storagesServesBlob(fileId2, 1);

    List<String> nodeIds = List.of(fileId1, subFolderId);
    String jsonArray = objectMapper.writeValueAsString(nodeIds);
    String requestBody = "nodeIds=" + URLEncoder.encode(jsonArray, StandardCharsets.UTF_8);

    List<Map.Entry<String, String>> headers = List.of(
        Map.entry("Content-Type", "application/x-www-form-urlencoded")
    );

    final HttpRequest httpRequest = HttpRequest.of(
        "POST",
        "/download-multiple",
        "ZM_AUTH_TOKEN=fake-token",
        headers,
        requestBody
    );

    // When
    final HttpResponse httpResponse = app.sendForm(httpRequest);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
  }

  @Test
  void givenLocalRootAsNodeIdTheDownloadMultipleShouldReturnZipWith200() throws Exception {
    // Given
    String fileId1 = "00000000-0000-0000-0000-000000000301";
    String fileId2 = "00000000-0000-0000-0000-000000000302";
    String folderId = "11111111-1111-1111-1111-111111111301";

    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                fileId1,
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                Constants.Db.RootId.LOCAL_ROOT,
                "file1.txt",
                "",
                NodeType.TEXT,
                Constants.Db.RootId.LOCAL_ROOT,
                10L,
                "text/plain"))
        .addNode(
            new PopulatorNode(
                fileId2,
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                Constants.Db.RootId.LOCAL_ROOT,
                "file2.txt",
                "",
                NodeType.TEXT,
                Constants.Db.RootId.LOCAL_ROOT,
                20L,
                "text/plain"))
        .addNode(
            new PopulatorNode(
                folderId,
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                Constants.Db.RootId.LOCAL_ROOT,
                "folder",
                "",
                NodeType.FOLDER,
                Constants.Db.RootId.LOCAL_ROOT,
                0L,
                null));

    // Mock storages responses
    app.mocks().storagesServesBlob(fileId1, 1);
    app.mocks().storagesServesBlob(fileId2, 1);

    List<String> nodeIds = List.of(Constants.Db.RootId.LOCAL_ROOT);
    String jsonArray = objectMapper.writeValueAsString(nodeIds);
    String requestBody = "nodeIds=" + URLEncoder.encode(jsonArray, StandardCharsets.UTF_8);

    List<Map.Entry<String, String>> headers = List.of(
        Map.entry("Content-Type", "application/x-www-form-urlencoded")
    );

    final HttpRequest httpRequest = HttpRequest.of(
        "POST",
        "/download-multiple",
        "ZM_AUTH_TOKEN=fake-token",
        headers,
        requestBody
    );

    // When
    final HttpResponse httpResponse = app.sendForm(httpRequest);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Assertions.assertThat(httpResponse.getHeaders())
        .anyMatch(header ->
            header.getKey().equalsIgnoreCase("content-type") &&
                header.getValue().contains("application/zip")
        );
  }

  @Test
  void givenLocalRootWithOtherNodesTheDownloadMultipleShouldReturn400() throws Exception {
    // Given
    String fileId = "00000000-0000-0000-0000-000000000501";

    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                fileId,
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                Constants.Db.RootId.LOCAL_ROOT,
                "file.txt",
                "",
                NodeType.TEXT,
                Constants.Db.RootId.LOCAL_ROOT,
                10L,
                "text/plain"));

    List<String> nodeIds = List.of(Constants.Db.RootId.LOCAL_ROOT, fileId);
    String jsonArray = objectMapper.writeValueAsString(nodeIds);
    String requestBody = "nodeIds=" + URLEncoder.encode(jsonArray, StandardCharsets.UTF_8);

    List<Map.Entry<String, String>> headers = List.of(
        Map.entry("Content-Type", "application/x-www-form-urlencoded")
    );

    final HttpRequest httpRequest = HttpRequest.of(
        "POST",
        "/download-multiple",
        "ZM_AUTH_TOKEN=fake-token",
        headers,
        requestBody
    );

    // When
    final HttpResponse httpResponse = app.sendForm(httpRequest);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(400);
  }

  @Test
  void givenEmptyNodeListTheDownloadMultipleShouldReturn400() throws Exception {
    // Given
    List<String> nodeIds = List.of();
    String jsonArray = objectMapper.writeValueAsString(nodeIds);
    String requestBody = "nodeIds=" + URLEncoder.encode(jsonArray, StandardCharsets.UTF_8);

    List<Map.Entry<String, String>> headers = List.of(
        Map.entry("Content-Type", "application/x-www-form-urlencoded")
    );

    final HttpRequest httpRequest = HttpRequest.of(
        "POST",
        "/download-multiple",
        "ZM_AUTH_TOKEN=fake-token",
        headers,
        requestBody
    );

    // When
    final HttpResponse httpResponse = app.sendForm(httpRequest);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(400);
  }

  @Test
  void givenMissingRequestBodyTheDownloadMultipleShouldReturn400() {
    // Given
    List<Map.Entry<String, String>> headers = List.of(
        Map.entry("Content-Type", "application/x-www-form-urlencoded")
    );

    final HttpRequest httpRequest = HttpRequest.of(
        "POST",
        "/download-multiple",
        "ZM_AUTH_TOKEN=fake-token",
        headers,
        null
    );

    // When
    final HttpResponse httpResponse = app.sendForm(httpRequest);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(400);
  }

  @Test
  void givenInvalidJsonFormatTheDownloadMultipleShouldReturn400() {
    // Given
    String requestBody = "nodeIds=invalid-json";

    List<Map.Entry<String, String>> headers = List.of(
        Map.entry("Content-Type", "application/x-www-form-urlencoded")
    );

    final HttpRequest httpRequest = HttpRequest.of(
        "POST",
        "/download-multiple",
        "ZM_AUTH_TOKEN=fake-token",
        headers,
        requestBody
    );

    // When
    final HttpResponse httpResponse = app.sendForm(httpRequest);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(400);
  }

  @Test
  void givenMissingNodeIdsParameterTheDownloadMultipleShouldReturn400() throws Exception {
    // Given
    String requestBody = "wrongParam=" + URLEncoder.encode("[\"00000000-0000-0000-0000-000000000001\"]", StandardCharsets.UTF_8);

    List<Map.Entry<String, String>> headers = List.of(
        Map.entry("Content-Type", "application/x-www-form-urlencoded")
    );

    final HttpRequest httpRequest = HttpRequest.of(
        "POST",
        "/download-multiple",
        "ZM_AUTH_TOKEN=fake-token",
        headers,
        requestBody
    );

    // When
    final HttpResponse httpResponse = app.sendForm(httpRequest);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(400);
  }

  @Test
  void givenSharedNodeTheDownloadMultipleShouldReturnZipWith200() throws Exception {
    // Given
    String folderId = "11111111-1111-1111-1111-111111111901";
    String fileId = "00000000-0000-0000-0000-000000000901";

    // Create a folder owned by another user but shared with our user
    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                folderId,
                "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                Constants.Db.RootId.LOCAL_ROOT,
                "shared-folder",
                "",
                NodeType.FOLDER,
                Constants.Db.RootId.LOCAL_ROOT,
                0L,
                null))
        .addShare(folderId, "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", ACL.SharePermission.READ_ONLY)
        .addNode(
            new PopulatorNode(
                fileId,
                "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                folderId,
                "shared-file.txt",
                "",
                NodeType.TEXT,
                Constants.Db.RootId.LOCAL_ROOT + "," + folderId,
                10L,
                "text/plain"))
        .addShare(fileId, "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", ACL.SharePermission.READ_ONLY);

    // Mock storages response
    app.mocks().storagesServesBlob(fileId, 1);

    List<String> nodeIds = List.of(fileId);
    String jsonArray = objectMapper.writeValueAsString(nodeIds);
    String requestBody = "nodeIds=" + URLEncoder.encode(jsonArray, StandardCharsets.UTF_8);

    List<Map.Entry<String, String>> headers = List.of(
        Map.entry("Content-Type", "application/x-www-form-urlencoded")
    );

    final HttpRequest httpRequest = HttpRequest.of(
        "POST",
        "/download-multiple",
        "ZM_AUTH_TOKEN=fake-token",
        headers,
        requestBody
    );

    // When
    final HttpResponse httpResponse = app.sendForm(httpRequest);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
  }
}
