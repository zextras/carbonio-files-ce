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
import com.zextras.carbonio.files.dal.repositories.interfaces.LinkRepository;
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

class GetPublicLinksApiIT {

  static Simulator simulator;
  static NodeRepository nodeRepository;
  static FileVersionRepository fileVersionRepository;
  static LinkRepository linkRepository;
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

    final Injector injector = simulator.getInjector();
    nodeRepository = injector.getInstance(NodeRepository.class);
    fileVersionRepository = injector.getInstance(FileVersionRepository.class);
    linkRepository = injector.getInstance(LinkRepository.class);
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
  void
      givenAnExistingNodeWithTwoExistingLinksTheGetLinksShouldReturnAListOfAssociatedLinksOrderedByCreationDescending() {
    // Given
    createFile("00000000-0000-0000-0000-000000000000", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    linkRepository.createLink(
        "06e0f2ae-b128-4d25-9b3b-df84eb7948a9",
        "00000000-0000-0000-0000-000000000000",
        "abcd1234abcd1234abcd1234abcd1234",
        Optional.of(5L),
        Optional.of("super-description"));

    linkRepository.createLink(
        "0c04783b-bdfb-446f-870c-625f5ae02a0a",
        "00000000-0000-0000-0000-000000000000",
        "00001234abcd1234abcd1234abcd1234",
        Optional.empty(),
        Optional.empty());

    final String bodyPayload =
        "query { "
            + "getLinks(node_id: \\\"00000000-0000-0000-0000-000000000000\\\") { "
            + "id "
            + "url "
            + "expires_at "
            + "created_at "
            + "description "
            + "node { id } "
            + "} "
            + "} ";

    final HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);

    // When
    final HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    final List<Map<String, Object>> publicLinks =
        TestUtils.jsonResponseToList(httpResponse.getBodyPayload(), "getLinks");

    Assertions.assertThat(publicLinks).hasSize(2);

    Assertions.assertThat(publicLinks.get(0))
        .containsEntry("id", "0c04783b-bdfb-446f-870c-625f5ae02a0a")
        .containsEntry("url", "example.com/services/files/link/00001234abcd1234abcd1234abcd1234")
        .containsEntry("expires_at", null)
        .containsEntry("description", null);
    Assertions.assertThat((Map<String, Object>) publicLinks.get(0).get("node"))
        .containsEntry("id", "00000000-0000-0000-0000-000000000000");

    Assertions.assertThat(publicLinks.get(1))
        .containsEntry("id", "06e0f2ae-b128-4d25-9b3b-df84eb7948a9")
        .containsEntry("url", "example.com/services/files/link/abcd1234abcd1234abcd1234abcd1234")
        .containsEntry("expires_at", 5)
        .containsEntry("description", "super-description");
    Assertions.assertThat((Map<String, Object>) publicLinks.get(0).get("node"))
        .containsEntry("id", "00000000-0000-0000-0000-000000000000");
  }

