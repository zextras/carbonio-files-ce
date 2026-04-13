// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.api;

import com.google.inject.Injector;
import com.zextras.carbonio.files.Simulator;
import com.zextras.carbonio.files.Simulator.SimulatorBuilder;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.api.utilities.DatabasePopulator;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.api.utilities.entities.SimplePopulatorFolder;
import com.zextras.carbonio.files.api.utilities.entities.SimplePopulatorTextFile;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.LinkRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.utilities.http.HttpRequest;
import com.zextras.carbonio.files.utilities.http.HttpResponse;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

class DeleteAllNodesAndBlobsApiIT {

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
            .withStorages()
            .withUserManagement( // create a fake token to use in cookie for auth
                Map.of(
                    "fake-token",
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"))
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
    simulator.reinitializeMocks();
  }

  @AfterAll
  static void cleanUpAll() {
    simulator.stopAll();
  }

  @Test
  void givenNodesOwnedByUserAndStoragesNotRespondingTheDeleteAllNodesAndBlobsShouldReturn200WithAnErrorCode() {
    // Given
    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(
            new SimplePopulatorTextFile(
                "00000000-0000-0000-0000-000000000000", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "name.txt"))
        .addNode(
            new SimplePopulatorFolder(
                "00000000-0000-0000-0000-000000000001", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "folder"
            )
        );

    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("deleteAllNodesAndBlobs")
            .withString("user_id", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
            .withWantedResultFormat("")
            .build();


    List<Map.Entry<String, String>> headers = List.of(Map.entry("Internal", ""));
    final HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", headers, bodyPayload);

    // When
    final HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    final List<String> errorResponse =
        TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errorResponse)
        .hasSize(1)
        .containsExactly("Storages returned an error while trying to delete all blobs");

  }

  @Test
  void givenNodesOwnedByUserTheDeleteAllNodesAndBlobsShouldDeleteTheNodesAndTheBlobs() {
    // Given
    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(
            new SimplePopulatorTextFile(
                "00000000-0000-0000-0000-000000000002", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "name.txt"))
        .addNode(
            new SimplePopulatorFolder(
                "00000000-0000-0000-0000-000000000003", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "folder"
            )
        );

    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("deleteAllNodesAndBlobs")
            .withString("user_id", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
            .withWantedResultFormat("")
            .build();

    simulator.bulkDelete(List.of(new String[]{"00000000-0000-0000-0000-000000000002", "00000000-0000-0000-0000-000000000003"}));

    List<Map.Entry<String, String>> headers = List.of(Map.entry("Internal", ""));
    final HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", headers, bodyPayload);

    // When
    final HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    Optional<Object> result = TestUtils.jsonResponseToValue(httpResponse.getBodyPayload(), "deleteAllNodesAndBlobs");

    Assertions.assertThat(result).isNotEmpty();
    Assertions.assertThat(result).contains(true);

  }

}
