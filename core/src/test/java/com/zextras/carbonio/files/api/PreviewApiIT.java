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
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.model.Parameter;

import java.io.IOException;
import java.util.Map;

public class PreviewApiIT {

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
  }

  @AfterAll
  static void cleanUpAll() {
    simulator.stopAll();
  }

  @Test
  void givenAnExistingDocumentGetPreviewShouldReturnTheCorrectPreview() throws IOException {
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

    MockServerClient previewServiceMock = simulator.getPreviewServiceMock();

    previewServiceMock
        .when(
            org.mockserver.model.HttpRequest.request()
                .withMethod(HttpMethod.GET.toString())
                .withPath("/document/00000000-0000-0000-0000-000000000000/1/")
                .withQueryStringParameter(new Parameter("locale", "en"))
                .withQueryStringParameter(new Parameter("service_type", "files"))
                .withHeader("FileOwnerId", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        )
        .respond(org.mockserver.model.HttpResponse.response()
            .withStatusCode(200)
            .withBody("0"));

    final HttpRequest httpRequest =
        HttpRequest.of("GET",
            "/preview/document/00000000-0000-0000-0000-000000000000/1",
            "ZM_AUTH_TOKEN=fake-token",
            null);

    // When
    final HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

  }
}
