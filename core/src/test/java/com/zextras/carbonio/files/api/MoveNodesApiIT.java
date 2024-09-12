// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.api;

import com.google.inject.Injector;
import com.zextras.carbonio.files.Files;
import com.zextras.carbonio.files.Simulator;
import com.zextras.carbonio.files.Simulator.SimulatorBuilder;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.api.utilities.DatabasePopulator;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.api.utilities.entities.PopulatorNode;
import com.zextras.carbonio.files.api.utilities.entities.SimplePopulatorFolder;
import com.zextras.carbonio.files.api.utilities.entities.SimplePopulatorTextFile;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.LinkRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.utilities.http.HttpRequest;
import com.zextras.carbonio.files.utilities.http.HttpResponse;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

class MoveNodesApiIT {

  static Simulator simulator;
  static NodeRepository nodeRepository;
  static FileVersionRepository fileVersionRepository;
  static LinkRepository linkRepository;

  @BeforeEach
  void init() {
    simulator =
        SimulatorBuilder.aSimulator()
            .init()
            .withDatabase()
            .withServiceDiscover()
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
    simulator.stopAll();
  }

  @AfterAll
  static void cleanUpAll() {
    simulator.stopAll();
  }

  @Test
  void givenANodeInRootAndANodeInAnotherFolderWithSameNameMovingThemInTheSameFolderShouldRenameTheMovedOne() {
    // Given
    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(
            new SimplePopulatorTextFile(
                "00000000-0000-0000-0000-000000000000", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "name.txt"))
        .addNode(
            new SimplePopulatorFolder(
                "00000000-0000-0000-0000-000000000001", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "folder"
            )
        )
        .addNode(
            new PopulatorNode(
                "00000000-0000-0000-0000-000000000002",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "00000000-0000-0000-0000-000000000001",
                "name.txt",
                "",
                NodeType.TEXT,
                "LOCAL_ROOT,00000000-0000-0000-0000-000000000001",
                1L,
                "text/plain"));

    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("moveNodes")
            .withListOfStrings("node_ids", new String[]{"00000000-0000-0000-0000-000000000002"})
            .withString("destination_id", "LOCAL_ROOT")
            .withWantedResultFormat("{ id name }")
            .build();

    final HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);

    // When
    final HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    final Map<String, Object> page =
        TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "moveNodes");

    final List<Map<String, Object>> nodes = (List<Map<String, Object>>) page.get("data");

    Assertions.assertThat(nodes).hasSize(1);
    // folders always on top
    Assertions.assertThat(nodes.get(0))
        .containsEntry("id", "00000000-0000-0000-0000-000000000002")
        .containsEntry("name", "name (1)");
  }

  @Test
  void givenANodeInAFolderMovingItInTheSameFolderShouldNotRenameTheNode() {
    // Given
    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(
            new SimplePopulatorTextFile(
                "00000000-0000-0000-0000-000000000003", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "second.txt"));

    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("moveNodes")
            .withListOfStrings("node_ids", new String[]{"00000000-0000-0000-0000-000000000003"})
            .withString("destination_id", "LOCAL_ROOT")
            .withWantedResultFormat("{ id name }")
            .build();

    final HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);

    // When
    final HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    final Map<String, Object> page =
        TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "moveNodes");

    final List<Map<String, Object>> nodes = (List<Map<String, Object>>) page.get("data");

    Assertions.assertThat(nodes).hasSize(1);
    // folders always on top
    Assertions.assertThat(nodes.get(0))
        .containsEntry("id", "00000000-0000-0000-0000-000000000003")
        .containsEntry("name", "second");
  }
}
