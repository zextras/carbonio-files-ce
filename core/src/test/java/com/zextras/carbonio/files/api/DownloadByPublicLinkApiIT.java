// SPDX-FileCopyrightText: 2023 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.api;

import com.google.inject.Injector;
import com.zextras.carbonio.files.Simulator;
import com.zextras.carbonio.files.Simulator.SimulatorBuilder;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.LinkRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.utilities.http.HttpRequest;
import com.zextras.carbonio.files.utilities.http.HttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import java.util.Map;
import java.util.Optional;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockserver.model.HttpError;
import org.mockserver.model.Parameter;
import org.mockserver.verify.VerificationTimes;

public class DownloadByPublicLinkApiIT {

  static Simulator simulator;
  static NodeRepository nodeRepository;
  static FileVersionRepository fileVersionRepository;
  static LinkRepository linkRepository;

  @BeforeAll
  static void init() {
    simulator =
        SimulatorBuilder.aSimulator()
            .init()
            .withDatabase()
            .withServiceDiscover()
            .withUserManagement(Map.of("fake-token", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"))
            .withStorages()
            .build()
            .start();

    final Injector injector = simulator.getInjector();
    nodeRepository = injector.getInstance(NodeRepository.class);
    fileVersionRepository = injector.getInstance(FileVersionRepository.class);
    linkRepository = injector.getInstance(LinkRepository.class);
  }

  @AfterEach
  void cleanUp() {
    simulator.resetDatabase();
    simulator.getStoragesMock().reset();
  }

  @AfterAll
  static void cleanUpAll() {
    simulator.stopAll();
  }

  @ParameterizedTest
  @CsvSource({
    "abcd1234,/public/link/download/,",
    "abcd1234abcd1234abcd1234abcd1234,/public/link/download/,",
    "abcd1234,/link/,",
    "abcd1234abcd1234abcd1234abcd1234,/link/,",
    "abcd1234abcd1234abcd1234abcd1234,/public/link/download/,fake-token",
    "abcd1234abcd1234abcd1234abcd1234,/link/,fake-token",
  })
  void
      givenAUserWithOrWithoutCookieAnExistingFileAndAnExistingPublicLinkAssociatedTheDownloadByPublicLinkShouldReturnTheBlob(
          String publicLinkId, String publicLinkEndpoint, String userToken) {
    // Given
    nodeRepository.createNewNode(
        "00000000-0000-0000-0000-000000000000",
        "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
        "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
        "LOCAL_ROOT",
        "test.txt",
        "",
        NodeType.TEXT,
        "LOCAL_ROOT",
        10L);

    fileVersionRepository.createNewFileVersion(
        "00000000-0000-0000-0000-000000000000",
        "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
        1,
        "text/plain",
        10L,
        "",
        false);

    linkRepository.createLink(
        "94103c01-e701-4f3d-9dc9-54b79064ad76",
        "00000000-0000-0000-0000-000000000000",
        publicLinkId,
        Optional.empty(),
        Optional.empty());

    simulator.getBlob("00000000-0000-0000-0000-000000000000", 1);

    final String publicLinkUrl = publicLinkEndpoint + publicLinkId;
    HttpRequest httpRequest = HttpRequest.of("GET", publicLinkUrl, userToken, null);

    // When
    HttpResponse httpResponse = TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    simulator
        .getStoragesMock()
        .verify(
            org.mockserver.model.HttpRequest.request()
                .withMethod(HttpMethod.GET.toString())
                .withPath("/download")
                .withQueryStringParameter(
                    Parameter.param("node", "00000000-0000-0000-0000-000000000000"))
                .withQueryStringParameter(Parameter.param("version", "1"))
                .withQueryStringParameter(Parameter.param("type", "files")),
            VerificationTimes.once());
  }

  @Test
  void givenANotExistingLinkTheDownloadByPublicLinkShouldReturnA404StatusCode() {
    // Given
    nodeRepository.createNewNode(
        "00000000-0000-0000-0000-000000000000",
        "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
        "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
        "LOCAL_ROOT",
        "file.txt",
        "",
        NodeType.TEXT,
        "LOCAL_ROOT",
        1L);

    linkRepository.createLink(
        "94103c01-e701-4f3d-9dc9-54b79064ad76",
        "00000000-0000-0000-0000-000000000000",
        "000000",
        Optional.empty(),
        Optional.empty());

    HttpRequest httpRequest = HttpRequest.of("GET", "/public/link/download/1234abcd", null, null);

    // When
    HttpResponse httpResponse = TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(404);
    Assertions.assertThat(httpResponse.getBodyPayload()).isEqualTo("404 Not Found");

    simulator
        .getStoragesMock()
        .verify(
            org.mockserver.model.HttpRequest.request()
                .withMethod(HttpMethod.GET.toString())
                .withPath("/download"),
            VerificationTimes.never());
  }

  @Test
  void givenANotExistingNodeTheDownloadByPublicLinkShouldReturnA404StatusCode() {
    // Given
    HttpRequest httpRequest = HttpRequest.of("GET", "/public/link/download/1234abcd", null, null);

    // When
    HttpResponse httpResponse = TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(404);
    Assertions.assertThat(httpResponse.getBodyPayload()).isEqualTo("404 Not Found");

    simulator
        .getStoragesMock()
        .verify(
            org.mockserver.model.HttpRequest.request()
                .withMethod(HttpMethod.GET.toString())
                .withPath("/download"),
            VerificationTimes.never());
  }

  @Test
  void t() {
    // Given
    nodeRepository.createNewNode(
        "00000000-0000-0000-0000-000000000000",
        "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
        "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
        "LOCAL_ROOT",
        "test.txt",
        "",
        NodeType.TEXT,
        "LOCAL_ROOT",
        10L);

    fileVersionRepository.createNewFileVersion(
        "00000000-0000-0000-0000-000000000000",
        "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
        1,
        "text/plain",
        10L,
        "",
        false);

    linkRepository.createLink(
        "94103c01-e701-4f3d-9dc9-54b79064ad76",
        "00000000-0000-0000-0000-000000000000",
        "1234abcd1234abcd1234abcd1234abcd",
        Optional.empty(),
        Optional.empty());

    simulator
        .getStoragesMock()
        .when(
            org.mockserver.model.HttpRequest.request()
                .withMethod(HttpMethod.GET.toString())
                .withPath("/download"))
        .error(HttpError.error().withDropConnection(true));

    // When
    HttpRequest httpRequest =
        HttpRequest.of("GET", "/public/link/download/1234abcd1234abcd1234abcd1234abcd", null, null);

    // Then
    HttpResponse httpResponse = TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(500);
    Assertions.assertThat(httpResponse.getBodyPayload()).isEqualTo("500 Internal Server Error");
  }
}