  @Test
  void givenAnExistingNodeWithoutLinksTheGetLinksShouldReturnAnEmptyList() {
    // Given
    createFile("00000000-0000-0000-0000-000000000000", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    final String bodyPayload =
        "query { getLinks(node_id: \\\"00000000-0000-0000-0000-000000000000\\\") { id }}";

    final HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);

    // When
    final HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToList(httpResponse.getBodyPayload(), "getLinks"))
        .isEmpty();
  }

  // TODO it should return an error message
  @Test
  void givenANotExistingNodeTheGetLinksShouldReturn200StatusCodeAndNull() {
    // Given
    final String bodyPayload =
        "query { getLinks(node_id: \\\"00000000-0000-0000-0000-000000000000\\\") { id }}";

    final HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);

    // When
    final HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToList(httpResponse.getBodyPayload(), "getLinks"))
        .first()
        .isNull();
  }

  // TODO it should return an error message
  @Test
  void
      givenAnExistingNodeALinkAssociatedAndAUserWithoutPermissionsTheGetLinksShouldReturn200StatusCodeAndNull() {
    // Given
    createFile("00000000-0000-0000-0000-000000000000", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    linkRepository.createLink(
        "0c04783b-bdfb-446f-870c-625f5ae02a0a",
        "00000000-0000-0000-0000-000000000000",
        "0000aaaa",
        Optional.empty(),
        Optional.empty());

    final String bodyPayload =
        "query { getLinks(node_id: \\\"00000000-0000-0000-0000-000000000000\\\") { id }}";

    final HttpRequest httpRequest =
        HttpRequest.of(
            "POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token-account-for-sharing", bodyPayload);

    // When
    final HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToList(httpResponse.getBodyPayload(), "getLinks"))
        .first()
        .isNull();
  }

  // TODO it should return an error message
  @Test
  void
      givenAnExistingNodeSharedToAUserWithoutShareRightsAndAnExistingLinkTheGetLinksShouldReturn200CodeAndNull() {
    // Given
    createFile("00000000-0000-0000-0000-000000000000", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    createShare(
        "00000000-0000-0000-0000-000000000000",
        "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
        SharePermission.READ_ONLY);

    linkRepository.createLink(
        "0c04783b-bdfb-446f-870c-625f5ae02a0a",
        "00000000-0000-0000-0000-000000000000",
        "abcd1234abcd1234abcd1234abcd1234",
        Optional.empty(),
        Optional.empty());

    final String bodyPayload =
        "query { getLinks(node_id: \\\"00000000-0000-0000-0000-000000000000\\\") { id }}";

    final HttpRequest httpRequest =
        HttpRequest.of(
            "POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token-account-for-sharing", bodyPayload);

    // When
    final HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToList(httpResponse.getBodyPayload(), "getLinks"))
        .first()
        .isNull();
  }

  @Test
  void
      givenAnExistingNodeSharedToAUserWithShareRightsAndAnExistingLinkTheGetLinksShouldReturnAListOfAssociatedLinks() {
    // Given
    createFile("00000000-0000-0000-0000-000000000000", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    createShare(
        "00000000-0000-0000-0000-000000000000",
        "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
        SharePermission.READ_AND_SHARE);

    linkRepository.createLink(
        "0c04783b-bdfb-446f-870c-625f5ae02a0a",
        "00000000-0000-0000-0000-000000000000",
        "abcd1234abcd1234abcd1234abcd1234",
        Optional.empty(),
        Optional.empty());

    final String bodyPayload =
        "query { getLinks(node_id: \\\"00000000-0000-0000-0000-000000000000\\\") { id }}";

    final HttpRequest httpRequest =
        HttpRequest.of(
            "POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token-account-for-sharing", bodyPayload);

    // When
    final HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    final List<Map<String, Object>> publicLinks =
        TestUtils.jsonResponseToList(httpResponse.getBodyPayload(), "getLinks");

    Assertions.assertThat(publicLinks).hasSize(1);
    Assertions.assertThat(publicLinks.get(0))
        .containsEntry("id", "0c04783b-bdfb-446f-870c-625f5ae02a0a");
  }

  @Test
  void givenAnExistingNodeAndAnAssociatedLegacyPublicLinkWithAn8CharsPublicIdentifierTheGetLinksShouldReturnItCorrectly() {
    // Given
    createFile("00000000-0000-0000-0000-000000000000", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    createShare(
      "00000000-0000-0000-0000-000000000000",
      "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
      SharePermission.READ_ONLY);

    linkRepository.createLink(
      "0c04783b-bdfb-446f-870c-625f5ae02a0a",
      "00000000-0000-0000-0000-000000000000",
      "abcd1234",
      Optional.empty(),
      Optional.empty());

    final String bodyPayload =
      "query { getLinks(node_id: \\\"00000000-0000-0000-0000-000000000000\\\") { id url }}";

    final HttpRequest httpRequest =
      HttpRequest.of(
        "POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);

    // When
    final HttpResponse httpResponse =
      TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    final List<Map<String, Object>> publicLinks =
      TestUtils.jsonResponseToList(httpResponse.getBodyPayload(), "getLinks");

    Assertions.assertThat(publicLinks).hasSize(1);
    Assertions.assertThat(publicLinks.get(0))
      .containsEntry("id", "0c04783b-bdfb-446f-870c-625f5ae02a0a")
      .containsEntry("url", "example.com/services/files/link/abcd1234");
  }
}
