// SPDX-FileCopyrightText: 2023 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.api;

import com.google.inject.Injector;
import com.zextras.carbonio.files.Simulator;
import com.zextras.carbonio.files.Simulator.SimulatorBuilder;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
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

class UpdatePublicLinkApiIT {

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

  void createFolder(String nodeId, String ownerId) {
    nodeRepository.createNewNode(
        nodeId, ownerId, ownerId, "LOCAL_ROOT", "folder", "", NodeType.FOLDER, "LOCAL_ROOT", 0L);
  }

  void createShare(String nodeId, String targetUserId, SharePermission permission) {
    shareRepository.upsertShare(
        nodeId, targetUserId, ACL.decode(permission), true, false, Optional.empty());
  }

  @Test
  void
      givenAnExistingFileAnExistingLinkAndAllUpdatedFieldsTheUpdateLinkShouldReturnTheUpdatedLink() {
    // Given
    createFile("00000000-0000-0000-0000-000000000000", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    linkRepository.createLink(
        "cc83bd73-8c5c-4e7c-8c34-3e3919ff6c9b",
        "00000000-0000-0000-0000-000000000000",
        "abcd1234abcd1234abcd1234abcd1234",
        Optional.of(5L),
        Optional.of("super-description"));

    final String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("updateLink")
            .withString("link_id", "cc83bd73-8c5c-4e7c-8c34-3e3919ff6c9b")
            .withInteger("expires_at", 10)
            .withString("description", "another-description")
            .build("{ id url expires_at created_at description node { id } }");

    final HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);

    // When
    final HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    final Map<String, Object> updatedLink =
        TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "updateLink");

    Assertions.assertThat((String) updatedLink.get("url"))
        .isEqualTo(
            "example.com/services/files/public/link/download/abcd1234abcd1234abcd1234abcd1234");

    Assertions.assertThat(updatedLink)
        .containsEntry("id", "cc83bd73-8c5c-4e7c-8c34-3e3919ff6c9b")
        .containsEntry("expires_at", 10)
        .containsEntry("description", "another-description");

