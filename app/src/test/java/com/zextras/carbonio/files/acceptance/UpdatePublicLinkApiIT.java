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
import java.util.Optional;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class UpdatePublicLinkApiIT {

  static FilesTestApp app;

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
  void
      givenAnExistingFileAnExistingLinkAndAllUpdatedFieldsTheUpdateLinkShouldReturnTheUpdatedLink() {
    // Given
    createFile("00000000-0000-0000-0000-000000000000", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    app.backdoor()
        .populator()
        .addLink(
            "cc83bd73-8c5c-4e7c-8c34-3e3919ff6c9b",
            "00000000-0000-0000-0000-000000000000",
            "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234ab",
            Optional.of(5L),
            Optional.of("super-description"),
            Optional.of("fake-access-code"));

    final String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("updateLink")
            .withString("link_id", "cc83bd73-8c5c-4e7c-8c34-3e3919ff6c9b")
            .withInteger("expires_at", 10)
            .withString("description", "another-description")
            .withString("access_code", "another-fake-access-code")
            .withWantedResultFormat("{ id url expires_at created_at description access_code node { id } }")
            .build();

    final HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);

    // When
    final HttpResponse httpResponse = app.send(httpRequest);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    final Map<String, Object> updatedLink =
        TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "updateLink");

    Assertions.assertThat((String) updatedLink.get("url"))
        .isEqualTo(
            "example.com/services/files/public/link/download/abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234ab");

    Assertions.assertThat(updatedLink)
        .containsEntry("id", "cc83bd73-8c5c-4e7c-8c34-3e3919ff6c9b")
        .containsEntry("expires_at", 10)
        .containsEntry("description", "another-description")
        .containsEntry("access_code", "another-fake-access-code");

    Assertions.assertThat((Map<String, Object>) updatedLink.get("node"))
        .containsEntry("id", "00000000-0000-0000-0000-000000000000");
  }

  @Test
  void
      givenAnExistingFileAnExistingLinkAndEmptyAccessCodeTheUpdateLinkShouldReturnTheUpdatedLink() {
    // Given
    createFile("00000000-0000-0000-0000-000000000000", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    app.backdoor()
        .populator()
        .addLink(
            "cc83bd73-8c5c-4e7c-8c34-3e3919ff6c9b",
            "00000000-0000-0000-0000-000000000000",
            "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234ab",
            Optional.of(5L),
            Optional.of("super-description"),
            Optional.of("fake-access-code"));

    final String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("updateLink")
            .withString("link_id", "cc83bd73-8c5c-4e7c-8c34-3e3919ff6c9b")
            .withInteger("expires_at", 10)
            .withString("description", "another-description")
            .withString("access_code", "")
            .withWantedResultFormat("{ id url expires_at created_at description access_code node { id } }")
            .build();

    final HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);

    // When
    final HttpResponse httpResponse = app.send(httpRequest);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    final Map<String, Object> updatedLink =
        TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "updateLink");

    Assertions.assertThat((String) updatedLink.get("url"))
        .isEqualTo(
            "example.com/services/files/public/link/download/abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234ab");

    Assertions.assertThat(updatedLink)
        .containsEntry("id", "cc83bd73-8c5c-4e7c-8c34-3e3919ff6c9b")
        .containsEntry("expires_at", 10)
        .containsEntry("description", "another-description")
        .containsEntry("access_code", null);

    Assertions.assertThat((Map<String, Object>) updatedLink.get("node"))
        .containsEntry("id", "00000000-0000-0000-0000-000000000000");
  }

  @Test
  void
      givenAnExistingFileAnExistingLinkAndNoFieldsToUpdateTheUpdateLinkShouldReturnTheUntouchedLink() {
    // Given
    createFile("00000000-0000-0000-0000-000000000000", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    app.backdoor()
        .populator()
        .addLink(
            "cc83bd73-8c5c-4e7c-8c34-3e3919ff6c9b",
            "00000000-0000-0000-0000-000000000000",
            "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234ab",
            Optional.of(5L),
            Optional.of("super-description"),
            Optional.empty());

    final String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("updateLink")
            .withString("link_id", "cc83bd73-8c5c-4e7c-8c34-3e3919ff6c9b")
            .withWantedResultFormat("{ id url expires_at created_at description access_code node { id } }")
            .build();

    final HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);

    // When
    final HttpResponse httpResponse = app.send(httpRequest);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    final Map<String, Object> updatedLink =
        TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "updateLink");

    Assertions.assertThat((String) updatedLink.get("id")).isNotNull().hasSize(36);
    Assertions.assertThat((String) updatedLink.get("url"))
        .isEqualTo(
            "example.com/services/files/public/link/download/abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234ab");

    Assertions.assertThat(updatedLink)
        .containsEntry("id", "cc83bd73-8c5c-4e7c-8c34-3e3919ff6c9b")
        .containsEntry("expires_at", 5)
        .containsEntry("description", "super-description")
        .containsEntry("access_code", null);

    Assertions.assertThat((Map<String, Object>) updatedLink.get("node"))
        .containsEntry("id", "00000000-0000-0000-0000-000000000000");
  }

  @Test
  void
      givenAnExistingFolderAnExistingLinkAndAllUpdatedFieldsTheUpdateLinkShouldReturnTheUpdatedLink() {
    // Given
    createFolder("00000000-0000-0000-0000-000000000000", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    app.backdoor()
        .populator()
        .addLink(
            "cc83bd73-8c5c-4e7c-8c34-3e3919ff6c9b",
            "00000000-0000-0000-0000-000000000000",
            "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234ab",
            Optional.empty(),
            Optional.empty(),
            Optional.empty());

    final String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("updateLink")
            .withString("link_id", "cc83bd73-8c5c-4e7c-8c34-3e3919ff6c9b")
            .withInteger("expires_at", 10)
            .withString("description", "another-description")
            .withWantedResultFormat("{ id url expires_at created_at description access_code node { id } }")
            .build();

    final HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);

    // When
    final HttpResponse httpResponse = app.send(httpRequest);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    final Map<String, Object> updatedLink =
        TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "updateLink");

    Assertions.assertThat((String) updatedLink.get("url"))
        .isEqualTo("example.com/files/public/link/access/abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234ab");

    Assertions.assertThat(updatedLink)
        .containsEntry("id", "cc83bd73-8c5c-4e7c-8c34-3e3919ff6c9b")
        .containsEntry("expires_at", 10)
        .containsEntry("description", "another-description")
        .containsEntry("access_code", null);

    Assertions.assertThat((Map<String, Object>) updatedLink.get("node"))
        .containsEntry("id", "00000000-0000-0000-0000-000000000000");
  }

  @Test
  void givenANotExistingLinkTheUpdateLinkShouldReturn200CodeWithAnErrorMessage() {
    // Given
    final String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("updateLink")
            .withString("link_id", "cc83bd73-8c5c-4e7c-8c34-3e3919ff6c9b")
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

    app.backdoor()
        .populator()
        .addLink(
            "cc83bd73-8c5c-4e7c-8c34-3e3919ff6c9b",
            "00000000-0000-0000-0000-000000000000",
            "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234ab",
            Optional.of(5L),
            Optional.of("super-description"),
            Optional.empty());

    final String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("updateLink")
            .withString("link_id", "cc83bd73-8c5c-4e7c-8c34-3e3919ff6c9b")
            .withString("description", "")
            .withWantedResultFormat("{ id description }")
            .build();

    final HttpRequest httpRequest =
        HttpRequest.of(
            "POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token-account-for-sharing", bodyPayload);

    // When
    final HttpResponse httpResponse = app.send(httpRequest);

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

    app.backdoor()
        .populator()
        .addLink(
            "cc83bd73-8c5c-4e7c-8c34-3e3919ff6c9b",
            "00000000-0000-0000-0000-000000000000",
            "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234ab",
            Optional.of(5L),
            Optional.empty(),
            Optional.empty());

    final String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("updateLink")
            .withString("link_id", "cc83bd73-8c5c-4e7c-8c34-3e3919ff6c9b")
            .withInteger("expires_at", 0)
            .withWantedResultFormat("{ id expires_at }")
            .build();

    final HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);

    // When
    final HttpResponse httpResponse = app.send(httpRequest);

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

    app.backdoor()
        .populator()
        .addLink(
            "cc83bd73-8c5c-4e7c-8c34-3e3919ff6c9b",
            "00000000-0000-0000-0000-000000000000",
            "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234ab",
            Optional.of(5L),
            Optional.of("super-description"),
            Optional.empty());

    final String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("updateLink")
            .withString("link_id", "cc83bd73-8c5c-4e7c-8c34-3e3919ff6c9b")
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
        .containsExactly("Could not find link with id cc83bd73-8c5c-4e7c-8c34-3e3919ff6c9b");
  }

  @Test
  void
      givenAnExistingNodeAndAUserWithoutPermissionsAndAnExistingLinkTheUpdateLinkShouldReturn200CodeWithAnErrorMessage() {
    // Given
    createFile("00000000-0000-0000-0000-000000000000", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    app.backdoor()
        .populator()
        .addLink(
            "cc83bd73-8c5c-4e7c-8c34-3e3919ff6c9b",
            "00000000-0000-0000-0000-000000000000",
            "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234ab",
            Optional.of(5L),
            Optional.of("super-description"),
            Optional.empty());

    final String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("updateLink")
            .withString("link_id", "cc83bd73-8c5c-4e7c-8c34-3e3919ff6c9b")
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
        .containsExactly("Could not find link with id cc83bd73-8c5c-4e7c-8c34-3e3919ff6c9b");
  }
}
