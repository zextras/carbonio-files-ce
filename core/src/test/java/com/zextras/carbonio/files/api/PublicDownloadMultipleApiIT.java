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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class PublicDownloadMultipleApiIT {

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
            .withUserManagement(Map.of())
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
  void givenMultipleFilesWithPublicLinkTheDownloadMultipleShouldReturnZipWith200() throws Exception {
    // Given
    String userId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    String folderId = "11111111-1111-1111-1111-111111111101";
    String fileId1 = "00000000-0000-0000-0000-000000000101";
    String fileId2 = "00000000-0000-0000-0000-000000000102";
    String fileId3 = "00000000-0000-0000-0000-000000000103";
    String linkId = UUID.randomUUID().toString();
    String publicId = UUID.randomUUID().toString();

    DatabasePopulator.aNodePopulator(simulator.getInjector())
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
        .addLink(linkId, folderId, publicId, Optional.empty(), Optional.of("Public folder link"), Optional.empty());

    // Mock storages responses for each file
    simulator.getBlob(fileId1, 1);
    simulator.getBlob(fileId2, 1);
    simulator.getBlob(fileId3, 1);

    List<String> nodeIds = List.of(fileId1, fileId2, fileId3);
    String jsonArray = objectMapper.writeValueAsString(nodeIds);
    String requestBody = "nodeIds=" + URLEncoder.encode(jsonArray, StandardCharsets.UTF_8)
        + "&nodeLinkId=" + publicId;

    List<Map.Entry<String, String>> headers = List.of(
        Map.entry("Content-Type", "application/x-www-form-urlencoded")
    );

    final HttpRequest httpRequest = HttpRequest.of(
        "POST",
        "/public/download-multiple",
        null,
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
  void givenPublicLinkWithAccessCodeTheDownloadMultipleShouldReturnZipWith200() throws Exception {
    // Given
    String userId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    String folderId = "11111111-1111-1111-1111-111111111201";
    String fileId1 = "00000000-0000-0000-0000-000000000201";
    String fileId2 = "00000000-0000-0000-0000-000000000202";
    String linkId = UUID.randomUUID().toString();
    String publicId = UUID.randomUUID().toString();
    String accessCode = "secret123";

    DatabasePopulator.aNodePopulator(simulator.getInjector())
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
        .addLink(linkId, folderId, publicId, Optional.empty(), Optional.of("Protected link"), Optional.of(accessCode));

    // Mock storages responses
    simulator.getBlob(fileId1, 1);
    simulator.getBlob(fileId2, 1);

    List<String> nodeIds = List.of(fileId1, fileId2);
    String jsonArray = objectMapper.writeValueAsString(nodeIds);
    String requestBody = "nodeIds=" + URLEncoder.encode(jsonArray, StandardCharsets.UTF_8)
        + "&nodeLinkId=" + publicId
        + "&accessCode=" + accessCode;

    List<Map.Entry<String, String>> headers = List.of(
        Map.entry("Content-Type", "application/x-www-form-urlencoded")
    );

    final HttpRequest httpRequest = HttpRequest.of(
        "POST",
        "/public/download-multiple",
        null,
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
  void givenValidPublicLinkTheCheckDownloadMultipleShouldReturn204() throws Exception {
    // Given
    String userId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    String folderId = "11111111-1111-1111-1111-111111111301";
    String fileId1 = "00000000-0000-0000-0000-000000000301";
    String fileId2 = "00000000-0000-0000-0000-000000000302";
    String linkId = UUID.randomUUID().toString();
    String publicId = UUID.randomUUID().toString();

    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(
            new PopulatorNode(
                folderId,
                userId,
                userId,
                Constants.Db.RootId.LOCAL_ROOT,
                "folder",
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
        .addLink(linkId, folderId, publicId, Optional.empty(), Optional.empty(), Optional.empty());

    Map<String, Object> requestBody = new HashMap<>();
    requestBody.put("nodeIds", List.of(fileId1, fileId2));
    requestBody.put("nodeLinkId", publicId);  // Use camelCase, not snake_case

    String jsonBody = objectMapper.writeValueAsString(requestBody);

    List<Map.Entry<String, String>> headers = List.of(
        Map.entry("Content-Type", "application/json")
    );

    final HttpRequest httpRequest = HttpRequest.of(
        "POST",
        "/public/download-multiple/check",
        null,
        headers,
        jsonBody
    );

    // When
    final HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(204);
  }

  @Test
  void givenInvalidPublicLinkTheDownloadMultipleShouldReturn404() throws Exception {
    // Given
    String userId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    String fileId = "00000000-0000-0000-0000-000000000401";
    String invalidPublicId = UUID.randomUUID().toString();

    DatabasePopulator.aNodePopulator(simulator.getInjector())
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
    String requestBody = "nodeIds=" + URLEncoder.encode(jsonArray, StandardCharsets.UTF_8)
        + "&nodeLinkId=" + invalidPublicId;

    List<Map.Entry<String, String>> headers = List.of(
        Map.entry("Content-Type", "application/x-www-form-urlencoded")
    );

    final HttpRequest httpRequest = HttpRequest.of(
        "POST",
        "/public/download-multiple",
        null,
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
  void givenMissingNodeLinkIdTheDownloadMultipleShouldReturn400() throws Exception {
    // Given
    List<String> nodeIds = List.of("00000000-0000-0000-0000-000000000501");
    String jsonArray = objectMapper.writeValueAsString(nodeIds);
    String requestBody = "nodeIds=" + URLEncoder.encode(jsonArray, StandardCharsets.UTF_8);

    List<Map.Entry<String, String>> headers = List.of(
        Map.entry("Content-Type", "application/x-www-form-urlencoded")
    );

    final HttpRequest httpRequest = HttpRequest.of(
        "POST",
        "/public/download-multiple",
        null,
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
  void givenEmptyNodeListTheDownloadMultipleShouldReturn400() throws Exception {
    // Given
    String publicId = UUID.randomUUID().toString();
    List<String> nodeIds = List.of();
    String jsonArray = objectMapper.writeValueAsString(nodeIds);
    String requestBody = "nodeIds=" + URLEncoder.encode(jsonArray, StandardCharsets.UTF_8)
        + "&nodeLinkId=" + publicId;

    List<Map.Entry<String, String>> headers = List.of(
        Map.entry("Content-Type", "application/x-www-form-urlencoded")
    );

    final HttpRequest httpRequest = HttpRequest.of(
        "POST",
        "/public/download-multiple",
        null,
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
  void givenWrongAccessCodeTheCheckDownloadMultipleShouldReturn404() throws Exception {
    // Given
    String userId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    String folderId = "11111111-1111-1111-1111-111111111601";
    String fileId = "00000000-0000-0000-0000-000000000601";
    String linkId = UUID.randomUUID().toString();
    String publicId = UUID.randomUUID().toString();
    String correctAccessCode = "correct123";
    String wrongAccessCode = "wrong456";

    DatabasePopulator.aNodePopulator(simulator.getInjector())
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
        .addLink(linkId, folderId, publicId, Optional.empty(), Optional.empty(), Optional.of(correctAccessCode));

    Map<String, Object> requestBody = new HashMap<>();
    requestBody.put("nodeIds", List.of(fileId));
    requestBody.put("nodeLinkId", publicId);  // Use camelCase
    requestBody.put("accessCode", wrongAccessCode);

    String jsonBody = objectMapper.writeValueAsString(requestBody);

    List<Map.Entry<String, String>> headers = List.of(
        Map.entry("Content-Type", "application/json")
    );

    final HttpRequest httpRequest = HttpRequest.of(
        "POST",
        "/public/download-multiple/check",
        null,
        headers,
        jsonBody
    );

    // When
    final HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(404);
  }

  @Test
  void givenMixedNodesWithPublicLinkTheDownloadMultipleShouldReturnZipWith200() throws Exception {
    // Given
    String userId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    String parentFolderId = "11111111-1111-1111-1111-111111111701";
    String subFolderId = "22222222-2222-2222-2222-222222222701";
    String fileId1 = "00000000-0000-0000-0000-000000000701";
    String fileId2 = "00000000-0000-0000-0000-000000000702";
    String linkId = UUID.randomUUID().toString();
    String publicId = UUID.randomUUID().toString();

    DatabasePopulator.aNodePopulator(simulator.getInjector())
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
        .addLink(linkId, parentFolderId, publicId, Optional.empty(), Optional.empty(), Optional.empty());

    // Mock storages responses
    simulator.getBlob(fileId1, 1);
    simulator.getBlob(fileId2, 1);

    List<String> nodeIds = List.of(fileId1, subFolderId);
    String jsonArray = objectMapper.writeValueAsString(nodeIds);
    String requestBody = "nodeIds=" + URLEncoder.encode(jsonArray, StandardCharsets.UTF_8)
        + "&nodeLinkId=" + publicId;

    List<Map.Entry<String, String>> headers = List.of(
        Map.entry("Content-Type", "application/x-www-form-urlencoded")
    );

    final HttpRequest httpRequest = HttpRequest.of(
        "POST",
        "/public/download-multiple",
        null,
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
  void givenInvalidJsonFormatInCheckEndpointShouldReturn400() {
    // Given
    String invalidJsonBody = "{ invalid json }";

    List<Map.Entry<String, String>> headers = List.of(
        Map.entry("Content-Type", "application/json")
    );

    final HttpRequest httpRequest = HttpRequest.of(
        "POST",
        "/public/download-multiple/check",
        null,
        headers,
        invalidJsonBody
    );

    // When
    final HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(400);
  }

  @Test
  void givenMissingAccessCodeForProtectedLinkTheDownloadMultipleShouldReturn404() throws Exception {
    // Given
    String userId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    String folderId = "11111111-1111-1111-1111-111111111801";
    String fileId = "00000000-0000-0000-0000-000000000801";
    String linkId = UUID.randomUUID().toString();
    String publicId = UUID.randomUUID().toString();
    String accessCode = "required123";

    DatabasePopulator.aNodePopulator(simulator.getInjector())
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
        .addLink(linkId, folderId, publicId, Optional.empty(), Optional.empty(), Optional.of(accessCode));

    List<String> nodeIds = List.of(fileId);
    String jsonArray = objectMapper.writeValueAsString(nodeIds);
    String requestBody = "nodeIds=" + URLEncoder.encode(jsonArray, StandardCharsets.UTF_8)
        + "&nodeLinkId=" + publicId;

    List<Map.Entry<String, String>> headers = List.of(
        Map.entry("Content-Type", "application/x-www-form-urlencoded")
    );

    final HttpRequest httpRequest = HttpRequest.of(
        "POST",
        "/public/download-multiple",
        null,
        headers,
        requestBody
    );

    // When
    final HttpResponse httpResponse =
        TestUtils.sendFormRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(404);
  }
}