// SPDX-FileCopyrightText: 2023 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.api;

import com.google.inject.Injector;
import com.zextras.carbonio.files.Simulator;
import com.zextras.carbonio.files.Simulator.SimulatorBuilder;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.ShareRepository;
import com.zextras.carbonio.files.utilities.http.HttpRequest;
import com.zextras.carbonio.files.utilities.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CreatePublicLinkApiIT {

  static Simulator simulator;
  static NodeRepository nodeRepository;
  static FileVersionRepository fileVersionRepository;
  static ShareRepository shareRepository;

  @BeforeAll
  static void init() {
    simulator =
        SimulatorBuilder.aSimulator()
            .init()
            .withDatabase()
            .withServiceDiscover()
            .withUserManagement(
                Map.of(
                    "fake-token",
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                    "fake-token-account-for-sharing",
                    "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"))
            .build()
            .start();
    Injector injector = simulator.getInjector();
    nodeRepository = injector.getInstance(NodeRepository.class);
    fileVersionRepository = injector.getInstance(FileVersionRepository.class);
    shareRepository = injector.getInstance(ShareRepository.class);
  }

  @AfterEach
  void cleanUp() {
    simulator.resetDatabase();
  }

  @AfterAll
  static void cleanUpAll() {
    simulator.stopAll();
  }

  void createFile(String nodeId, String ownerId) {
    nodeRepository.createNewNode(
        nodeId, ownerId, ownerId, "LOCAL_ROOT", "fake.txt", "", NodeType.TEXT, "LOCAL_ROOT", 1L);

    fileVersionRepository.createNewFileVersion(nodeId, ownerId, 1, "text/plain", 1L, "", false);
  }

  void createShare(String nodeId, String targetUserId, SharePermission permission) {
    shareRepository.upsertShare(
        nodeId, targetUserId, ACL.decode(permission), true, false, Optional.empty());
  }

  @Test
  void givenAFileIdAndAllLinkFieldsTheCreateLinkShouldCreateANewPublicLink() {
    // Given
    createFile("00000000-0000-0000-0000-000000000000", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    String bodyPayload =
        "mutation { "
            + "createLink(node_id: \\\"00000000-0000-0000-0000-000000000000\\\", expires_at: 5, description: \\\"super-description\\\") {"
            + "id "
            + "url "
            + "expires_at "
            + "created_at "
            + "description "
            + "node { "
            + "  id "
            + "} "
            + "} "
            + "}";

    HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);

    // When
    HttpResponse httpResponse = TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Map<String, Object> createdLink =
        TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "createLink");

    Assertions.assertThat((String) createdLink.get("id")).isNotNull().hasSize(36);
    Assertions.assertThat((String) createdLink.get("url"))
        .startsWith("example.com/services/files/link/")
        .hasSize("example.com/services/files/link/".length() + 8);

    Assertions.assertThat(createdLink)
        .containsEntry("expires_at", 5)
        .containsEntry("description", "super-description");

    Assertions.assertThat((Map<String, Object>) createdLink.get("node"))
        .containsEntry("id", "00000000-0000-0000-0000-000000000000");
  }

  @Test
  void givenAFileIdAndOnlyMandatoryLinkFieldsTheCreateLinkShouldCreateANewPublicLink() {
    // Given
    createFile("00000000-0000-0000-0000-000000000000", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    String bodyPayload =
        "mutation { "
            + "createLink(node_id: \\\"00000000-0000-0000-0000-000000000000\\\") {"
            + "id "
            + "url "
            + "expires_at "
            + "created_at "
            + "description "
            + "node { "
            + "  id "
            + "} "
            + "} "
            + "}";

    HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);

    // When
    HttpResponse httpResponse = TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Map<String, Object> createdLink =
        TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "createLink");

    Assertions.assertThat((String) createdLink.get("id")).isNotNull().hasSize(36);
    Assertions.assertThat((String) createdLink.get("url"))
        .startsWith("example.com/services/files/link/")
        .hasSize("example.com/services/files/link/".length() + 8);

    Assertions.assertThat(createdLink)
        .containsEntry("expires_at", null)
        .containsEntry("description", null);

    Assertions.assertThat((Map<String, Object>) createdLink.get("node"))
        .containsEntry("id", "00000000-0000-0000-0000-000000000000");
  }

  @Test
  void givenAFileIdAndAnExpiresAtToZeroTheCreateLinkShouldCreateANewPublicLinkWithoutExpiration() {
    // Given
    createFile("00000000-0000-0000-0000-000000000000", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    String bodyPayload =
        "mutation { "
            + "createLink(node_id: \\\"00000000-0000-0000-0000-000000000000\\\", expires_at: 0) {"
            + "id "
            + "expires_at "
            + "} "
            + "}";

    HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);

    // When
    HttpResponse httpResponse = TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Map<String, Object> createdLink =
        TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "createLink");

    Assertions.assertThat((String) createdLink.get("id")).isNotNull().hasSize(36);

    Assertions.assertThat(createdLink).containsEntry("expires_at", null);
  }

  @Test
  void givenANotExistingNodeIdTheCreateLinkShouldReturn200CodeWithAnErrorMessage() {
    // Given
    String bodyPayload =
        "mutation { createLink(node_id: \\\"00000000-0000-0000-0000-000000000000\\\") {id}}";
    HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);

    // When
    HttpResponse httpResponse = TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    List<String> errorResponse = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errorResponse)
        .hasSize(1)
        .containsExactly(
            "There was a problem while executing requested operation on node: 00000000-0000-0000-0000-000000000000");
  }

  @Test
  void givenAnExistingNodeSharedToAUserWithShareRightsTheCreateLinkShouldReturnANewPublicLink() {
    // Given
    createFile("00000000-0000-0000-0000-000000000000", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    createShare(
        "00000000-0000-0000-0000-000000000000",
        "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
        SharePermission.READ_AND_SHARE);

    String bodyPayload =
        "mutation { createLink(node_id: \\\"00000000-0000-0000-0000-000000000000\\\") {id}}";
    HttpRequest httpRequest =
        HttpRequest.of(
            "POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token-account-for-sharing", bodyPayload);

    // When
    HttpResponse httpResponse = TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Map<String, Object> createdLink =
        TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "createLink");

    Assertions.assertThat((String) createdLink.get("id")).isNotNull().hasSize(36);
  }

  @Test
  void
      givenAnExistingNodeSharedToAUserWithoutShareRightsTheCreateLinkShouldReturn200CodeWithAnErrorMessage() {
    // Given
    createFile("00000000-0000-0000-0000-000000000000", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    createShare(
        "00000000-0000-0000-0000-000000000000",
        "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
        SharePermission.READ_ONLY);

    String bodyPayload =
        "mutation { createLink(node_id: \\\"00000000-0000-0000-0000-000000000000\\\") {id}}";
    HttpRequest httpRequest =
        HttpRequest.of(
            "POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token-account-for-sharing", bodyPayload);

    // When
    HttpResponse httpResponse = TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    List<String> errorResponse = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errorResponse)
        .hasSize(1)
        .containsExactly(
            "There was a problem while executing requested operation on node: 00000000-0000-0000-0000-000000000000");
  }

  @Test
  void
      givenAnExistingNodeAndAUserWithoutPermissionsTheCreateLinkShouldReturn200CodeWithAnErrorMessage() {
    // Given
    createFile("00000000-0000-0000-0000-000000000000", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    String bodyPayload =
      "mutation { createLink(node_id: \\\"00000000-0000-0000-0000-000000000000\\\") {id}}";
    HttpRequest httpRequest =
      HttpRequest.of(
        "POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token-account-for-sharing", bodyPayload);

    // When
    HttpResponse httpResponse = TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    List<String> errorResponse = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errorResponse)
      .hasSize(1)
      .containsExactly(
        "There was a problem while executing requested operation on node: 00000000-0000-0000-0000-000000000000");
  }
}
