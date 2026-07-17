// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
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
import com.zextras.carbonio.files.api.utilities.entities.SimplePopulatorTextFile;
import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import com.zextras.carbonio.files.utilities.http.HttpRequest;
import com.zextras.carbonio.files.utilities.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class DeleteSharesApiIT {

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
                    "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                    "fake-token-account-for-sharing-2",
                    "cccccccc-cccc-cccc-cccc-cccccccccccc"))
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

  void createShare(String nodeId, String targetUserId, SharePermission permission) {
    app.backdoor().populator().addShare(nodeId, targetUserId, permission);
  }

  @Test
  void givenExistingSharesTheDeleteSharesShouldDeleteAllAndReturnTargetIds() {
    // Given
    createFile("00000000-0000-0000-0000-000000000000", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    createShare(
        "00000000-0000-0000-0000-000000000000",
        "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
        SharePermission.READ_ONLY);
    createShare(
        "00000000-0000-0000-0000-000000000000",
        "cccccccc-cccc-cccc-cccc-cccccccccccc",
        SharePermission.READ_ONLY);

    final String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("deleteShares")
            .withString("node_id", "00000000-0000-0000-0000-000000000000")
            .withListOfStrings(
                "share_target_ids",
                new String[]{
                    "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                    "cccccccc-cccc-cccc-cccc-cccccccccccc"
                })
            .withWantedResultFormat("")
            .build();

    final HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);

    // When
    final HttpResponse httpResponse = app.send(httpRequest);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    final List<String> deletedIds =
        (List<String>) TestUtils.jsonResponseToValue(httpResponse.getBodyPayload(), "deleteShares")
            .orElse(List.of());

    Assertions.assertThat(deletedIds)
        .hasSize(2)
        .containsExactly(
            "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
            "cccccccc-cccc-cccc-cccc-cccccccccccc");

    Assertions.assertThat(
        app.backdoor().shareExists(
            "00000000-0000-0000-0000-000000000000",
            "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
    ).isFalse();

    Assertions.assertThat(
        app.backdoor().shareExists(
            "00000000-0000-0000-0000-000000000000",
            "cccccccc-cccc-cccc-cccc-cccccccccccc")
    ).isFalse();
  }

  @Test
  void givenOneNonExistingShareTheDeleteSharesShouldReturnPartialSuccessWithErrors() {
    // Given
    createFile("00000000-0000-0000-0000-000000000000", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    createShare(
        "00000000-0000-0000-0000-000000000000",
        "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
        SharePermission.READ_ONLY);

    final String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("deleteShares")
            .withString("node_id", "00000000-0000-0000-0000-000000000000")
            .withListOfStrings(
                "share_target_ids",
                new String[]{
                    "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                    "cccccccc-cccc-cccc-cccc-cccccccccccc"
                })
            .withWantedResultFormat("")
            .build();

    final HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);

    // When
    final HttpResponse httpResponse = app.send(httpRequest);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    final List<String> deletedIds =
        (List<String>) TestUtils.jsonResponseToValue(httpResponse.getBodyPayload(), "deleteShares")
            .orElse(List.of());

    Assertions.assertThat(deletedIds)
        .hasSize(1)
        .containsExactly("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    final List<String> errors =
        TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly(
            "Could not find share for node: 00000000-0000-0000-0000-000000000000"
                + " and user cccccccc-cccc-cccc-cccc-cccccccccccc");
  }

  @Test
  void givenATargetUserTheDeleteSharesShouldAllowSelfDeletion() {
    // Given
    createFile("00000000-0000-0000-0000-000000000000", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    createShare(
        "00000000-0000-0000-0000-000000000000",
        "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
        SharePermission.READ_ONLY);

    final String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("deleteShares")
            .withString("node_id", "00000000-0000-0000-0000-000000000000")
            .withListOfStrings(
                "share_target_ids",
                new String[]{"bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"})
            .withWantedResultFormat("")
            .build();

    final HttpRequest httpRequest =
        HttpRequest.of(
            "POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token-account-for-sharing", bodyPayload);

    // When
    final HttpResponse httpResponse = app.send(httpRequest);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    final List<String> deletedIds =
        (List<String>) TestUtils.jsonResponseToValue(httpResponse.getBodyPayload(), "deleteShares")
            .orElse(List.of());

    Assertions.assertThat(deletedIds)
        .hasSize(1)
        .containsExactly("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    Assertions.assertThat(
        app.backdoor().shareExists(
            "00000000-0000-0000-0000-000000000000",
            "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
    ).isFalse();
  }

  @Test
  void givenAUserWithoutPermissionsTheDeleteSharesShouldReturnErrors() {
    // Given
    createFile("00000000-0000-0000-0000-000000000000", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    createShare(
        "00000000-0000-0000-0000-000000000000",
        "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
        SharePermission.READ_ONLY);

    final String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("deleteShares")
            .withString("node_id", "00000000-0000-0000-0000-000000000000")
            .withListOfStrings(
                "share_target_ids",
                new String[]{"bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"})
            .withWantedResultFormat("")
            .build();

    // cccccccc is not the target and has no share permission
    final HttpRequest httpRequest =
        HttpRequest.of(
            "POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token-account-for-sharing-2", bodyPayload);

    // When
    final HttpResponse httpResponse = app.send(httpRequest);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    final List<String> deletedIds =
        (List<String>) TestUtils.jsonResponseToValue(httpResponse.getBodyPayload(), "deleteShares")
            .orElse(List.of());

    Assertions.assertThat(deletedIds).isEmpty();

    final List<String> errors =
        TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors).hasSize(1);
  }

  @Test
  void givenOwnerAsTargetTheDeleteSharesShouldReturnErrorForOwner() {
    // Given
    createFile("00000000-0000-0000-0000-000000000000", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    createShare(
        "00000000-0000-0000-0000-000000000000",
        "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
        SharePermission.READ_ONLY);

    final String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("deleteShares")
            .withString("node_id", "00000000-0000-0000-0000-000000000000")
            .withListOfStrings(
                "share_target_ids",
                new String[]{
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                    "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
                })
            .withWantedResultFormat("")
            .build();

    final HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);

    // When
    final HttpResponse httpResponse = app.send(httpRequest);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    final List<String> deletedIds =
        (List<String>) TestUtils.jsonResponseToValue(httpResponse.getBodyPayload(), "deleteShares")
            .orElse(List.of());

    Assertions.assertThat(deletedIds)
        .hasSize(1)
        .containsExactly("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    final List<String> errors =
        TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly(
            "Could not find share for node: 00000000-0000-0000-0000-000000000000"
                + " and user aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  }
}
