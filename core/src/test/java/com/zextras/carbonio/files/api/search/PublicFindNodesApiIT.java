// SPDX-FileCopyrightText: 2023 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.api.search;

import com.google.inject.Injector;
import com.zextras.carbonio.files.Simulator;
import com.zextras.carbonio.files.Simulator.SimulatorBuilder;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.api.utilities.DatabasePopulator;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.api.utilities.entities.PopulatorNode;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.LinkRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.utilities.http.HttpRequest;
import com.zextras.carbonio.files.utilities.http.HttpResponse;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class PublicFindNodesApiIT {

  static Simulator simulator;
  static NodeRepository nodeRepository;
  static FileVersionRepository fileVersionRepository;
  static LinkRepository linkRepository;

  @BeforeAll
  static void init() {
    simulator =
        SimulatorBuilder.aSimulator().init()
            .withDatabase()
            .withMessageBroker()
            .withServiceDiscover()
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

  void createFolderTree() {
    String ownerId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(
            new PopulatorNode(
                "00000000-0000-0000-0000-000000000000",
                ownerId,
                ownerId,
                "LOCAL_ROOT",
                "public folder",
                "",
                NodeType.FOLDER,
                "LOCAL_ROOT",
                0L,
                null))
        .addNode(
            new PopulatorNode(
                "11111111-1111-1111-1111-111111111111",
                ownerId,
                ownerId,
                "00000000-0000-0000-0000-000000000000",
                "folder child",
                "",
                NodeType.FOLDER,
                "LOCAL_ROOT,00000000-0000-0000-0000-000000000000",
                0L,
                null));

    final List<String> childrenFileIds =
        Arrays.asList(
            "22222222-2222-2222-2222-222222222222",
            "33333333-3333-3333-3333-333333333333",
            "44444444-4444-4444-4444-444444444444",
            "55555555-5555-5555-5555-555555555555");

    childrenFileIds.forEach(
        fileId -> {
          DatabasePopulator.aNodePopulator(simulator.getInjector())
              .addNode(
                  new PopulatorNode(
                      fileId,
                      ownerId,
                      ownerId,
                      "00000000-0000-0000-0000-000000000000",
                      "file child id " + fileId.charAt(0) + ".txt",
                      "",
                      NodeType.TEXT,
                      "LOCAL_ROOT,00000000-0000-0000-0000-000000000000",
                      5L,
                      "text/plain"));
        });
  }

  @DisplayName(
      """
    Given an existing folder with five nodes inside, a valid public link associated, a limit of
    three elements per page: the findNodes should return the first page containing three nodes
    ordered by category and alphabetically and the page_token for the next page""")
  @Test
  void givenAnExistingFolderAndAValidPublicLinkTheFindNodesShouldReturnTheFirstPage() {
    // Given
    createFolderTree();
    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addLink(
            "54ef41f2-8edf-4023-8b70-b29441a8e8b0",
            "00000000-0000-0000-0000-000000000000",
            "abcd1234abcd1234abcd1234abcd1234",
            Optional.empty(),
            Optional.empty());

    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("findNodes")
            .withString("folder_id", "00000000-0000-0000-0000-000000000000")
            .withInteger("limit", 3)
            .withWantedResultFormat("{ nodes { id name }, page_token }")
            .build();

    final HttpRequest httpRequest = HttpRequest.of("POST", "/public/graphql/", null, bodyPayload);

    // When
    final HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    final Map<String, Object> page =
        TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "findNodes");

    Assertions.assertThat(page.get("page_token")).isNotNull();

    final List<Map<String, Object>> nodes = (List<Map<String, Object>>) page.get("nodes");

    Assertions.assertThat(nodes).hasSize(3);
    Assertions.assertThat(nodes.get(0))
        .containsEntry("id", "11111111-1111-1111-1111-111111111111")
        .containsEntry("name", "folder child");
    Assertions.assertThat(nodes.get(1))
        .containsEntry("id", "22222222-2222-2222-2222-222222222222")
        .containsEntry("name", "file child id 2");
    Assertions.assertThat(nodes.get(2))
        .containsEntry("id", "33333333-3333-3333-3333-333333333333")
        .containsEntry("name", "file child id 3");
  }

  @DisplayName(
      """
    Given an existing folder with five nodes inside, a valid public link associated, a limit of
    three elements per page and a page token for the second page: the findNodes should return the
    second page containing two nodes and a null page_token""")
  @Test
  void givenAnExistingFolderAndAValidLinkTheFindNodesShouldReturnTheSecondPage() {
    // Given
    createFolderTree();
    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addLink(
            "54ef41f2-8edf-4023-8b70-b29441a8e8b0",
            "00000000-0000-0000-0000-000000000000",
            "abcd1234abcd1234abcd1234abcd1234",
            Optional.empty(),
            Optional.empty());

    // Start request first page of the folder content
    String firstBodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("findNodes")
            .withString("folder_id", "00000000-0000-0000-0000-000000000000")
            .withInteger("limit", 3)
            .withWantedResultFormat("{ nodes { id name }, page_token }")
            .build();

    final HttpRequest firstHttpRequest =
        HttpRequest.of("POST", "/public/graphql/", null, firstBodyPayload);
    final HttpResponse firstHttpResponse =
        TestUtils.sendRequest(firstHttpRequest, simulator.getNettyChannel());

    Assertions.assertThat(firstHttpResponse.getStatus()).isEqualTo(200);
    String pageToken =
        (String)
            TestUtils.jsonResponseToMap(firstHttpResponse.getBodyPayload(), "findNodes")
                .get("page_token");
    // End request first page of the folder content

    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("findNodes")
            .withString("folder_id", "00000000-0000-0000-0000-000000000000")
            .withInteger("limit", 3)
            .withString("page_token", pageToken)
            .withWantedResultFormat("{ nodes { id name }, page_token }")
            .build();

    final HttpRequest httpRequest = HttpRequest.of("POST", "/public/graphql/", null, bodyPayload);

    // When
    final HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    final Map<String, Object> page =
        TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "findNodes");

    Assertions.assertThat(page.get("page_token")).isNull();

    final List<Map<String, Object>> nodes = (List<Map<String, Object>>) page.get("nodes");

    Assertions.assertThat(nodes).hasSize(2);
    Assertions.assertThat(nodes.get(0))
        .containsEntry("id", "44444444-4444-4444-4444-444444444444")
        .containsEntry("name", "file child id 4");
    Assertions.assertThat(nodes.get(1))
        .containsEntry("id", "55555555-5555-5555-5555-555555555555")
        .containsEntry("name", "file child id 5");
  }

  @DisplayName(
      """
    Given an existing folder with five nodes inside, a valid public link associated, a limit of
    six elements per page: the findNodes should return the first page containing five nodes ordered
    by category and alphabetically and a null page_token since there is no more pages to fetch""")
  @Test
  void givenAnExistingFolderAndAValidPublicLinkTheFindNodesShouldReturnTheOnlyPageExisting() {
    // Given
    createFolderTree();
    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addLink(
            "54ef41f2-8edf-4023-8b70-b29441a8e8b0",
            "00000000-0000-0000-0000-000000000000",
            "abcd1234abcd1234abcd1234abcd1234",
            Optional.empty(),
            Optional.empty());

    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("findNodes")
            .withString("folder_id", "00000000-0000-0000-0000-000000000000")
            .withInteger("limit", 6)
            .withWantedResultFormat("{ nodes { id name }, page_token }")
            .build();

    final HttpRequest httpRequest = HttpRequest.of("POST", "/public/graphql/", null, bodyPayload);

    // When
    final HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    final Map<String, Object> page =
        TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "findNodes");

    Assertions.assertThat(page.get("page_token")).isNull();

    final List<Map<String, Object>> nodes = (List<Map<String, Object>>) page.get("nodes");

    Assertions.assertThat(nodes).hasSize(5);
    Assertions.assertThat(nodes.get(0))
        .containsEntry("id", "11111111-1111-1111-1111-111111111111")
        .containsEntry("name", "folder child");
    Assertions.assertThat(nodes.get(1))
        .containsEntry("id", "22222222-2222-2222-2222-222222222222")
        .containsEntry("name", "file child id 2");
    Assertions.assertThat(nodes.get(2))
        .containsEntry("id", "33333333-3333-3333-3333-333333333333")
        .containsEntry("name", "file child id 3");
    Assertions.assertThat(nodes.get(3))
        .containsEntry("id", "44444444-4444-4444-4444-444444444444")
        .containsEntry("name", "file child id 4");
    Assertions.assertThat(nodes.get(4))
        .containsEntry("id", "55555555-5555-5555-5555-555555555555")
        .containsEntry("name", "file child id 5");
  }

  @DisplayName(
      """
    Given an existing folder without nodes inside, a valid public link associated: the findNodes
    should return an empty first page and a null page_token""")
  @Test
  void givenAnExistingEmptyFolderAndAValidPublicLinkTheFindNodesShouldReturnAnEmptyFirstPage() {
    // Given
    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(
            new PopulatorNode(
                "00000000-0000-0000-0000-000000000000",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "LOCAL_ROOT",
                "public folder",
                "",
                NodeType.FOLDER,
                "LOCAL_ROOT",
                0L,
                null))
        .addLink(
            "54ef41f2-8edf-4023-8b70-b29441a8e8b0",
            "00000000-0000-0000-0000-000000000000",
            "abcd1234abcd1234abcd1234abcd1234",
            Optional.empty(),
            Optional.empty());

    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("findNodes")
            .withString("folder_id", "00000000-0000-0000-0000-000000000000")
            .withWantedResultFormat("{ nodes { id name }, page_token }")
            .build();

    final HttpRequest httpRequest = HttpRequest.of("POST", "/public/graphql/", null, bodyPayload);

    // When
    final HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    final Map<String, Object> page =
        TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "findNodes");

    Assertions.assertThat(page.get("page_token")).isNull();

    final List<Map<String, Object>> nodes = (List<Map<String, Object>>) page.get("nodes");

    Assertions.assertThat(nodes).isEmpty();
  }

  @DisplayName(
      """
    Given an existing folder without nodes inside, a valid public link associated: the findNodes
    should return an empty first page and a null page_token""")
  @Test
  void
      givenAnExistingFolderAndAnExpiredPublicLinkTheFindNodesShouldReturn200CodeAndAnErrorMessage() {
    // Given
    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(
            new PopulatorNode(
                "00000000-0000-0000-0000-000000000000",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "LOCAL_ROOT",
                "public folder",
                "",
                NodeType.FOLDER,
                "LOCAL_ROOT",
                0L,
                null))
        .addLink(
            "54ef41f2-8edf-4023-8b70-b29441a8e8b0",
            "00000000-0000-0000-0000-000000000000",
            "abcd1234abcd1234abcd1234abcd1234",
            Optional.of(1L),
            Optional.empty());

    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("findNodes")
            .withString("folder_id", "00000000-0000-0000-0000-000000000000")
            .withWantedResultFormat("{ nodes { id name }, page_token }")
            .build();

    final HttpRequest httpRequest = HttpRequest.of("POST", "/public/graphql/", null, bodyPayload);

    // When
    final HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    final List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());

    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("Could not find node with id 00000000-0000-0000-0000-000000000000");
  }

  @DisplayName(
      """
    Given an existing folder with five nodes inside, a not existing public link associated: the
    findNodes should return 200 status code and an error message""")
  @Test
  void
      givenAnExistingFolderAndANotExistingPublicLinkTheFindNodesShouldReturnAn200StatusWithAnErrorMessage() {
    // Given
    createFolderTree();

    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("findNodes")
            .withString("folder_id", "00000000-0000-0000-0000-000000000000")
            .withWantedResultFormat("{ nodes { id name }, page_token }")
            .build();

    final HttpRequest httpRequest = HttpRequest.of("POST", "/public/graphql/", null, bodyPayload);

    // When
    final HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    final List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());

    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("Could not find node with id 00000000-0000-0000-0000-000000000000");
  }

  @Test
  void givenANotExistingFolderTheFindNodesShouldReturnAn200StatusWithAnErrorMessage() {
    // Given
    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("findNodes")
            .withString("folder_id", "00000000-0000-0000-0000-000000000000")
            .withWantedResultFormat("{ nodes { id name }, page_token }")
            .build();

    final HttpRequest httpRequest = HttpRequest.of("POST", "/public/graphql/", null, bodyPayload);

    // When
    final HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    final List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());

    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("Could not find node with id 00000000-0000-0000-0000-000000000000");
  }

  @DisplayName(
      """
    Given an existing folder with five nodes inside, a valid public link associated, another folder
    not public and an hacked page token that is formed to try access a private node: the findNodes
    should return an empty page""")
  @Test
  void givenAnHackedPageTokenTheFindNodesShouldReturnAnEmptyList() {
    // Given
    createFolderTree();

    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addLink(
            "54ef41f2-8edf-4023-8b70-b29441a8e8b0",
            "00000000-0000-0000-0000-000000000000",
            "abcd1234abcd1234abcd1234abcd1234",
            Optional.empty(),
            Optional.empty())
        .addNode(
            new PopulatorNode(
                "77777777-7777-7777-7777-777777777777",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "LOCAL_ROOT",
                "not public folder",
                "",
                NodeType.FOLDER,
                "LOCAL_ROOT",
                0L,
                null))
        .addNode(
            new PopulatorNode(
                "88888888-8888-8888-8888-888888888888",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "77777777-7777-7777-7777-777777777777",
                "folder child",
                "",
                NodeType.FOLDER,
                "LOCAL_ROOT,77777777-7777-7777-7777-777777777777",
                0L,
                null))
        .addNode(
            new PopulatorNode(
                "99999999-9999-9999-9999-999999999999",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "77777777-7777-7777-7777-777777777777",
                "folder child 2",
                "",
                NodeType.FOLDER,
                "LOCAL_ROOT,77777777-7777-7777-7777-777777777777",
                0L,
                null));

    String pageTokenHacked =
        """
    {
      "limit": 1,
      "keywords": [],
      "keySet": "(node_category > 1) OR (node_category = 1 AND LOWER(name)>'folder child') OR (node_category = 1 AND LOWER(name) = 'folder child' AND t0.node_id > '88888888-8888-8888-8888-888888888888')",
      "sort": "NAME_ASC",
      "flagged": null,
      "folderId": "77777777-7777-7777-7777-777777777777",
      "cascade": null,
      "sharedWithMe": null,
      "sharedByMe": null,
      "directShare": null,
      "nodeType": null,
      "ownerId": null
    }""";

    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("findNodes")
            .withString("folder_id", "00000000-0000-0000-0000-000000000000")
            .withInteger("limit", 1)
            .withString(
                "page_token", Base64.getEncoder().encodeToString(pageTokenHacked.getBytes()))
            .withWantedResultFormat("{ nodes { id name }, page_token }")
            .build();

    final HttpRequest httpRequest = HttpRequest.of("POST", "/public/graphql/", null, bodyPayload);

    // When
    final HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    final Map<String, Object> page =
        TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "findNodes");

    Assertions.assertThat(page.get("page_token")).isNull();

    final List<Map<String, Object>> nodes = (List<Map<String, Object>>) page.get("nodes");

    Assertions.assertThat(nodes).isEmpty();
  }
}
