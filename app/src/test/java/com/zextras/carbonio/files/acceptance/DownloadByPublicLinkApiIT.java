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
import org.junit.jupiter.params.provider.CsvSource;

@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
public class DownloadByPublicLinkApiIT {

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
  @CsvSource({
    "abcd1234,/public/link/download/,",
    "abcd1234abcd1234abcd1234abcd1234,/public/link/download/,",
    "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234ab,/public/link/download/,",
    "abcd1234,/link/,",
    "abcd1234abcd1234abcd1234abcd1234,/link/,",
    "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234ab,/link/,",
    "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234ab,/public/link/download/,fake-token",
    "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234ab,/link/,fake-token",
  })
  void
      givenAUserWithOrWithoutCookieAnExistingFileAndAnExistingPublicLinkAssociatedTheDownloadByPublicLinkShouldReturnTheBlob(
          String publicLinkId, String publicLinkEndpoint, String userToken) {
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
            publicLinkId,
            Optional.empty(),
            Optional.empty(),
            Optional.empty());

    app.mocks().storagesServesBlob("00000000-0000-0000-0000-000000000000", 1);

    final String publicLinkUrl = publicLinkEndpoint + publicLinkId;
    final HttpRequest httpRequest = HttpRequest.of("GET", publicLinkUrl, userToken, null);

    // When
    final HttpResponse httpResponse = app.send(httpRequest);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    app.mocks().verifyStoragesDownloaded("00000000-0000-0000-0000-000000000000", 1);
  }

  @ParameterizedTest
  @CsvSource({
    "abcd1234,/public/link/download/,",
    "abcd1234abcd1234abcd1234abcd1234,/public/link/download/,",
    "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234ab,/public/link/download/,",
    "abcd1234,/link/,",
    "abcd1234abcd1234abcd1234abcd1234,/link/,",
    "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234ab,/link/,",
    "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234ab,/public/link/download/,fake-token",
    "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234ab,/link/,fake-token",
  })
  void
      givenAUserWithOrWithoutCookieAnExistingFileAndAnExistingPublicLinkAssociatedWithAccessCodeTheDownloadByPublicLinkShouldRedirect(
          String publicLinkId, String publicLinkEndpoint, String userToken) {
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
            publicLinkId,
            Optional.empty(),
            Optional.empty(),
            Optional.of("test"));

    app.mocks().storagesServesBlob("00000000-0000-0000-0000-000000000000", 1);

    final String publicLinkUrl = publicLinkEndpoint + publicLinkId;
    final HttpRequest httpRequest = HttpRequest.of("GET", publicLinkUrl, userToken, null);

    // When
    final HttpResponse httpResponse = app.send(httpRequest);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(307);
    Assertions.assertThat(httpResponse.getHeaders())
        .extracting(header -> header.getKey().equals("location") ? header.getValue() : null)
        .contains("/files/public/link/access/" + publicLinkId);
  }

  @Test
  void givenAnExistingFileAndAnExpiredLinkTheDownloadByPublicLinkShouldReturnA404StatusCode() {
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
                1L,
                "text/plain"))
        .addLink(
            "94103c01-e701-4f3d-9dc9-54b79064ad76",
            "00000000-0000-0000-0000-000000000000",
            "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234ab",
            Optional.of(1L),
            Optional.empty(),
            Optional.empty());

    final String publicDownloadUrl = "/public/link/download/abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234ab";
    final HttpRequest httpRequest = HttpRequest.of("GET", publicDownloadUrl, null, null);

    // When
    final HttpResponse httpResponse = app.send(httpRequest);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(404);
    Assertions.assertThat(httpResponse.getBodyPayload()).isEqualTo("404 Not Found");

    app.mocks().verifyStoragesNeverDownloaded();
  }

  @Test
  void givenANotExistingLinkTheDownloadByPublicLinkShouldReturnA404StatusCode() {
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
            "000000",
            Optional.empty(),
            Optional.empty(),
            Optional.empty());

    final HttpRequest httpRequest =
        HttpRequest.of("GET", "/public/link/download/1234abcd", null, null);

    // When
    final HttpResponse httpResponse = app.send(httpRequest);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(404);
    Assertions.assertThat(httpResponse.getBodyPayload()).isEqualTo("404 Not Found");

    app.mocks().verifyStoragesNeverDownloaded();
  }

  @Test
  void givenANotExistingNodeTheDownloadByPublicLinkShouldReturnA404StatusCode() {
    // Given
    final HttpRequest httpRequest =
        HttpRequest.of("GET", "/public/link/download/1234abcd", null, null);

    // When
    final HttpResponse httpResponse = app.send(httpRequest);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(404);
    Assertions.assertThat(httpResponse.getBodyPayload()).isEqualTo("404 Not Found");

    app.mocks().verifyStoragesNeverDownloaded();
  }

  @Test
  void
      givenAnExistingFileAndAValidPublicLinkAssociatedAndAConnectionProblemToStoragesTheTheDownloadByPublicLinkShouldReturnA500StatusCode() {
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

    final HttpRequest httpRequest =
        HttpRequest.of(
            "GET",
            "/public/link/download/abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234ab",
            null,
            null);

    // When
    final HttpResponse httpResponse = app.send(httpRequest);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(500);
    Assertions.assertThat(httpResponse.getBodyPayload()).isEqualTo("500 Internal Server Error");
  }

  @ParameterizedTest
  @CsvSource({
    "abcd1234,/public/link/download/,",
    "abcd1234abcd1234abcd1234abcd1234,/public/link/download/,",
    "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234ab,/public/link/download/,",
    "abcd1234,/link/,",
    "abcd1234abcd1234abcd1234abcd1234,/link/,",
    "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234ab,/link/,",
    "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234ab,/public/link/download/,fake-token",
    "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234ab,/link/,fake-token",
  })
  void
      givenAUserWithOrWithoutCookieAnExistingTrashedFileAndAnExistingPublicLinkAssociatedTheDownloadByPublicLinkShouldReturn404(
          String publicLinkId, String publicLinkEndpoint, String userToken) {
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
            publicLinkId,
            Optional.empty(),
            Optional.empty(),
            Optional.empty())
        .addNodeToTrash("00000000-0000-0000-0000-000000000000", "LOCAL_ROOT");

    app.mocks().storagesServesBlob("00000000-0000-0000-0000-000000000000", 1);

    final String publicLinkUrl = publicLinkEndpoint + publicLinkId;
    final HttpRequest httpRequest = HttpRequest.of("GET", publicLinkUrl, userToken, null);

    // When
    final HttpResponse httpResponse = app.send(httpRequest);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(404);
  }
}
