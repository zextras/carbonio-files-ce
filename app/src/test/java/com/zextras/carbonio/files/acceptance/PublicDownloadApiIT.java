// SPDX-FileCopyrightText: 2023 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.acceptance;

import com.zextras.carbonio.files.FilesStackTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

import com.zextras.carbonio.files.acceptance.seam.FilesTestApp;
import com.zextras.carbonio.files.acceptance.seam.impl.QuarkusFilesTestAppBuilder;
import com.zextras.carbonio.files.api.utilities.entities.PopulatorNode;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.utilities.http.HttpRequest;
import com.zextras.carbonio.files.utilities.http.HttpResponse;
import java.util.Map;
import java.util.Optional;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
public class PublicDownloadApiIT {

  static FilesTestApp app;

  @BeforeAll
  static void init() {
    app =
        QuarkusFilesTestAppBuilder.aFilesTestApp()
            .withDatabase()
            .withServiceDiscover()
            .withUserManagement(Map.of("fake-token", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"))
            .withStorages()
            .build();
  }

  @AfterEach
  void cleanUp() {
    app.backdoor().resetDatabase();
    app.mocks().reset();
  }

  @AfterAll
  static void cleanUpAll() {
    app.close();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "fake-token",
      })
  void
      givenAUserWithOrWithoutCookieAnExistingFileAndAValidPublicLinkAssociatedThePublicDownloadByNodeIdShouldReturnTheBlob(
          String userToken) {
    // Given
    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                "00000000-0000-0000-0000-000000000000",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "LOCAL_ROOT",
                "test.txt",
                "",
                NodeType.TEXT,
                "LOCAL_ROOT",
                10L,
                "text/plain"))
        .addLink(
            "94103c01-e701-4f3d-9dc9-54b79064ad76",
            "00000000-0000-0000-0000-000000000000",
            "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234ab",
            Optional.empty(),
            Optional.empty(),
            Optional.empty());

    app.mocks().storagesServesBlob("00000000-0000-0000-0000-000000000000", 1);

    final String publicDownloadUrl = "/public/download/00000000-0000-0000-0000-000000000000?node_link_id=abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234ab";
    final HttpRequest httpRequest = HttpRequest.of("GET", publicDownloadUrl, userToken, null);

