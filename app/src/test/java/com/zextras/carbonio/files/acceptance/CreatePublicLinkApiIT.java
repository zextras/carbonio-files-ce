// SPDX-FileCopyrightText: 2023 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.acceptance;

import com.zextras.carbonio.files.FilesStackTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.acceptance.seam.FilesTestApp;
import com.zextras.carbonio.files.acceptance.seam.impl.QuarkusFilesTestAppBuilder;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.api.utilities.entities.SimplePopulatorFolder;
import com.zextras.carbonio.files.api.utilities.entities.SimplePopulatorTextFile;
import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import com.zextras.carbonio.files.utilities.http.HttpRequest;
import com.zextras.carbonio.files.utilities.http.HttpResponse;
import java.util.List;
import java.util.Map;

import java.util.stream.Stream;
import org.apache.commons.lang3.RandomStringUtils;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class CreatePublicLinkApiIT {

  static FilesTestApp app;

  // It is needed since strings cannot be generated as constants in the @ValueSource definition
  static Stream<Arguments> invalidAccessCodesProvider() {
    return Stream.of(
      Arguments.of(RandomStringUtils.secure().nextAlphanumeric(9)),
      Arguments.of(RandomStringUtils.secure().nextAlphanumeric(255))
    );
  }

  @BeforeAll
  static void init() {
    app =
        QuarkusFilesTestAppBuilder.aFilesTestApp()
            .withDatabase()
            .withServiceDiscover()
            .withUserManagement(
                Map.of(
                    "fake-token",
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                    "fake-token-account-for-sharing",
                    "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"))
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

  void createFile(String nodeId, String ownerId) {
    app.backdoor().populator().addNode(new SimplePopulatorTextFile(nodeId, ownerId));
  }

  void createFolder(String nodeId, String ownerId) {
    app.backdoor().populator().addNode(new SimplePopulatorFolder(nodeId, ownerId));
  }

  void createShare(String nodeId, String targetUserId, SharePermission permission) {
    app.backdoor().populator().addShare(nodeId, targetUserId, permission);
  }

  @Test
  void givenAFileIdAndAllLinkFieldsTheCreateLinkShouldCreateANewPublicLink() {
    // Given
    createFile("00000000-0000-0000-0000-000000000000", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    final String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("createLink")
            .withString("node_id", "00000000-0000-0000-0000-000000000000")
            .withInteger("expires_at", 5)
            .withString("description", "super-description")
            .withString("access_code", "fake-access-code")
            .withWantedResultFormat("{ id url expires_at created_at description access_code node { id } }")
            .build();

    final HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);

    // When
    final HttpResponse httpResponse = app.send(httpRequest);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    final Map<String, Object> createdLink =
        TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "createLink");

    Assertions.assertThat((String) createdLink.get("id")).isNotNull().hasSize(36);
    Assertions.assertThat((String) createdLink.get("url"))
        .startsWith("example.com/services/files/public/link/download/")
        .hasSize("example.com/services/files/public/link/download/".length() + 50);

    Assertions.assertThat(createdLink)
        .containsEntry("expires_at", 5)
        .containsEntry("description", "super-description")
        .containsEntry("access_code", "fake-access-code");

    Assertions.assertThat((Map<String, Object>) createdLink.get("node"))
        .containsEntry("id", "00000000-0000-0000-0000-000000000000");
  }

  @Test
  void givenAFileIdAndOnlyMandatoryLinkFieldsTheCreateLinkShouldCreateANewPublicLink() {
    // Given
    createFile("00000000-0000-0000-0000-000000000000", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    final String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("createLink")
            .withString("node_id", "00000000-0000-0000-0000-000000000000")
            .withWantedResultFormat("{ id url expires_at created_at description access_code node { id } }")
            .build();

    final HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);

    // When
    final HttpResponse httpResponse = app.send(httpRequest);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    final Map<String, Object> createdLink =
        TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "createLink");

    Assertions.assertThat((String) createdLink.get("id")).isNotNull().hasSize(36);
    Assertions.assertThat((String) createdLink.get("url"))
        .startsWith("example.com/services/files/public/link/download/")
        .hasSize("example.com/services/files/public/link/download/".length() + 50);

    Assertions.assertThat(createdLink)
        .containsEntry("expires_at", null)
        .containsEntry("description", null)
        .containsEntry("access_code", null);

    Assertions.assertThat((Map<String, Object>) createdLink.get("node"))
        .containsEntry("id", "00000000-0000-0000-0000-000000000000");
  }

  @Test
  void givenAFolderIdAndOnlyMandatoryLinkFieldsTheCreateLinkShouldCreateANewPublicLink() {
    // Given
    createFolder("00000000-0000-0000-0000-000000000000", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    final String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("createLink")
            .withString("node_id", "00000000-0000-0000-0000-000000000000")
            .withWantedResultFormat("{ id url expires_at created_at description access_code node { id } }")
            .build();

    final HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);

    // When
    final HttpResponse httpResponse = app.send(httpRequest);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    final Map<String, Object> createdLink =
        TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "createLink");

    Assertions.assertThat((String) createdLink.get("id")).isNotNull().hasSize(36);
    Assertions.assertThat((String) createdLink.get("url"))
        .startsWith("example.com/files/public/link/access/")
        .hasSize("example.com/files/public/link/access/".length() + 50);

    Assertions.assertThat(createdLink)
        .containsEntry("expires_at", null)
        .containsEntry("description", null)
        .containsEntry("access_code", null);

    Assertions.assertThat((Map<String, Object>) createdLink.get("node"))
        .containsEntry("id", "00000000-0000-0000-0000-000000000000");
  }

  @Test
  void givenANodeIdAndAnExpiresAtToZeroTheCreateLinkShouldCreateANewPublicLinkWithoutExpiration() {
    // Given
    createFile("00000000-0000-0000-0000-000000000000", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    final String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("createLink")
            .withString("node_id", "00000000-0000-0000-0000-000000000000")
            .withInteger("expires_at", 0)
            .withWantedResultFormat("{ id expires_at }")
            .build();

    final HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);

    // When
    final HttpResponse httpResponse = app.send(httpRequest);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    final Map<String, Object> createdLink =
        TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "createLink");

    Assertions.assertThat((String) createdLink.get("id")).isNotNull().hasSize(36);

    Assertions.assertThat(createdLink).containsEntry("expires_at", null);
  }

  @ParameterizedTest
  @MethodSource("invalidAccessCodesProvider")
  void givenAFileIdAndAnInvalidAccessCodeLengthTheCreateLinkShouldReturn200CodeWithAnErrorMessage(
    String invalidAccessCode
  ) {
    // Given
    createFile("00000000-0000-0000-0000-000000000000", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    final String bodyPayload =
      GraphqlCommandBuilder.aMutationBuilder("createLink")
        .withString("node_id", "00000000-0000-0000-0000-000000000000")
        .withInteger("expires_at", 5)
        .withString("description", "super-description")
        .withString("access_code", invalidAccessCode)
        .withWantedResultFormat("{ id url expires_at created_at description access_code node { id } }")
        .build();

    final HttpRequest httpRequest =
      HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);

    // When
    final HttpResponse httpResponse = app.send(httpRequest);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    final List<String> errorResponse =
      TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errorResponse)
      .hasSize(1)
      .containsExactly(
        "Invalid link access code. The access code must be between 10 and 255 characters long");
  }

  @Test
  void givenANotExistingNodeIdTheCreateLinkShouldReturn200CodeWithAnErrorMessage() {
    // Given
    final String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("createLink")
            .withString("node_id", "00000000-0000-0000-0000-000000000000")
            .withWantedResultFormat("{ id }")
            .build();

    final HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);

    // When
    final HttpResponse httpResponse = app.send(httpRequest);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    final List<String> errorResponse =
        TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
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

    final String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("createLink")
            .withString("node_id", "00000000-0000-0000-0000-000000000000")
            .withWantedResultFormat("{ id }")
            .build();
    final HttpRequest httpRequest =
        HttpRequest.of(
            "POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token-account-for-sharing", bodyPayload);

    // When
    final HttpResponse httpResponse = app.send(httpRequest);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    final Map<String, Object> createdLink =
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

    final String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("createLink")
            .withString("node_id", "00000000-0000-0000-0000-000000000000")
            .withWantedResultFormat("{ id }")
            .build();
    final HttpRequest httpRequest =
        HttpRequest.of(
            "POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token-account-for-sharing", bodyPayload);

    // When
    final HttpResponse httpResponse = app.send(httpRequest);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    final List<String> errorResponse =
        TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
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

    final String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("createLink")
            .withString("node_id", "00000000-0000-0000-0000-000000000000")
            .withWantedResultFormat("{ id }")
            .build();
    final HttpRequest httpRequest =
        HttpRequest.of(
            "POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token-account-for-sharing", bodyPayload);

    // When
    final HttpResponse httpResponse = app.send(httpRequest);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    final List<String> errorResponse =
        TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errorResponse)
        .hasSize(1)
        .containsExactly(
            "There was a problem while executing requested operation on node: 00000000-0000-0000-0000-000000000000");
  }

  @Test
  void givenAFileIdWithMoreThanFiftyLinksAndOnlyMandatoryLinkFieldsTheCreateLinkShouldReturn200CodeWithAnErrorMessage() {
    // Given
    createFile("00000000-0000-0000-0000-000000000000", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    app.backdoor().populator().addLinks("00000000-0000-0000-0000-000000000000", 50);

    final String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("createLink")
            .withString("node_id", "00000000-0000-0000-0000-000000000000")
            .withWantedResultFormat("{ id url expires_at created_at description access_code node { id } }")
            .build();

    final HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);

    // When
    final HttpResponse httpResponse = app.send(httpRequest);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    final List<String> errorResponse =
        TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errorResponse)
        .hasSize(1)
        .containsExactly(
            "The limit for links has been reached for this node: 00000000-0000-0000-0000-000000000000");
  }
}
