// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
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
import java.util.Map;
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

class PreviewApiIT {

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
            .withUserManagement( // create a fake token to use in cookie for auth
                Map.of("fake-token", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"))
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

  static String mockSuccessPreviewResponse(
      String previewPathEndpoint, MediaType previewTypeResponse) {
    org.mockserver.model.HttpRequest request =
        org.mockserver.model.HttpRequest.request()
            .withMethod(HttpMethod.GET.toString())
            .withPath(previewPathEndpoint)
            .withQueryStringParameter(new Parameter("service_type", "files"))
            .withHeader("FileOwnerId", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    if (previewPathEndpoint.contains("document")) {
      request.withQueryStringParameter(new Parameter("lang_tag", "en"));
    }

    return simulator
        .getPreviewMock()
        .when(request)
        .respond(
            org.mockserver.model.HttpResponse.response()
                .withStatusCode(200)
                .withBody(new BinaryBody("0".getBytes()))
                .withContentType(previewTypeResponse))[0]
        .getId();
  }

  static void verifyAndClearExpectationInPreviewMockService(String expectationId) {
    MockServerClient previewServiceMock = simulator.getPreviewMock();
    previewServiceMock.verify(expectationId).clear(expectationId);
  }

  @Test
  void givenAnExistingDocumentTheGetPreviewApiShouldGetAndReturnThePreviewWithLangTag() {
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
                "application/vnd.ms-excel"));

    String callPreviewExpectationId =
        mockSuccessPreviewResponse(
            "/preview/document/00000000-0000-0000-0000-000000000000/1/", MediaType.PDF);

    final HttpRequest httpRequest =
        HttpRequest.of(
            "GET",
            "/preview/document/00000000-0000-0000-0000-000000000000",
            "ZM_AUTH_TOKEN=fake-token",
            null);

    // When
    final HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Assertions.assertThat(httpResponse.getHeaders())
        .extracting(header -> header.getKey().equals("content-type") ? header.getValue() : null)
        .contains("application/pdf");

    verifyAndClearExpectationInPreviewMockService(callPreviewExpectationId);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "?version=2"})
  void givenTwoVersionsOfAnExistingDocumentTheGetPreviewApiShouldReturnThePdfOfTheLatestVersion(
      String versionQueryParam) {
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
                "application/vnd.oasis.opendocument.presentation"))
        .addVersion("00000000-0000-0000-0000-000000000000");

    String callPreviewExpectationId =
        mockSuccessPreviewResponse(
            "/preview/document/00000000-0000-0000-0000-000000000000/2/", MediaType.PDF);

    final HttpRequest httpRequest =
        HttpRequest.of(
            "GET",
            "/preview/document/00000000-0000-0000-0000-000000000000" + versionQueryParam,
            "ZM_AUTH_TOKEN=fake-token",
            null);

    // When
    final HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    verifyAndClearExpectationInPreviewMockService(callPreviewExpectationId);
  }

  @Test
  void givenTwoVersionsOfAnExistingDocumentTheGetPreviewApiShouldReturnThePdfOfTheFirstVersion() {
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
                "application/vnd.oasis.opendocument.presentation"))
        .addVersion("00000000-0000-0000-0000-000000000000");

    String callPreviewExpectationId =
        mockSuccessPreviewResponse(
            "/preview/document/00000000-0000-0000-0000-000000000000/1/", MediaType.PDF);

    final HttpRequest httpRequest =
        HttpRequest.of(
            "GET",
            "/preview/document/00000000-0000-0000-0000-000000000000?version=1",
            "ZM_AUTH_TOKEN=fake-token",
            null);

    // When
    final HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    verifyAndClearExpectationInPreviewMockService(callPreviewExpectationId);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "?version=3"})
  void givenThreeVersionsOfAnExistingPdfTheGetPreviewApiShouldReturnThePdfOfTheLatestVersion(
      String versionQueryParam) {
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
                "application/pdf"))
        .addVersion("00000000-0000-0000-0000-000000000000")
        .addVersion("00000000-0000-0000-0000-000000000000");

    String callPreviewExpectationId =
        mockSuccessPreviewResponse(
            "/preview/pdf/00000000-0000-0000-0000-000000000000/3/", MediaType.PDF);

    final HttpRequest httpRequest =
        HttpRequest.of(
            "GET",
            "/preview/pdf/00000000-0000-0000-0000-000000000000/" + versionQueryParam,
            "ZM_AUTH_TOKEN=fake-token",
            null);

    // When
    final HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    verifyAndClearExpectationInPreviewMockService(callPreviewExpectationId);
  }

  @Test
  void givenThreeVersionsOfAnExistingPdfTheGetPreviewApiShouldReturnThePdfOfTheSecondVersion() {
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
                "application/pdf"))
        .addVersion("00000000-0000-0000-0000-000000000000")
        .addVersion("00000000-0000-0000-0000-000000000000");

    String callPreviewExpectationId =
        mockSuccessPreviewResponse(
            "/preview/pdf/00000000-0000-0000-0000-000000000000/2/", MediaType.PDF);

    final HttpRequest httpRequest =
        HttpRequest.of(
            "GET",
            "/preview/pdf/00000000-0000-0000-0000-000000000000?version=2",
            "ZM_AUTH_TOKEN=fake-token",
            null);

    // When
    final HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    verifyAndClearExpectationInPreviewMockService(callPreviewExpectationId);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "?version=2"})
  void givenTwoVersionsOfAnExistingPngImageTheGetPreviewApiShouldReturnThePngOfTheLatestVersion(
      String versionQueryParam) {
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
                "image/png"))
        .addVersion("00000000-0000-0000-0000-000000000000");

    String callPreviewExpectationId =
        mockSuccessPreviewResponse(
            "/preview/image/00000000-0000-0000-0000-000000000000/2/0x0/", MediaType.PNG);

    final HttpRequest httpRequest =
        HttpRequest.of(
            "GET",
            "/preview/image/00000000-0000-0000-0000-000000000000/0x0" + versionQueryParam,
            "ZM_AUTH_TOKEN=fake-token",
            null);

    // When
    final HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    verifyAndClearExpectationInPreviewMockService(callPreviewExpectationId);
  }

  @Test
  void
      givenTwoVersionsOfAnExistingJpegImageTheGetPreviewApiShouldReturnTheJpegOfTheSecondVersion() {
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
                "image/jpeg"))
        .addVersion("00000000-0000-0000-0000-000000000000");

    String callPreviewExpectationId =
        mockSuccessPreviewResponse(
            "/preview/image/00000000-0000-0000-0000-000000000000/2/0x0/", MediaType.JPEG);

    final HttpRequest httpRequest =
        HttpRequest.of(
            "GET",
            "/preview/image/00000000-0000-0000-0000-000000000000/0x0?version=2",
            "ZM_AUTH_TOKEN=fake-token",
            null);

    // When
    final HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    verifyAndClearExpectationInPreviewMockService(callPreviewExpectationId);
  }
}
