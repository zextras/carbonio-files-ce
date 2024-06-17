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
import com.zextras.carbonio.files.api.utilities.entities.SimplePopulatorTextFile;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
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

class RestoreNodesApiIT {

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
  }

  @AfterAll
  static void cleanUpAll() {
    simulator.stopAll();
  }

  private void trashNode(String nodeId){
    Optional<Node> trashedNodeOpt = nodeRepository.getNode(nodeId);
    trashedNodeOpt.ifPresent(trashedNode -> {
      String nodeParentId = trashedNode.getParentId().get();
      trashedNode.setAncestorIds(Files.Db.RootId.TRASH_ROOT);
      trashedNode.setParentId(Files.Db.RootId.TRASH_ROOT);
      nodeRepository.trashNode(trashedNode.getId(), nodeParentId);
      nodeRepository.updateNode(trashedNode);
    });
  }

  @Test
  void givenATrashedNodeRestoreNodesShouldRestoreThatNode() {
    // Given
    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(
            new SimplePopulatorTextFile(
                "00000000-0000-0000-0000-000000000002", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));

    trashNode("00000000-0000-0000-0000-000000000002");

    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("restoreNodes")
            .withListOfStrings("node_ids", new String[]{"00000000-0000-0000-0000-000000000002"})
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
        TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "restoreNodes");

    final List<Map<String, Object>> nodes = (List<Map<String, Object>>) page.get("data");

    Assertions.assertThat(nodes).hasSize(1);
    // folders always on top
    Assertions.assertThat(nodes.get(0))
        .containsEntry("id", "00000000-0000-0000-0000-000000000002")
        .containsEntry("name", "fake");
  }

  @Test
  void givenTwoFilesOnWithTheSameNameAndOneIsTrashedBothWithSameParentDirectoryRestoreNodeShouldRestoreFileWithDifferentNameFromAlreadyExisting() {
    // Given
    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(
            new SimplePopulatorTextFile(
                "00000000-0000-0000-0000-000000000000", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));

    trashNode("00000000-0000-0000-0000-000000000000");

    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(
            new SimplePopulatorTextFile(
                "00000000-0000-0000-0000-000000000001", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));

    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("restoreNodes")
            .withListOfStrings("node_ids", new String[]{"00000000-0000-0000-0000-000000000000"})
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
        TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "restoreNodes");

    final List<Map<String, Object>> nodes = (List<Map<String, Object>>) page.get("data");

    Assertions.assertThat(nodes).hasSize(1);
    // folders always on top
    Assertions.assertThat(nodes.get(0))
        .containsEntry("id", "00000000-0000-0000-0000-000000000000")
        .containsEntry("name", "fake (1)");
  }
}
