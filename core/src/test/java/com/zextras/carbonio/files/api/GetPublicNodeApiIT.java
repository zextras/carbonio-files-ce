// SPDX-FileCopyrightText: 2023 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.api;

import com.google.inject.Injector;
import com.zextras.carbonio.files.Simulator;
import com.zextras.carbonio.files.Simulator.SimulatorBuilder;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.api.utilities.DatabasePopulator;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.api.utilities.entities.PopulatorNode;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.LinkRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.utilities.http.HttpRequest;
import com.zextras.carbonio.files.utilities.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class GetPublicNodeApiIT {

  static Simulator simulator;
  static NodeRepository nodeRepository;
  static LinkRepository linkRepository;
  static FileVersionRepository fileVersionRepository;

  @BeforeAll
  static void init() {
    simulator =
        SimulatorBuilder.aSimulator().init().withDatabase().withServiceDiscover().build().start();

    final Injector injector = simulator.getInjector();
    nodeRepository = injector.getInstance(NodeRepository.class);
    linkRepository = injector.getInstance(LinkRepository.class);
    fileVersionRepository = injector.getInstance(FileVersionRepository.class);
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
  void givenAPublicLinkIdAndAnExistingFolderTheGetPublicNodeShouldReturnThePublicFolder() {
    // Given
    long now = System.currentTimeMillis();
    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(
            new PopulatorNode(
                "00000000-0000-0000-0000-000000000000",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "LOCAL_ROOT",
                "folder",
                "",
                NodeType.FOLDER,
                "LOCAL_ROOT",
                0L,
                null))
        .addLink(
            "8cac6df0-3ecb-451d-a953-10c3ac5e3ebc",
            "00000000-0000-0000-0000-000000000000",
            "abcd1234abcd1234abcd1234abcd1234",
            Optional.empty(),
            Optional.empty());

    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("getPublicNode")
            .withString("node_link_id", "abcd1234abcd1234abcd1234abcd1234")
            .build("{ id created_at updated_at name type }");

    final HttpRequest httpRequest = HttpRequest.of("POST", "/public/graphql/", null, bodyPayload);

    // When
    HttpResponse httpResponse = TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    final Map<String, Object> publicNode =
        TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "getPublicNode");

    Assertions.assertThat(publicNode.get("id")).isEqualTo("00000000-0000-0000-0000-000000000000");
    Assertions.assertThat((long) publicNode.get("created_at")).isGreaterThanOrEqualTo(now);
    Assertions.assertThat((long) publicNode.get("updated_at")).isGreaterThanOrEqualTo(now);
    Assertions.assertThat(publicNode.get("name")).isEqualTo("folder");
    Assertions.assertThat(publicNode.get("type")).isEqualTo(NodeType.FOLDER.toString());
  }

  @Test
  void givenAPublicLinkIdAndAnExistingFileTheGetPublicNodeShouldReturnThePublicFile() {
    // Given
    long now = System.currentTimeMillis();
    DatabasePopulator.aNodePopulator(simulator.getInjector())
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
                5L,
                "text/plain"))
        .addLink(
            "8cac6df0-3ecb-451d-a953-10c3ac5e3ebc",
            "00000000-0000-0000-0000-000000000000",
            "abcd1234abcd1234abcd1234abcd1234",
            Optional.empty(),
            Optional.empty());

    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("getPublicNode")
            .withString("node_link_id", "abcd1234abcd1234abcd1234abcd1234")
            .build(
                "{ id created_at updated_at name type ... on File { extension mime_type size } }");

    final HttpRequest httpRequest = HttpRequest.of("POST", "/public/graphql/", null, bodyPayload);

    // When
    HttpResponse httpResponse = TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    final Map<String, Object> publicNode =
        TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "getPublicNode");

    Assertions.assertThat(publicNode.get("id")).isEqualTo("00000000-0000-0000-0000-000000000000");
    Assertions.assertThat((long) publicNode.get("created_at")).isGreaterThanOrEqualTo(now);
    Assertions.assertThat((long) publicNode.get("updated_at")).isGreaterThanOrEqualTo(now);
    Assertions.assertThat(publicNode.get("name")).isEqualTo("test");
    Assertions.assertThat(publicNode.get("extension")).isEqualTo("txt");
    Assertions.assertThat(publicNode.get("type")).isEqualTo(NodeType.TEXT.toString());
    Assertions.assertThat(publicNode.get("mime_type")).isEqualTo("text/plain");
    Assertions.assertThat(publicNode.get("size")).isEqualTo(5.0);
  }

  @Test
  void
      givenANotExistingPublicLinkIdAndAnExistingFolderTheGetPublicNodeShouldReturn200StatusCodeWithAnErrorMessage() {
    // Given
    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(
            new PopulatorNode(
                "00000000-0000-0000-0000-000000000000",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "LOCAL_ROOT",
                "folder",
                "",
                NodeType.FOLDER,
                "LOCAL_ROOT",
                0L,
                null));

    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("getPublicNode")
            .withString("node_link_id", "abcd1234abcd1234abcd1234abcd1234")
            .build("{ id }");

    final HttpRequest httpRequest = HttpRequest.of("POST", "/public/graphql/", null, bodyPayload);

    // When
    HttpResponse httpResponse = TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    final List<String> errorMessages =
        TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());

    Assertions.assertThat(errorMessages)
        .hasSize(1)
        .containsExactly("Could not find link with id abcd1234abcd1234abcd1234abcd1234");
  }

  @Test
  void
      givenAnExpiredPublicLinkIdAndAnExistingFolderTheGetPublicNodeShouldReturn200StatusCodeWithAnErrorMessage() {
    // Given
    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(
            new PopulatorNode(
                "00000000-0000-0000-0000-000000000000",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "LOCAL_ROOT",
                "folder",
                "",
                NodeType.FOLDER,
                "LOCAL_ROOT",
                0L,
                null))
        .addLink(
            "8cac6df0-3ecb-451d-a953-10c3ac5e3ebc",
            "00000000-0000-0000-0000-000000000000",
            "abcd1234abcd1234abcd1234abcd1234",
            Optional.of(1L),
            Optional.empty());

    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("getPublicNode")
            .withString("node_link_id", "abcd1234abcd1234abcd1234abcd1234")
            .build("{ id }");

    final HttpRequest httpRequest = HttpRequest.of("POST", "/public/graphql/", null, bodyPayload);

    // When
    HttpResponse httpResponse = TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    final List<String> errorMessages =
        TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());

    Assertions.assertThat(errorMessages)
        .hasSize(1)
        .containsExactly("Could not find link with id abcd1234abcd1234abcd1234abcd1234");
  }
}
