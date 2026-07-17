// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
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
import com.zextras.carbonio.files.utilities.http.HttpRequest;
import com.zextras.carbonio.files.utilities.http.HttpResponse;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class FlagNodesApiIT {

  static FilesTestApp app;

  @BeforeAll
  static void init() {
    app =
        QuarkusFilesTestAppBuilder.aFilesTestApp()
            .withDatabase()
            .withServiceDiscover()
            .withUserManagement( // create a fake token to use in cookie for auth
                Map.of(
                    "fake-token",
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"))
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
  void givenANodeFlagNodesShouldFlagIt() {
    // Given
    app.backdoor()
        .populator()
        .addNode(
            new SimplePopulatorTextFile(
                "00000000-0000-0000-0000-000000000000", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));

    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("flagNodes")
            .withListOfStrings("node_ids", new String[]{"00000000-0000-0000-0000-000000000000"})
            .withBoolean("flag", true)
            .withWantedResultFormat("")
            .build();

    final HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);

    // When
    final HttpResponse httpResponse = app.send(httpRequest);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    final Map<String, Object> page =
        TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "flagNodes");

    final List<String> nodes = (List<String>) page.get("data");

    Assertions.assertThat(nodes).hasSize(1);
    Assertions.assertThat(nodes.get(0)).isEqualTo("00000000-0000-0000-0000-000000000000");
  }

  @Test
  void givenANotExistingNodeFlagNodesShouldReturn200WithAnErrorMessage() {
    // Given
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("flagNodes")
            .withListOfStrings("node_ids", new String[]{"00000000-0000-0000-0000-000000000001"})
            .withBoolean("flag", true)
            .withWantedResultFormat("")
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
        "There was a problem while executing requested operation on node: 00000000-0000-0000-0000-000000000001");
  }

  @Test
  void givenANodeAndAUserWithoutPermissionsFlagNodesShouldReturn200WithAnErrorMessage() {
    // Given
    app.backdoor()
        .populator()
        .addNode(
            new SimplePopulatorTextFile(
                "00000000-0000-0000-0000-000000000002", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaab"));

    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("flagNodes")
            .withListOfStrings("node_ids", new String[]{"00000000-0000-0000-0000-000000000002"})
            .withBoolean("flag", true)
            .withWantedResultFormat("")
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
        "There was a problem while executing requested operation on node: 00000000-0000-0000-0000-000000000002");
  }

  @Test
  void givenTwoNodesWithOneNotExistingNodeFlagNodesShouldReturn200WithAnErrorMessage() {
    // Given
    app.backdoor()
        .populator()
        .addNode(
            new SimplePopulatorTextFile(
                "00000000-0000-0000-0000-000000000003", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));

    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("flagNodes")
            .withListOfStrings("node_ids", new String[]{"00000000-0000-0000-0000-000000000003", "00000000-0000-0000-0000-000000000004"})
            .withBoolean("flag", true)
            .withWantedResultFormat("")
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
        "There was a problem while executing requested operation on node: 00000000-0000-0000-0000-000000000004");
  }
}
