// SPDX-FileCopyrightText: 2025 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.api;

import com.google.inject.Injector;
import com.zextras.carbonio.files.Simulator;
import com.zextras.carbonio.files.Simulator.SimulatorBuilder;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.api.utilities.DatabasePopulator;
import com.zextras.carbonio.files.api.utilities.entities.PopulatorNode;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.utilities.http.HttpRequest;
import com.zextras.carbonio.files.utilities.http.HttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockserver.client.MockServerClient;
import org.mockserver.model.BinaryBody;
import org.mockserver.model.MediaType;
import org.mockserver.model.Parameter;

import java.util.Map;

class ThumbnailApiIT {

  static Simulator simulator;
  static NodeRepository nodeRepository;

  @BeforeAll
  static void init() {
    simulator =
        SimulatorBuilder.aSimulator()
            .init()
            .withDatabase()
            .withServiceDiscover()
            .withPreview()
            .withUserManagement(
                Map.of(
                    "fake-token",
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"))
            .build()
            .start();

    final Injector injector = simulator.getInjector();
    nodeRepository = injector.getInstance(NodeRepository.class);
  }

  @AfterEach
  void cleanUp() {
    simulator.resetDatabase();
    simulator.clearFileVersionCache();
  }

  @AfterAll
  static void cleanUpAll() {
    simulator.stopAll();
  }

  static String mockSuccessThumbnailResponse(String thumbnailPathEndpoint) {

    org.mockserver.model.HttpRequest request = org.mockserver.model.HttpRequest.request()
        .withMethod(HttpMethod.GET.toString())
        .withPath(thumbnailPathEndpoint)
        .withQueryStringParameter(new Parameter("service_type", "files"))
        .withHeader("FileOwnerId", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    if (thumbnailPathEndpoint.contains("document")) {
      request.withQueryStringParameter(new Parameter("locale", "en"));
    }

    return simulator.getPreviewServiceMock()
        .when(request)
        .respond(org.mockserver.model.HttpResponse.response()
            .withStatusCode(200)
            .withBody(new BinaryBody("0".getBytes())).withContentType(MediaType.JPEG))[0].getId();
  }

  static void verifyAndClearExpectationInThumbnailMockService(String expectationId) {
    MockServerClient previewServiceMock = simulator.getPreviewServiceMock();
    previewServiceMock.verify(expectationId).clear(expectationId);
  }

  @Test
  void givenAnExistingDocumentTheGetThumbnailApiShouldGetAndReturnTheThumbnailWithLocale() {
    // Given
    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(
            new PopulatorNode(
                "00000000-0000-0000-0000-000000000000",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "LOCAL_ROOT",
                "FILE.XLS",
                "",
                NodeType.SPREADSHEET,
                "LOCAL_ROOT",
                7L,
                "application/vnd.ms-excel")
        );

    String callThumbnailExpectationId = mockSuccessThumbnailResponse(
        "/preview/document/00000000-0000-0000-0000-000000000000/1/5x5/thumbnail/"
    );

    final HttpRequest httpRequest =
        HttpRequest.of("GET",
            "/preview/document/00000000-0000-0000-0000-000000000000/5x5/thumbnail",
            "ZM_AUTH_TOKEN=fake-token",
            null);

    // When
    final HttpResponse httpResponse = TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Assertions.assertThat(httpResponse.getHeaders())
        .extracting(header -> header.getKey().equals("content-type") ? header.getValue() : null)
        .contains("image/jpeg");

    verifyAndClearExpectationInThumbnailMockService(callThumbnailExpectationId);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "?version=2"})
  void givenTwoVersionsOfAnExistingDocumentTheGetThumbnailApiShouldReturnTheJpegOfTheLatestVersion(
      String versionQueryParam
  ) {
    // Given
    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(
            new PopulatorNode(
                "00000000-0000-0000-0000-000000000000",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "LOCAL_ROOT",
                "pres.odp",
                "",
                NodeType.PRESENTATION,
                "LOCAL_ROOT",
                10L,
                "application/vnd.oasis.opendocument.presentation")
        ).addVersion("00000000-0000-0000-0000-000000000000");

    String callThumbnailExpectationId = mockSuccessThumbnailResponse(
        "/preview/document/00000000-0000-0000-0000-000000000000/2/5x5/thumbnail/"
    );

    final HttpRequest httpRequest =
        HttpRequest.of("GET",
            "/preview/document/00000000-0000-0000-0000-000000000000/5x5/thumbnail" + versionQueryParam,
            "ZM_AUTH_TOKEN=fake-token",
            null);

    // When
    final HttpResponse httpResponse = TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    verifyAndClearExpectationInThumbnailMockService(callThumbnailExpectationId);
  }

  @Test
  void givenTwoVersionsOfAnExistingDocumentTheGetThumbnailApiShouldReturnTheJpegOfTheFirstVersion() {
    // Given
    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(
            new PopulatorNode(
                "00000000-0000-0000-0000-000000000000",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "LOCAL_ROOT",
                "pres.odp",
                "",
                NodeType.PRESENTATION,
                "LOCAL_ROOT",
                10L,
                "application/vnd.oasis.opendocument.presentation")
        ).addVersion("00000000-0000-0000-0000-000000000000");

    String callThumbnailExpectationId = mockSuccessThumbnailResponse(
        "/preview/document/00000000-0000-0000-0000-000000000000/1/5x5/thumbnail/"
    );

    final HttpRequest httpRequest =
        HttpRequest.of("GET",
            "/preview/document/00000000-0000-0000-0000-000000000000/5x5/thumbnail?version=1",
            "ZM_AUTH_TOKEN=fake-token",
            null);

    // When
    final HttpResponse httpResponse = TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    verifyAndClearExpectationInThumbnailMockService(callThumbnailExpectationId);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "?version=3"})
  void givenThreeVersionsOfAnExistingPdfTheGetPreviewApiShouldReturnTheJpegOfTheLatestVersion(
      String versionQueryParam
  ) {
    // Given
    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(
            new PopulatorNode(
                "00000000-0000-0000-0000-000000000000",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "LOCAL_ROOT",
                "buy_me.pdf",
                "",
                NodeType.APPLICATION,
                "LOCAL_ROOT",
                100L,
                "application/pdf")
        ).addVersion("00000000-0000-0000-0000-000000000000")
        .addVersion("00000000-0000-0000-0000-000000000000");

    String callThumbnailExpectationId = mockSuccessThumbnailResponse(
        "/preview/pdf/00000000-0000-0000-0000-000000000000/3/5x5/thumbnail/"
    );

    final HttpRequest httpRequest =
        HttpRequest.of("GET",
            "/preview/pdf/00000000-0000-0000-0000-000000000000/5x5/thumbnail/" + versionQueryParam,
            "ZM_AUTH_TOKEN=fake-token",
            null);

    // When
    final HttpResponse httpResponse = TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    verifyAndClearExpectationInThumbnailMockService(callThumbnailExpectationId);
  }

  @Test
  void givenThreeVersionsOfAnExistingPdfTheGetThumbnailApiShouldReturnTheJpegOfTheSecondVersion() {
    // Given
    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(
            new PopulatorNode(
                "00000000-0000-0000-0000-000000000000",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "LOCAL_ROOT",
                "buy_me.pdf",
                "",
                NodeType.APPLICATION,
                "LOCAL_ROOT",
                100L,
                "application/pdf")
        ).addVersion("00000000-0000-0000-0000-000000000000")
        .addVersion("00000000-0000-0000-0000-000000000000");

    String callThumbnailExpectationId = mockSuccessThumbnailResponse(
        "/preview/pdf/00000000-0000-0000-0000-000000000000/2/5x5/thumbnail/"
    );

    final HttpRequest httpRequest =
        HttpRequest.of("GET",
            "/preview/pdf/00000000-0000-0000-0000-000000000000/5x5/thumbnail?version=2",
            "ZM_AUTH_TOKEN=fake-token",
            null);

    // When
    final HttpResponse httpResponse = TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    verifyAndClearExpectationInThumbnailMockService(callThumbnailExpectationId);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "?version=2"})
  void givenTwoVersionsOfAnExistingPngImageTheGetThumbnailApiShouldReturnTheJpegOfTheLatestVersion(
      String versionQueryParam
  ) {
    // Given
    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(
            new PopulatorNode(
                "00000000-0000-0000-0000-000000000000",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "LOCAL_ROOT",
                "don't open.png",
                "",
                NodeType.IMAGE,
                "LOCAL_ROOT",
                1L,
                "image/png")
        ).addVersion("00000000-0000-0000-0000-000000000000");

    String callThumbnailExpectationId = mockSuccessThumbnailResponse(
        "/preview/image/00000000-0000-0000-0000-000000000000/2/5x5/thumbnail/"
    );

    final HttpRequest httpRequest =
        HttpRequest.of("GET",
            "/preview/image/00000000-0000-0000-0000-000000000000/5x5/thumbnail" + versionQueryParam,
            "ZM_AUTH_TOKEN=fake-token",
            null);

    // When
    final HttpResponse httpResponse = TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    verifyAndClearExpectationInThumbnailMockService(callThumbnailExpectationId);
  }

  @Test
  void givenTwoVersionsOfAnExistingJpegImageTheGetThumbnailApiShouldReturnTheJpegOfTheFirstVersion() {
    // Given
    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(
            new PopulatorNode(
                "00000000-0000-0000-0000-000000000000",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "LOCAL_ROOT",
                "don't open.png",
                "",
                NodeType.IMAGE,
                "LOCAL_ROOT",
                1L,
                "image/jpeg")
        ).addVersion("00000000-0000-0000-0000-000000000000");

    String callThumbnailExpectationId = mockSuccessThumbnailResponse(
        "/preview/image/00000000-0000-0000-0000-000000000000/1/5x5/thumbnail/"
    );

    final HttpRequest httpRequest =
        HttpRequest.of("GET",
            "/preview/image/00000000-0000-0000-0000-000000000000/5x5/thumbnail/?version=1",
            "ZM_AUTH_TOKEN=fake-token",
            null);

    // When
    final HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    verifyAndClearExpectationInThumbnailMockService(callThumbnailExpectationId);
  }
}
