// SPDX-FileCopyrightText: 2026 2026 Zextras <https://www.zextras.com>
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
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.utilities.http.HttpRequest;
import com.zextras.carbonio.files.utilities.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
public class PublicDownloadMultipleApiIT {

  static FilesTestApp app;
  static ObjectMapper objectMapper = new ObjectMapper();

  @BeforeAll
  static void init() {
    app =
        QuarkusFilesTestAppBuilder.aFilesTestApp()
            .withDatabase()
            .withServiceDiscover()
            .withUserManagement(Map.of())
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
  void givenMultipleFilesWithPublicLinkTheDownloadMultipleShouldReturnZipWith200()
      throws Exception {
    String userId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    String folderId = "11111111-1111-1111-1111-111111111101";
    String fileId1 = "00000000-0000-0000-0000-000000000101";
    String fileId2 = "00000000-0000-0000-0000-000000000102";
    String fileId3 = "00000000-0000-0000-0000-000000000103";
    String linkId = UUID.randomUUID().toString();
    String publicId = UUID.randomUUID().toString();

    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                folderId,
                userId,
                userId,
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
                userId,
                userId,
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
                userId,
                userId,
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
                userId,
                userId,
                folderId,
                "file3.jpg",
                "",
                NodeType.IMAGE,
                Constants.Db.RootId.LOCAL_ROOT + "," + folderId,
                30L,
                "image/jpeg"))
        .addLink(
            linkId,
            folderId,
            publicId,
            Optional.empty(),
            Optional.of("Public folder link"),
            Optional.empty());

    app.mocks().storagesServesBlob(fileId1, 1);
    app.mocks().storagesServesBlob(fileId2, 1);
    app.mocks().storagesServesBlob(fileId3, 1);

    List<String> nodeIds = List.of(fileId1, fileId2, fileId3);
    String jsonArray = objectMapper.writeValueAsString(nodeIds);
    String requestBody =
        "nodeIds="
            + URLEncoder.encode(jsonArray, StandardCharsets.UTF_8)
            + "&nodeLinkId="
            + publicId;

    List<Map.Entry<String, String>> headers =
        List.of(Map.entry("Content-Type", "application/x-www-form-urlencoded"));

    final HttpRequest httpRequest =
        HttpRequest.of("POST", "/public/download-multiple", null, headers, requestBody);

    final HttpResponse httpResponse = app.sendForm(httpRequest);

    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Assertions.assertThat(httpResponse.getHeaders())
        .anyMatch(
            header ->
                header.getKey().equalsIgnoreCase("content-type")
                    && header.getValue().contains("application/zip"));
    Assertions.assertThat(httpResponse.getHeaders())
        .anyMatch(
            header ->
                header.getKey().equalsIgnoreCase("content-disposition")
                    && header.getValue().contains("attachment")
                    && header.getValue().contains("Files.zip"));