    Assertions.assertThat((Map<String, Object>) updatedLink.get("node"))
        .containsEntry("id", "00000000-0000-0000-0000-000000000000");
  }

  @Test
  void
      givenAnExistingFileAnExistingLinkAndNoFieldsToUpdateTheUpdateLinkShouldReturnTheUntouchedLink() {
    // Given
    createFile("00000000-0000-0000-0000-000000000000", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    linkRepository.createLink(
        "cc83bd73-8c5c-4e7c-8c34-3e3919ff6c9b",
        "00000000-0000-0000-0000-000000000000",
        "abcd1234abcd1234abcd1234abcd1234",
        Optional.of(5L),
        Optional.of("super-description"));

    final String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("updateLink")
            .withString("link_id", "cc83bd73-8c5c-4e7c-8c34-3e3919ff6c9b")
            .build("{ id url expires_at created_at description node { id } }");

    final HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);

    // When
    final HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    final Map<String, Object> updatedLink =
        TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "updateLink");

    Assertions.assertThat((String) updatedLink.get("id")).isNotNull().hasSize(36);
    Assertions.assertThat((String) updatedLink.get("url"))
        .isEqualTo(
            "example.com/services/files/public/link/download/abcd1234abcd1234abcd1234abcd1234");

    Assertions.assertThat(updatedLink)
        .containsEntry("id", "cc83bd73-8c5c-4e7c-8c34-3e3919ff6c9b")
        .containsEntry("expires_at", 5)
        .containsEntry("description", "super-description");

    Assertions.assertThat((Map<String, Object>) updatedLink.get("node"))
        .containsEntry("id", "00000000-0000-0000-0000-000000000000");
  }

  @Test
  void
      givenAnExistingFolderAnExistingLinkAndAllUpdatedFieldsTheUpdateLinkShouldReturnTheUpdatedLink() {
    // Given
    createFolder("00000000-0000-0000-0000-000000000000", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    linkRepository.createLink(
        "cc83bd73-8c5c-4e7c-8c34-3e3919ff6c9b",
        "00000000-0000-0000-0000-000000000000",
        "abcd1234abcd1234abcd1234abcd1234",
        Optional.empty(),
        Optional.empty());

    final String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("updateLink")
            .withString("link_id", "cc83bd73-8c5c-4e7c-8c34-3e3919ff6c9b")
            .withInteger("expires_at", 10)
            .withString("description", "another-description")
            .build("{ id url expires_at created_at description node { id } }");

    final HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);

    // When
    final HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    final Map<String, Object> updatedLink =
        TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "updateLink");

    Assertions.assertThat((String) updatedLink.get("url"))
        .isEqualTo("example.com/files/public/link/access/abcd1234abcd1234abcd1234abcd1234");

    Assertions.assertThat(updatedLink)
        .containsEntry("id", "cc83bd73-8c5c-4e7c-8c34-3e3919ff6c9b")
        .containsEntry("expires_at", 10)
        .containsEntry("description", "another-description");

    Assertions.assertThat((Map<String, Object>) updatedLink.get("node"))
        .containsEntry("id", "00000000-0000-0000-0000-000000000000");
  }

  @Test
  void givenANotExistingLinkTheUpdateLinkShouldReturn200CodeWithAnErrorMessage() {
    // Given
    final String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("updateLink")
            .withString("link_id", "cc83bd73-8c5c-4e7c-8c34-3e3919ff6c9b")
            .build("{ id }");
    final HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);

    // When
    final HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    final List<String> errorResponse =
        TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errorResponse)
        .hasSize(1)
        .containsExactly("Could not find link with id cc83bd73-8c5c-4e7c-8c34-3e3919ff6c9b");
  }

  @Test
  void
      givenAnExistingNodeSharedToAUserWithShareRightsAndAnExistingLinkTheUpdateLinkShouldReturnAnUpdatedLink() {
    // Given
    createFile("00000000-0000-0000-0000-000000000000", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    createShare(
        "00000000-0000-0000-0000-000000000000",
        "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
        SharePermission.READ_AND_SHARE);

    linkRepository.createLink(
        "cc83bd73-8c5c-4e7c-8c34-3e3919ff6c9b",
        "00000000-0000-0000-0000-000000000000",
        "abcd1234abcd1234abcd1234abcd1234",
        Optional.of(5L),
        Optional.of("super-description"));

    final String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("updateLink")
            .withString("link_id", "cc83bd73-8c5c-4e7c-8c34-3e3919ff6c9b")
            .withString("description", "")
            .build("{ id description }");

    final HttpRequest httpRequest =
        HttpRequest.of(
            "POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token-account-for-sharing", bodyPayload);

    // When
    final HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    final Map<String, Object> updatedLink =
        TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "updateLink");

    Assertions.assertThat(updatedLink)
        .containsEntry("id", "cc83bd73-8c5c-4e7c-8c34-3e3919ff6c9b")
        .containsEntry("description", "");
  }

  @Test
  void
      givenAnExistingNodeAnExistingLinkWithExpirationAndAnExpiresAtToZeroTheUpdateLinkShouldReturnAnUpdatedLinkWithoutExpiration() {
    // Given
    createFolder("00000000-0000-0000-0000-000000000000", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    linkRepository.createLink(
        "cc83bd73-8c5c-4e7c-8c34-3e3919ff6c9b",
        "00000000-0000-0000-0000-000000000000",
        "abcd1234abcd1234abcd1234abcd1234",
        Optional.of(5L),
        Optional.empty());

    final String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("updateLink")
            .withString("link_id", "cc83bd73-8c5c-4e7c-8c34-3e3919ff6c9b")
            .withInteger("expires_at", 0)
            .build("{ id expires_at }");

    final HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);

    // When
    final HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    final Map<String, Object> updatedLink =
        TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "updateLink");

    Assertions.assertThat(updatedLink)
        .containsEntry("id", "cc83bd73-8c5c-4e7c-8c34-3e3919ff6c9b")
        .containsEntry("expires_at", null);
  }

  @Test
  void
      givenAnExistingNodeSharedToAUserWithoutShareRightsAndAnExistingLinkTheUpdateLinkShouldReturn200CodeWithAnErrorMessage() {
    // Given
    createFile("00000000-0000-0000-0000-000000000000", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    createShare(
        "00000000-0000-0000-0000-000000000000",
        "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
        SharePermission.READ_ONLY);

    linkRepository.createLink(
        "cc83bd73-8c5c-4e7c-8c34-3e3919ff6c9b",
        "00000000-0000-0000-0000-000000000000",
        "abcd1234abcd1234abcd1234abcd1234",
        Optional.of(5L),
        Optional.of("super-description"));

    final String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("updateLink")
            .withString("link_id", "cc83bd73-8c5c-4e7c-8c34-3e3919ff6c9b")
            .build("{ id }");

    final HttpRequest httpRequest =
        HttpRequest.of(
            "POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token-account-for-sharing", bodyPayload);

    // When
    final HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    final List<String> errorResponse =
        TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errorResponse)
        .hasSize(1)
        .containsExactly("Could not find link with id cc83bd73-8c5c-4e7c-8c34-3e3919ff6c9b");
  }

  @Test
  void
      givenAnExistingNodeAndAUserWithoutPermissionsAndAnExistingLinkTheUpdateLinkShouldReturn200CodeWithAnErrorMessage() {
    // Given
    createFile("00000000-0000-0000-0000-000000000000", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    linkRepository.createLink(
        "cc83bd73-8c5c-4e7c-8c34-3e3919ff6c9b",
        "00000000-0000-0000-0000-000000000000",
        "abcd1234abcd1234abcd1234abcd1234",
        Optional.of(5L),
        Optional.of("super-description"));

    final String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("updateLink")
            .withString("link_id", "cc83bd73-8c5c-4e7c-8c34-3e3919ff6c9b")
            .build("{ id }");

    final HttpRequest httpRequest =
        HttpRequest.of(
            "POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token-account-for-sharing", bodyPayload);

    // When
    final HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    final List<String> errorResponse =
        TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errorResponse)
        .hasSize(1)
        .containsExactly("Could not find link with id cc83bd73-8c5c-4e7c-8c34-3e3919ff6c9b");
  }
}
