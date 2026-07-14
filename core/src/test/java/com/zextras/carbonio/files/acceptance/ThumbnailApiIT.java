// SPDX-FileCopyrightText: 2025 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.acceptance;

import com.zextras.carbonio.files.acceptance.seam.FilesTestApp;
import com.zextras.carbonio.files.acceptance.seam.impl.GuiceNettyFilesTestAppBuilder;
import com.zextras.carbonio.files.api.utilities.entities.PopulatorNode;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.utilities.http.HttpRequest;
import com.zextras.carbonio.files.utilities.http.HttpResponse;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

class ThumbnailApiIT {

  static FilesTestApp app;

  private static final String OWNER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

  @BeforeAll
  static void init() {
    app =
        GuiceNettyFilesTestAppBuilder.aFilesTestApp()
            .withDatabase()
            .withServiceDiscover()
            .withPreview()
            .withUserManagement(
                Map.of("fake-token", OWNER_ID))
            .build();
  }

  @AfterEach
  void cleanUp() {
    app.backdoor().resetDatabase();
    app.backdoor().clearFileVersionCache();
  }

  @AfterAll
  static void cleanUpAll() {
    app.close();
  }

  @Test
  void givenAnExistingDocumentTheGetThumbnailApiShouldGetAndReturnTheThumbnailWithLangTag() {
    // Given
    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                "00000000-0000-0000-0000-000000000000",
                OWNER_ID,
                OWNER_ID,
                "LOCAL_ROOT",
                "FILE.XLS",
                "",
                NodeType.SPREADSHEET,
                "LOCAL_ROOT",
                7L,
                "application/vnd.ms-excel")
        );

    String callThumbnailExpectationId = app.mocks().previewServes(
        "/preview/document/00000000-0000-0000-0000-000000000000/1/5x5/thumbnail/",
        OWNER_ID,
        "0".getBytes(),
        "image/jpeg"
    );

    final HttpRequest httpRequest =
        HttpRequest.of("GET",
            "/preview/document/00000000-0000-0000-0000-000000000000/5x5/thumbnail",
            "ZM_AUTH_TOKEN=fake-token",
            null);

    // When
    final HttpResponse httpResponse = app.send(httpRequest);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Assertions.assertThat(httpResponse.getHeaders())
        .extracting(header -> header.getKey().equals("content-type") ? header.getValue() : null)
        .contains("image/jpeg");

    app.mocks().verifyPreviewServed(callThumbnailExpectationId);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "?version=2"})
  void givenTwoVersionsOfAnExistingDocumentTheGetThumbnailApiShouldReturnTheJpegOfTheLatestVersion(
      String versionQueryParam
  ) {
    // Given
    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                "00000000-0000-0000-0000-000000000000",
                OWNER_ID,
                OWNER_ID,
                "LOCAL_ROOT",
                "pres.odp",
                "",
                NodeType.PRESENTATION,
                "LOCAL_ROOT",
                10L,
                "application/vnd.oasis.opendocument.presentation")
        ).addVersion("00000000-0000-0000-0000-000000000000");

    String callThumbnailExpectationId = app.mocks().previewServes(
        "/preview/document/00000000-0000-0000-0000-000000000000/2/5x5/thumbnail/",
        OWNER_ID,
        "0".getBytes(),
        "image/jpeg"
    );

    final HttpRequest httpRequest =
        HttpRequest.of("GET",
            "/preview/document/00000000-0000-0000-0000-000000000000/5x5/thumbnail" + versionQueryParam,
            "ZM_AUTH_TOKEN=fake-token",
            null);

    // When
    final HttpResponse httpResponse = app.send(httpRequest);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    app.mocks().verifyPreviewServed(callThumbnailExpectationId);
  }

  @Test
  void givenTwoVersionsOfAnExistingDocumentTheGetThumbnailApiShouldReturnTheJpegOfTheFirstVersion() {
    // Given
    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                "00000000-0000-0000-0000-000000000000",
                OWNER_ID,
                OWNER_ID,
                "LOCAL_ROOT",
                "pres.odp",
                "",
                NodeType.PRESENTATION,
                "LOCAL_ROOT",
                10L,
                "application/vnd.oasis.opendocument.presentation")
        ).addVersion("00000000-0000-0000-0000-000000000000");

    String callThumbnailExpectationId = app.mocks().previewServes(
        "/preview/document/00000000-0000-0000-0000-000000000000/1/5x5/thumbnail/",
        OWNER_ID,
        "0".getBytes(),
        "image/jpeg"
    );

    final HttpRequest httpRequest =
        HttpRequest.of("GET",
            "/preview/document/00000000-0000-0000-0000-000000000000/5x5/thumbnail?version=1",
            "ZM_AUTH_TOKEN=fake-token",
            null);

    // When
    final HttpResponse httpResponse = app.send(httpRequest);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    app.mocks().verifyPreviewServed(callThumbnailExpectationId);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "?version=3"})
  void givenThreeVersionsOfAnExistingPdfTheGetPreviewApiShouldReturnTheJpegOfTheLatestVersion(
      String versionQueryParam
  ) {
    // Given
    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                "00000000-0000-0000-0000-000000000000",
                OWNER_ID,
                OWNER_ID,
                "LOCAL_ROOT",
                "buy_me.pdf",
                "",
                NodeType.APPLICATION,
                "LOCAL_ROOT",
                100L,
                "application/pdf")
        ).addVersion("00000000-0000-0000-0000-000000000000")
        .addVersion("00000000-0000-0000-0000-000000000000");

    String callThumbnailExpectationId = app.mocks().previewServes(
        "/preview/pdf/00000000-0000-0000-0000-000000000000/3/5x5/thumbnail/",
        OWNER_ID,
        "0".getBytes(),
        "image/jpeg"
    );

    final HttpRequest httpRequest =
        HttpRequest.of("GET",
            "/preview/pdf/00000000-0000-0000-0000-000000000000/5x5/thumbnail/" + versionQueryParam,
            "ZM_AUTH_TOKEN=fake-token",
            null);

    // When
    final HttpResponse httpResponse = app.send(httpRequest);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    app.mocks().verifyPreviewServed(callThumbnailExpectationId);
  }

  @Test
  void givenThreeVersionsOfAnExistingPdfTheGetThumbnailApiShouldReturnTheJpegOfTheSecondVersion() {
    // Given
    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                "00000000-0000-0000-0000-000000000000",
                OWNER_ID,
                OWNER_ID,
                "LOCAL_ROOT",
                "buy_me.pdf",
                "",
                NodeType.APPLICATION,
                "LOCAL_ROOT",
                100L,
                "application/pdf")
        ).addVersion("00000000-0000-0000-0000-000000000000")
        .addVersion("00000000-0000-0000-0000-000000000000");

    String callThumbnailExpectationId = app.mocks().previewServes(
        "/preview/pdf/00000000-0000-0000-0000-000000000000/2/5x5/thumbnail/",
        OWNER_ID,
        "0".getBytes(),
        "image/jpeg"
    );

    final HttpRequest httpRequest =
        HttpRequest.of("GET",
            "/preview/pdf/00000000-0000-0000-0000-000000000000/5x5/thumbnail?version=2",
            "ZM_AUTH_TOKEN=fake-token",
            null);

    // When
    final HttpResponse httpResponse = app.send(httpRequest);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    app.mocks().verifyPreviewServed(callThumbnailExpectationId);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "?version=2"})
  void givenTwoVersionsOfAnExistingPngImageTheGetThumbnailApiShouldReturnTheJpegOfTheLatestVersion(
      String versionQueryParam
  ) {
    // Given
    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                "00000000-0000-0000-0000-000000000000",
                OWNER_ID,
                OWNER_ID,
                "LOCAL_ROOT",
                "don't open.png",
                "",
                NodeType.IMAGE,
                "LOCAL_ROOT",
                1L,
                "image/png")
        ).addVersion("00000000-0000-0000-0000-000000000000");

    String callThumbnailExpectationId = app.mocks().previewServes(
        "/preview/image/00000000-0000-0000-0000-000000000000/2/5x5/thumbnail/",
        OWNER_ID,
        "0".getBytes(),
        "image/jpeg"
    );

    final HttpRequest httpRequest =
        HttpRequest.of("GET",
            "/preview/image/00000000-0000-0000-0000-000000000000/5x5/thumbnail" + versionQueryParam,
            "ZM_AUTH_TOKEN=fake-token",
            null);

    // When
    final HttpResponse httpResponse = app.send(httpRequest);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    app.mocks().verifyPreviewServed(callThumbnailExpectationId);
  }

  @Test
  void givenTwoVersionsOfAnExistingJpegImageTheGetThumbnailApiShouldReturnTheJpegOfTheFirstVersion() {
    // Given
    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                "00000000-0000-0000-0000-000000000000",
                OWNER_ID,
                OWNER_ID,
                "LOCAL_ROOT",
                "don't open.png",
                "",
                NodeType.IMAGE,
                "LOCAL_ROOT",
                1L,
                "image/jpeg")
        ).addVersion("00000000-0000-0000-0000-000000000000");

    String callThumbnailExpectationId = app.mocks().previewServes(
        "/preview/image/00000000-0000-0000-0000-000000000000/1/5x5/thumbnail/",
        OWNER_ID,
        "0".getBytes(),
        "image/jpeg"
    );

    final HttpRequest httpRequest =
        HttpRequest.of("GET",
            "/preview/image/00000000-0000-0000-0000-000000000000/5x5/thumbnail/?version=1",
            "ZM_AUTH_TOKEN=fake-token",
            null);

    // When
    final HttpResponse httpResponse = app.send(httpRequest);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    app.mocks().verifyPreviewServed(callThumbnailExpectationId);
  }
}
