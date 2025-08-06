// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Injector;
import com.zextras.carbonio.files.Constants;
import com.zextras.carbonio.files.Simulator;
import com.zextras.carbonio.files.Simulator.SimulatorBuilder;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.api.utilities.DatabasePopulator;
import com.zextras.carbonio.files.api.utilities.entities.PopulatorNode;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.LinkRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.utilities.http.HttpRequest;
import com.zextras.carbonio.files.utilities.http.HttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockserver.model.Parameter;
import org.mockserver.verify.VerificationTimes;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public class DownloadMultipleApiIT {

  static Simulator simulator;
  static NodeRepository nodeRepository;
  static FileVersionRepository fileVersionRepository;
  static LinkRepository linkRepository;
  static ObjectMapper objectMapper = new ObjectMapper();

  @BeforeAll
  static void init() {
    simulator =
        SimulatorBuilder.aSimulator()
            .init()
            .withDatabase()
            .withServiceDiscover()
            .withUserManagement(Map.of("fake-token", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"))
            .withStorages()
            .build()
            .start();

    final Injector injector = simulator.getInjector();
    nodeRepository = injector.getInstance(NodeRepository.class);
    fileVersionRepository = injector.getInstance(FileVersionRepository.class);
    linkRepository = injector.getInstance(LinkRepository.class);
  }

  @AfterEach
  void cleanUp() {
    simulator.resetDatabase();
  }

  @AfterAll
  static void cleanUpAll() {
    simulator.stopAll();
  }

  @Test
  void givenMultipleFilesInSameFolderTheDownloadMultipleShouldReturnZipWith200() throws Exception {
    // Given
    String folderId = "11111111-1111-1111-1111-111111111101";
    String fileId1 = "00000000-0000-0000-0000-000000000101";
    String fileId2 = "00000000-0000-0000-0000-000000000102";
    String fileId3 = "00000000-0000-0000-0000-000000000103";

    DatabasePopulator.aNodePopulator(simulator.getInjector())
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
    simulator.getBlob(fileId1, 1);
    simulator.getBlob(fileId2, 1);
    simulator.getBlob(fileId3, 1);

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
    final HttpResponse httpResponse =
        TestUtils.sendFormRequest(httpRequest, simulator.getNettyChannel());

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
    simulator
        .getStoragesMock()
        .verify(
            org.mockserver.model.HttpRequest.request()
                .withMethod(HttpMethod.GET.toString())
                .withPath("/download")
                .withQueryStringParameter(Parameter.param("node", fileId1))
                .withQueryStringParameter(Parameter.param("version", "1"))
                .withQueryStringParameter(Parameter.param("type", "files")),
            VerificationTimes.once());
  }

  @Test
  void givenMixedNodesInSameFolderTheDownloadMultipleShouldReturnZipWith200() throws Exception {
    // Given
    String parentFolderId = "11111111-1111-1111-1111-111111111201";
    String subFolderId = "22222222-2222-2222-2222-222222222201";
    String fileId1 = "00000000-0000-0000-0000-000000000201";
    String fileId2 = "00000000-0000-0000-0000-000000000202";

    DatabasePopulator.aNodePopulator(simulator.getInjector())
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
    simulator.getBlob(fileId1, 1);
    simulator.getBlob(fileId2, 1);

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
    final HttpResponse httpResponse =
        TestUtils.sendFormRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
  }

  @Test
  void givenLocalRootAsNodeIdTheDownloadMultipleShouldReturnZipWith200() throws Exception {
    // Given
    String fileId1 = "00000000-0000-0000-0000-000000000301";
    String fileId2 = "00000000-0000-0000-0000-000000000302";
    String folderId = "11111111-1111-1111-1111-111111111301";

    DatabasePopulator.aNodePopulator(simulator.getInjector())
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
    simulator.getBlob(fileId1, 1);
    simulator.getBlob(fileId2, 1);

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
    final HttpResponse httpResponse =
        TestUtils.sendFormRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Assertions.assertThat(httpResponse.getHeaders())
        .anyMatch(header ->
            header.getKey().equalsIgnoreCase("content-type") &&
                header.getValue().contains("application/zip")
        );
  }

  @Test
  void givenNodesOnDifferentLevelsTheDownloadMultipleShouldReturn400() throws Exception {
    // Given
    String folderId1 = "11111111-1111-1111-1111-111111111401";
    String folderId2 = "22222222-2222-2222-2222-222222222401";
    String fileId1 = "00000000-0000-0000-0000-000000000401";
    String fileId2 = "00000000-0000-0000-0000-000000000402";

    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(
            new PopulatorNode(
                folderId1,
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                Constants.Db.RootId.LOCAL_ROOT,
                "folder1",
                "",
                NodeType.FOLDER,
                Constants.Db.RootId.LOCAL_ROOT,
                0L,
                null))
        .addNode(
            new PopulatorNode(
                folderId2,
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                Constants.Db.RootId.LOCAL_ROOT,
                "folder2",
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
                folderId1,
                "file1.txt",
                "",
                NodeType.TEXT,
                Constants.Db.RootId.LOCAL_ROOT + "," + folderId1,
                10L,
                "text/plain"))
        .addNode(
            new PopulatorNode(
                fileId2,
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                folderId2,
                "file2.txt",
                "",
                NodeType.TEXT,
                Constants.Db.RootId.LOCAL_ROOT + "," + folderId2,
                20L,
                "text/plain"));

    List<String> nodeIds = List.of(fileId1, fileId2);
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
    final HttpResponse httpResponse =
        TestUtils.sendFormRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(400);
  }

  @Test
  void givenLocalRootWithOtherNodesTheDownloadMultipleShouldReturn400() throws Exception {
    // Given
    String fileId = "00000000-0000-0000-0000-000000000501";

    DatabasePopulator.aNodePopulator(simulator.getInjector())
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
    final HttpResponse httpResponse =
        TestUtils.sendFormRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(400);
  }

  @Test
  void givenNonExistentNodeTheDownloadMultipleShouldReturn404() throws Exception {
    // Given
    String existingFileId = "00000000-0000-0000-0000-000000000601";
    String nonExistentFileId = "99999999-9999-9999-9999-999999999601";

    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(
            new PopulatorNode(
                existingFileId,
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                Constants.Db.RootId.LOCAL_ROOT,
                "file.txt",
                "",
                NodeType.TEXT,
                Constants.Db.RootId.LOCAL_ROOT,
                10L,
                "text/plain"));

    List<String> nodeIds = List.of(existingFileId, nonExistentFileId);
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
    final HttpResponse httpResponse =
        TestUtils.sendFormRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(404);
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
    final HttpResponse httpResponse =
        TestUtils.sendFormRequest(httpRequest, simulator.getNettyChannel());

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
    final HttpResponse httpResponse =
        TestUtils.sendFormRequest(httpRequest, simulator.getNettyChannel());

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
    final HttpResponse httpResponse =
        TestUtils.sendFormRequest(httpRequest, simulator.getNettyChannel());

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
    final HttpResponse httpResponse =
        TestUtils.sendFormRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(400);
  }

  @Test
  void givenUserWithoutPermissionTheDownloadMultipleShouldReturn404() throws Exception {
    // Given
    String fileId = "00000000-0000-0000-0000-000000000801";

    // Create a file owned by a different user
    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(
            new PopulatorNode(
                fileId,
                "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                Constants.Db.RootId.LOCAL_ROOT,
                "file.txt",
                "",
                NodeType.TEXT,
                Constants.Db.RootId.LOCAL_ROOT,
                10L,
                "text/plain"));

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
    final HttpResponse httpResponse =
        TestUtils.sendFormRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(404);
  }

  @Test
  void givenSharedNodeTheDownloadMultipleShouldReturnZipWith200() throws Exception {
    // Given
    String folderId = "11111111-1111-1111-1111-111111111901";
    String fileId = "00000000-0000-0000-0000-000000000901";

    // Create a folder owned by another user but shared with our user
    DatabasePopulator.aNodePopulator(simulator.getInjector())
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
    simulator.getBlob(fileId, 1);

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
    final HttpResponse httpResponse =
        TestUtils.sendFormRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
  }

  @Test
  void givenDuplicateNodeIdsTheDownloadMultipleShouldReturnZipWith200() throws Exception {
    // Given
    String fileId = "00000000-0000-0000-0000-000000001001";

    DatabasePopulator.aNodePopulator(simulator.getInjector())
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

    // Mock storages response
    simulator.getBlob(fileId, 1);

    // Include the same fileId multiple times
    List<String> nodeIds = List.of(fileId, fileId, fileId);
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
    final HttpResponse httpResponse =
        TestUtils.sendFormRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    // Verify storages was called only once despite duplicates
    simulator
        .getStoragesMock()
        .verify(
            org.mockserver.model.HttpRequest.request()
                .withMethod(HttpMethod.GET.toString())
                .withPath("/download")
                .withQueryStringParameter(Parameter.param("node", fileId))
                .withQueryStringParameter(Parameter.param("version", "1"))
                .withQueryStringParameter(Parameter.param("type", "files")),
            VerificationTimes.once());
  }
}