    // When
    final HttpResponse httpResponse = app.send(httpRequest);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    app.mocks().verifyStoragesDownloaded("00000000-0000-0000-0000-000000000000", 1);
  }

  @Test
  void givenAnExistingFileAndAnExpiredLinkThePublicDownloadByNodeIdShouldReturnA404StatusCode() {
    // Given
    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                "00000000-0000-0000-0000-000000000000",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "LOCAL_ROOT",
                "file.txt",
                "",
                NodeType.TEXT,
                "LOCAL_ROOT",
                1L,
                "text/plain"))
        .addLink(
            "94103c01-e701-4f3d-9dc9-54b79064ad76",
            "00000000-0000-0000-0000-000000000000",
            "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234ab",
            Optional.of(1L),
            Optional.empty(),
            Optional.empty());

    final String publicDownloadUrl = "/public/download/00000000-0000-0000-0000-000000000000?node_link_id=abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234ab";
    final HttpRequest httpRequest = HttpRequest.of("GET", publicDownloadUrl, null, null);

    // When
    final HttpResponse httpResponse = app.send(httpRequest);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(404);
    Assertions.assertThat(httpResponse.getBodyPayload()).isEqualTo("404 Not Found");

    app.mocks().verifyStoragesNeverDownloaded();
  }

  @Test
  void givenAnExistingFileAndANotExistingLinkThePublicDownloadByNodeIdShouldReturnA404StatusCode() {
    // Given
    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                "00000000-0000-0000-0000-000000000000",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "LOCAL_ROOT",
                "file.txt",
                "",
                NodeType.TEXT,
                "LOCAL_ROOT",
                1L,
                "text/plain"));

    final String publicDownloadUrl = "/public/download/00000000-0000-0000-0000-000000000000?node_link_id=abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234ab";
    final HttpRequest httpRequest = HttpRequest.of("GET", publicDownloadUrl, null, null);

    // When
    final HttpResponse httpResponse = app.send(httpRequest);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(404);
    Assertions.assertThat(httpResponse.getBodyPayload()).isEqualTo("404 Not Found");

    app.mocks().verifyStoragesNeverDownloaded();
  }

  @Test
  void givenANotExistingNodeThePublicDownloadByNodeIdShouldReturnA404StatusCode() {
    final String publicDownloadUrl = "/public/download/00000000-0000-0000-0000-000000000000?node_link_id=abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234ab";
    final HttpRequest httpRequest = HttpRequest.of("GET", publicDownloadUrl, null, null);

    // When
    final HttpResponse httpResponse = app.send(httpRequest);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(404);
    Assertions.assertThat(httpResponse.getBodyPayload()).isEqualTo("404 Not Found");

    app.mocks().verifyStoragesNeverDownloaded();
  }

  @Test
  void
      givenAnExistingFileAndAValidPublicLinkAssociatedAndAConnectionProblemToStoragesTheThePublicDownloadByNodeIdShouldReturnA500StatusCode() {
    // Given
    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                "00000000-0000-0000-0000-000000000000",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "LOCAL_ROOT",
                "test.txt",
                "",
                NodeType.TEXT,
                "LOCAL_ROOT",
                10L,
                "text/plain"))
        .addLink(
            "94103c01-e701-4f3d-9dc9-54b79064ad76",
            "00000000-0000-0000-0000-000000000000",
            "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234ab",
            Optional.empty(),
            Optional.empty(),
            Optional.empty());

    app.mocks().storagesDownloadConnectionDrops();

    final String publicDownloadUrl = "/public/download/00000000-0000-0000-0000-000000000000?node_link_id=abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234ab";
    final HttpRequest httpRequest = HttpRequest.of("GET", publicDownloadUrl, null, null);

    // When
    final HttpResponse httpResponse = app.send(httpRequest);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(500);
    Assertions.assertThat(httpResponse.getBodyPayload()).isEqualTo("500 Internal Server Error");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "fake-token",
      })
  void
      givenAUserWithOrWithoutCookieAnExistingFileAndAValidPublicLinkAssociatedButNotPassedInUrlThePublicDownloadByNodeIdShouldReturnA404StatusCode(
          String userToken) {
    // Given
    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                "00000000-0000-0000-0000-000000000000",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "LOCAL_ROOT",
                "test.txt",
                "",
                NodeType.TEXT,
                "LOCAL_ROOT",
                10L,
                "text/plain"))
        .addLink(
            "94103c01-e701-4f3d-9dc9-54b79064ad76",
            "00000000-0000-0000-0000-000000000000",
            "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234ab",
            Optional.empty(),
            Optional.empty(),
            Optional.empty());

    app.mocks().storagesServesBlob("00000000-0000-0000-0000-000000000000", 1);

    final String publicDownloadUrl = "/public/download/00000000-0000-0000-0000-000000000000";
    final HttpRequest httpRequest = HttpRequest.of("GET", publicDownloadUrl, userToken, null);

    // When
    final HttpResponse httpResponse = app.send(httpRequest);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(404);
  }

  @Test
  void givenAnExistingFileWithAccessCodeAndAValidLinkThePublicDownloadByNodeIdWithoutAccessCodeShouldReturnA404StatusCode() {
    // Given
    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                "00000000-0000-0000-0000-000000000000",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "LOCAL_ROOT",
                "file.txt",
                "",
                NodeType.TEXT,
                "LOCAL_ROOT",
                1L,
                "text/plain"))
        .addLink(
            "94103c01-e701-4f3d-9dc9-54b79064ad76",
            "00000000-0000-0000-0000-000000000000",
            "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234ab",
            Optional.empty(),
            Optional.empty(),
            Optional.of("accesscode"));

    final String publicDownloadUrl = "/public/download/00000000-0000-0000-0000-000000000000?node_link_id=abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234ab";
    final HttpRequest httpRequest = HttpRequest.of("GET", publicDownloadUrl, null, null);

    // When
    final HttpResponse httpResponse = app.send(httpRequest);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(404);
  }

  @Test
  void givenAnExistingFileWithAccessCodeAndAValidLinkThePublicDownloadByNodeIdWithAccessCodeShouldReturnTheBlob() {
    // Given
    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                "00000000-0000-0000-0000-000000000000",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "LOCAL_ROOT",
                "file.txt",
                "",
                NodeType.TEXT,
                "LOCAL_ROOT",
                1L,
                "text/plain"))
        .addLink(
            "94103c01-e701-4f3d-9dc9-54b79064ad76",
            "00000000-0000-0000-0000-000000000000",
            "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234ab",
            Optional.empty(),
            Optional.empty(),
            Optional.of("accesscode"));

    app.mocks().storagesServesBlob("00000000-0000-0000-0000-000000000000", 1);

    final String publicDownloadUrl = "/public/download/00000000-0000-0000-0000-000000000000?node_link_id=abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234ab&access_code=accesscode";
    final HttpRequest httpRequest = HttpRequest.of("GET", publicDownloadUrl, null, null);

    // When
    final HttpResponse httpResponse = app.send(httpRequest);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
  }
}