    app.mocks().verifyStoragesDownloaded(fileId1, 1);
  }

  @Test
  void givenPublicLinkWithAccessCodeTheDownloadMultipleShouldReturnZipWith200() throws Exception {
    String userId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    String folderId = "11111111-1111-1111-1111-111111111201";
    String fileId1 = "00000000-0000-0000-0000-000000000201";
    String fileId2 = "00000000-0000-0000-0000-000000000202";
    String linkId = UUID.randomUUID().toString();
    String publicId = UUID.randomUUID().toString();
    String accessCode = "secret123";

    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                folderId,
                userId,
                userId,
                Constants.Db.RootId.LOCAL_ROOT,
                "protected-folder",
                "",
                NodeType.FOLDER,
                Constants.Db.RootId.LOCAL_ROOT,
                0L,
                null))
        .addNode(
            new PopulatorNode(
                fileId1,
                userId,
                userId,
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
                userId,
                userId,
                folderId,
                "file2.txt",
                "",
                NodeType.TEXT,
                Constants.Db.RootId.LOCAL_ROOT + "," + folderId,
                20L,
                "text/plain"))
        .addLink(
            linkId,
            folderId,
            publicId,
            Optional.empty(),
            Optional.of("Protected link"),
            Optional.of(accessCode));

    app.mocks().storagesServesBlob(fileId1, 1);
    app.mocks().storagesServesBlob(fileId2, 1);

    List<String> nodeIds = List.of(fileId1, fileId2);
    String jsonArray = objectMapper.writeValueAsString(nodeIds);
    String requestBody =
        "nodeIds="
            + URLEncoder.encode(jsonArray, StandardCharsets.UTF_8)
            + "&nodeLinkId="
            + publicId
            + "&accessCode="
            + accessCode;

    List<Map.Entry<String, String>> headers =
        List.of(Map.entry("Content-Type", "application/x-www-form-urlencoded"));

    final HttpRequest httpRequest =
        HttpRequest.of("POST", "/public/download-multiple", null, headers, requestBody);

    final HttpResponse httpResponse = app.sendForm(httpRequest);

    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
  }

  @Test
  void givenInvalidPublicLinkTheDownloadMultipleShouldReturn404() throws Exception {
    String userId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    String fileId = "00000000-0000-0000-0000-000000000401";
    String invalidPublicId = UUID.randomUUID().toString();

    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                fileId,
                userId,
                userId,
                Constants.Db.RootId.LOCAL_ROOT,
                "file.txt",
                "",
                NodeType.TEXT,
                Constants.Db.RootId.LOCAL_ROOT,
                10L,
                "text/plain"));

    List<String> nodeIds = List.of(fileId);
    String jsonArray = objectMapper.writeValueAsString(nodeIds);
    String requestBody =
        "nodeIds="
            + URLEncoder.encode(jsonArray, StandardCharsets.UTF_8)
            + "&nodeLinkId="
            + invalidPublicId;

    List<Map.Entry<String, String>> headers =
        List.of(Map.entry("Content-Type", "application/x-www-form-urlencoded"));

    final HttpRequest httpRequest =
        HttpRequest.of("POST", "/public/download-multiple", null, headers, requestBody);

    final HttpResponse httpResponse = app.sendForm(httpRequest);

    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(404);
  }

  @Test
  void givenMissingNodeLinkIdTheDownloadMultipleShouldReturn400() throws Exception {
    List<String> nodeIds = List.of("00000000-0000-0000-0000-000000000501");
    String jsonArray = objectMapper.writeValueAsString(nodeIds);
    String requestBody = "nodeIds=" + URLEncoder.encode(jsonArray, StandardCharsets.UTF_8);

    List<Map.Entry<String, String>> headers =
        List.of(Map.entry("Content-Type", "application/x-www-form-urlencoded"));

    final HttpRequest httpRequest =
        HttpRequest.of("POST", "/public/download-multiple", null, headers, requestBody);

    final HttpResponse httpResponse = app.sendForm(httpRequest);

    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(400);
  }

  @Test
  void givenEmptyNodeListTheDownloadMultipleShouldReturn404() throws Exception {
    String publicId = UUID.randomUUID().toString();
    List<String> nodeIds = List.of();
    String jsonArray = objectMapper.writeValueAsString(nodeIds);
    String requestBody =
        "nodeIds="
            + URLEncoder.encode(jsonArray, StandardCharsets.UTF_8)
            + "&nodeLinkId="
            + publicId;

    List<Map.Entry<String, String>> headers =
        List.of(Map.entry("Content-Type", "application/x-www-form-urlencoded"));

    final HttpRequest httpRequest =
        HttpRequest.of("POST", "/public/download-multiple", null, headers, requestBody);

    final HttpResponse httpResponse = app.sendForm(httpRequest);

    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(404);
  }

  @Test
  void givenMixedNodesWithPublicLinkTheDownloadMultipleShouldReturnZipWith200() throws Exception {
    String userId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    String parentFolderId = "11111111-1111-1111-1111-111111111701";
    String subFolderId = "22222222-2222-2222-2222-222222222701";
    String fileId1 = "00000000-0000-0000-0000-000000000701";
    String fileId2 = "00000000-0000-0000-0000-000000000702";
    String linkId = UUID.randomUUID().toString();
    String publicId = UUID.randomUUID().toString();

    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                parentFolderId,
                userId,
                userId,
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
                userId,
                userId,
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
                userId,
                userId,
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
                userId,
                userId,
                subFolderId,
                "file2.txt",
                "",
                NodeType.TEXT,
                Constants.Db.RootId.LOCAL_ROOT + "," + parentFolderId + "," + subFolderId,
                20L,
                "text/plain"))
        .addLink(
            linkId, parentFolderId, publicId, Optional.empty(), Optional.empty(), Optional.empty());

    app.mocks().storagesServesBlob(fileId1, 1);
    app.mocks().storagesServesBlob(fileId2, 1);

    List<String> nodeIds = List.of(fileId1, subFolderId);
    String jsonArray = objectMapper.writeValueAsString(nodeIds);
    String requestBody =
        "nodeIds="
            + URLEncoder.encode(jsonArray, StandardCharsets.UTF_8)
            + "&nodeLinkId="
            + publicId;

    List<Map.Entry<String, String>> headers =
        List.of(Map.entry("Content-Type", "application/x-www-form-urlencoded"));

    final HttpRequest httpRequest =
        HttpRequest.of("POST", "/public/download-multiple", null, headers, requestBody);

    final HttpResponse httpResponse = app.sendForm(httpRequest);

    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
  }

  @Test
  void givenMissingAccessCodeForProtectedLinkTheDownloadMultipleShouldReturn404() throws Exception {
    String userId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    String folderId = "11111111-1111-1111-1111-111111111801";
    String fileId = "00000000-0000-0000-0000-000000000801";
    String linkId = UUID.randomUUID().toString();
    String publicId = UUID.randomUUID().toString();
    String accessCode = "required123";

    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                folderId,
                userId,
                userId,
                Constants.Db.RootId.LOCAL_ROOT,
                "protected-folder",
                "",
                NodeType.FOLDER,
                Constants.Db.RootId.LOCAL_ROOT,
                0L,
                null))
        .addNode(
            new PopulatorNode(
                fileId,
                userId,
                userId,
                folderId,
                "file.txt",
                "",
                NodeType.TEXT,
                Constants.Db.RootId.LOCAL_ROOT + "," + folderId,
                10L,
                "text/plain"))
        .addLink(
            linkId,
            folderId,
            publicId,
            Optional.empty(),
            Optional.empty(),
            Optional.of(accessCode));

    List<String> nodeIds = List.of(fileId);
    String jsonArray = objectMapper.writeValueAsString(nodeIds);
    String requestBody =
        "nodeIds="
            + URLEncoder.encode(jsonArray, StandardCharsets.UTF_8)
            + "&nodeLinkId="
            + publicId;

    List<Map.Entry<String, String>> headers =
        List.of(Map.entry("Content-Type", "application/x-www-form-urlencoded"));

    final HttpRequest httpRequest =
        HttpRequest.of("POST", "/public/download-multiple", null, headers, requestBody);

    final HttpResponse httpResponse = app.sendForm(httpRequest);

    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(404);
  }

  @Test
  void givenEmptyFolderWithPublicLinkTheDownloadMultipleShouldReturn200() throws Exception {
    String userId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    String folderId = "11111111-1111-1111-1111-111111111901";
    String linkId = UUID.randomUUID().toString();
    String publicId = UUID.randomUUID().toString();

    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                folderId,
                userId,
                userId,
                Constants.Db.RootId.LOCAL_ROOT,
                "empty-folder",
                "",
                NodeType.FOLDER,
                Constants.Db.RootId.LOCAL_ROOT,
                0L,
                null))
        .addLink(
            linkId,
            folderId,
            publicId,
            Optional.empty(),
            Optional.of("Empty folder link"),
            Optional.empty());

    List<String> nodeIds = List.of(folderId);
    String jsonArray = objectMapper.writeValueAsString(nodeIds);
    String requestBody =
        "nodeIds="
            + URLEncoder.encode(jsonArray, StandardCharsets.UTF_8)
            + "&nodeLinkId="
            + publicId;

    List<Map.Entry<String, String>> headers =
        List.of(Map.entry("Content-Type", "application/x-www-form-urlencoded"));

    final HttpRequest httpRequest =
        HttpRequest.of("POST", "/public/download-multiple", null, headers, requestBody);

    final HttpResponse httpResponse = app.sendForm(httpRequest);

    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
  }
}
