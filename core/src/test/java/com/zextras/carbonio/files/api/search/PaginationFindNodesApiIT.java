// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.api.search;

import com.google.inject.Injector;
import com.zextras.carbonio.files.Simulator;
import com.zextras.carbonio.files.Simulator.SimulatorBuilder;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.api.utilities.DatabasePopulator;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.api.utilities.entities.SimplePopulatorFolder;
import com.zextras.carbonio.files.api.utilities.entities.SimplePopulatorTextFile;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.NodeSort;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.LinkRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.utilities.http.HttpRequest;
import com.zextras.carbonio.files.utilities.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/*
 * This test class mimics findNodes tests but only checks if the pagination is correct and has been created
 * to avoid checking two things in the same test class, so findNodesApi will only check if the search returns correct
 * values without pagination and PaginationFindNodes will only check if those values are paginated correctly.
 * Obviously if the search is broken so will be the pagination since paginated values are returned based on the
 * search criteria.
 */
class PaginationFindNodesApiIT {

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
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                    "fake-token-account-for-sharing",
                    "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"))
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

  void createNodesDifferentNames() {
    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(
            new SimplePopulatorFolder(
                "10000000-0000-0000-0000-000000000001",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "folderA"))
        .addNode(
            new SimplePopulatorFolder(
                "10000000-0000-0000-0000-000000000002",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "folderB"))
        .addNode(
            new SimplePopulatorTextFile(
                "00000000-0000-0000-0000-000000000001",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "aaa.txt"))
        .addNode(
            new SimplePopulatorTextFile(
                "00000000-0000-0000-0000-000000000002",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "bbb.txt"))
        .addNode(
            new SimplePopulatorTextFile(
                "00000000-0000-0000-0000-000000000003",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "ccc.txt"));
  }

  void createNodesDifferentSizes() { // files with same name, to really be sure it's the size that's
    // being compared
    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(
            new SimplePopulatorFolder(
                "10000000-0000-0000-0000-000000000001", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"))
        .addNode(
            new SimplePopulatorFolder(
                "10000000-0000-0000-0000-000000000002", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"))
        .addNode(
            new SimplePopulatorTextFile(
                "00000000-0000-0000-0000-000000000001", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", 0L))
        .addNode(
            new SimplePopulatorTextFile(
                "00000000-0000-0000-0000-000000000002", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", 1L))
        .addNode(
            new SimplePopulatorTextFile(
                "00000000-0000-0000-0000-000000000003",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                2L));
  }

  @Test
  void givenFilesOnRootSearchWithSortNameAscShouldReturnCorrectlyPaginatedNodes() {
    // Given
    createNodesDifferentNames();
    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("findNodes")
            .withString("folder_id", "LOCAL_ROOT")
            .withBoolean("cascade", true)
            .withEnum("sort", NodeSort.NAME_ASC)
            .withInteger("limit", 4)
            .withWantedResultFormat("{ nodes { id name }, page_token }")
            .build();

    final HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);

    final HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    final Map<String, Object> page =
        TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "findNodes");
    final String pageToken = (String) page.get("page_token");

    String bodyPayloadPage =
        GraphqlCommandBuilder.aQueryBuilder("findNodes")
            .withString("folder_id", "LOCAL_ROOT")
            .withBoolean("cascade", true)
            .withEnum("sort", NodeSort.NAME_ASC)
            .withInteger("limit", 1)
            .withString("page_token", pageToken)
            .withWantedResultFormat("{ nodes { id name }, page_token }")
            .build();

    final HttpRequest httpRequestPage =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayloadPage);

    // When
    final HttpResponse httpResponsePage =
        TestUtils.sendRequest(httpRequestPage, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponsePage.getStatus()).isEqualTo(200);

    final Map<String, Object> secondPage =
        TestUtils.jsonResponseToMap(httpResponsePage.getBodyPayload(), "findNodes");

    final List<Map<String, Object>> nodes = (List<Map<String, Object>>) secondPage.get("nodes");

    Assertions.assertThat(nodes).hasSize(1);
    Assertions.assertThat(nodes.get(0))
        .containsEntry("id", "00000000-0000-0000-0000-000000000003")
        .containsEntry("name", "ccc");
  }

  @Test
  void givenFilesOnRootSearchWithSortNameDescShouldReturnCorrectlyPaginatedNodes() {
    // Given
    createNodesDifferentNames();
    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("findNodes")
            .withString("folder_id", "LOCAL_ROOT")
            .withBoolean("cascade", true)
            .withEnum("sort", NodeSort.NAME_DESC)
            .withInteger("limit", 4)
            .withWantedResultFormat("{ nodes { id name }, page_token }")
            .build();

    final HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);

    final HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    final Map<String, Object> page =
        TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "findNodes");
    final String pageToken = (String) page.get("page_token");

    String bodyPayloadPage =
        GraphqlCommandBuilder.aQueryBuilder("findNodes")
            .withString("folder_id", "LOCAL_ROOT")
            .withBoolean("cascade", true)
            .withEnum("sort", NodeSort.NAME_DESC)
            .withInteger("limit", 1)
            .withString("page_token", pageToken)
            .withWantedResultFormat("{ nodes { id name }, page_token }")
            .build();

    final HttpRequest httpRequestPage =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayloadPage);

    // When
    final HttpResponse httpResponsePage =
        TestUtils.sendRequest(httpRequestPage, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponsePage.getStatus()).isEqualTo(200);

    final Map<String, Object> secondPage =
        TestUtils.jsonResponseToMap(httpResponsePage.getBodyPayload(), "findNodes");

    final List<Map<String, Object>> nodes = (List<Map<String, Object>>) secondPage.get("nodes");

    Assertions.assertThat(nodes).hasSize(1);
    Assertions.assertThat(nodes.get(0))
        .containsEntry("id", "00000000-0000-0000-0000-000000000001")
        .containsEntry("name", "aaa");
  }

  @Test
  void givenFilesOnRootSearchWithSortSizeAscShouldReturnCorrectlyPaginatedNodes() {
    // Given
    createNodesDifferentSizes();
    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("findNodes")
            .withString("folder_id", "LOCAL_ROOT")
            .withBoolean("cascade", true)
            .withEnum("sort", NodeSort.SIZE_ASC)
            .withInteger("limit", 4)
            .withWantedResultFormat("{ nodes { id name }, page_token }")
            .build();

    final HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);

    final HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    final Map<String, Object> page =
        TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "findNodes");
    final String pageToken = (String) page.get("page_token");

    String bodyPayloadPage =
        GraphqlCommandBuilder.aQueryBuilder("findNodes")
            .withString("folder_id", "LOCAL_ROOT")
            .withBoolean("cascade", true)
            .withEnum("sort", NodeSort.SIZE_ASC)
            .withInteger("limit", 1)
            .withString("page_token", pageToken)
            .withWantedResultFormat("{ nodes { id name }, page_token }")
            .build();

    final HttpRequest httpRequestPage =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayloadPage);

    // When
    final HttpResponse httpResponsePage =
        TestUtils.sendRequest(httpRequestPage, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponsePage.getStatus()).isEqualTo(200);

    final Map<String, Object> secondPage =
        TestUtils.jsonResponseToMap(httpResponsePage.getBodyPayload(), "findNodes");

    final List<Map<String, Object>> nodes = (List<Map<String, Object>>) secondPage.get("nodes");

    Assertions.assertThat(nodes).hasSize(1);
    Assertions.assertThat(nodes.get(0))
        .containsEntry("id", "00000000-0000-0000-0000-000000000003")
        .containsEntry("name", "fake");
  }

  @Test
  void givenFilesOnRootSearchWithSortSizeDescShouldReturnCorrectlyPaginatedNodes() {
    // Given
    createNodesDifferentSizes();
    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("findNodes")
            .withString("folder_id", "LOCAL_ROOT")
            .withBoolean("cascade", true)
            .withEnum("sort", NodeSort.SIZE_DESC)
            .withInteger("limit", 4)
            .withWantedResultFormat("{ nodes { id name }, page_token }")
            .build();

    final HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);

    final HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    final Map<String, Object> page =
        TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "findNodes");
    final String pageToken = (String) page.get("page_token");

    String bodyPayloadPage =
        GraphqlCommandBuilder.aQueryBuilder("findNodes")
            .withString("folder_id", "LOCAL_ROOT")
            .withBoolean("cascade", true)
            .withEnum("sort", NodeSort.SIZE_DESC)
            .withInteger("limit", 1)
            .withString("page_token", pageToken)
            .withWantedResultFormat("{ nodes { id name }, page_token }")
            .build();

    final HttpRequest httpRequestPage =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayloadPage);

    // When
    final HttpResponse httpResponsePage =
        TestUtils.sendRequest(httpRequestPage, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponsePage.getStatus()).isEqualTo(200);

    final Map<String, Object> secondPage =
        TestUtils.jsonResponseToMap(httpResponsePage.getBodyPayload(), "findNodes");

    final List<Map<String, Object>> nodes = (List<Map<String, Object>>) secondPage.get("nodes");

    Assertions.assertThat(nodes).hasSize(1);
    Assertions.assertThat(nodes.get(0))
        .containsEntry("id", "10000000-0000-0000-0000-000000000002")
        .containsEntry("name", "folder");
  }

  @Test
  void givenFilesOnRootSearchWithSortLastUpdateAscShouldReturnCorrectlyPaginatedNodes() {
    // Given
    createNodesDifferentNames();
    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("findNodes")
            .withString("folder_id", "LOCAL_ROOT")
            .withBoolean("cascade", true)
            .withEnum("sort", NodeSort.UPDATED_AT_ASC)
            .withInteger("limit", 4)
            .withWantedResultFormat("{ nodes { id name }, page_token }")
            .build();

    final HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);

    final HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    final Map<String, Object> page =
        TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "findNodes");
    final String pageToken = (String) page.get("page_token");

    String bodyPayloadPage =
        GraphqlCommandBuilder.aQueryBuilder("findNodes")
            .withString("folder_id", "LOCAL_ROOT")
            .withBoolean("cascade", true)
            .withEnum("sort", NodeSort.UPDATED_AT_ASC)
            .withInteger("limit", 1)
            .withString("page_token", pageToken)
            .withWantedResultFormat("{ nodes { id name }, page_token }")
            .build();

    final HttpRequest httpRequestPage =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayloadPage);

    // When
    final HttpResponse httpResponsePage =
        TestUtils.sendRequest(httpRequestPage, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponsePage.getStatus()).isEqualTo(200);

    final Map<String, Object> secondPage =
        TestUtils.jsonResponseToMap(httpResponsePage.getBodyPayload(), "findNodes");

    final List<Map<String, Object>> nodes = (List<Map<String, Object>>) secondPage.get("nodes");

    Assertions.assertThat(nodes).hasSize(1);
    Assertions.assertThat(nodes.get(0))
        .containsEntry("id", "00000000-0000-0000-0000-000000000003")
        .containsEntry("name", "ccc");
  }

  @Test
  void
      givenFilesOnRootSearchWithSortLastUpdateDescShouldReturnCorrectlyPaginatedNodes() { // recents
    // Given
    createNodesDifferentNames();
    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("findNodes")
            .withString("folder_id", "LOCAL_ROOT")
            .withBoolean("cascade", true)
            .withEnum("sort", NodeSort.UPDATED_AT_DESC)
            .withInteger("limit", 4)
            .withWantedResultFormat("{ nodes { id name }, page_token }")
            .build();

    final HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);

    final HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    final Map<String, Object> page =
        TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "findNodes");
    final String pageToken = (String) page.get("page_token");

    String bodyPayloadPage =
        GraphqlCommandBuilder.aQueryBuilder("findNodes")
            .withString("folder_id", "LOCAL_ROOT")
            .withBoolean("cascade", true)
            .withEnum("sort", NodeSort.UPDATED_AT_DESC)
            .withInteger("limit", 1)
            .withString("page_token", pageToken)
            .withWantedResultFormat("{ nodes { id name }, page_token }")
            .build();

    final HttpRequest httpRequestPage =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayloadPage);

    // When
    final HttpResponse httpResponsePage =
        TestUtils.sendRequest(httpRequestPage, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponsePage.getStatus()).isEqualTo(200);

    final Map<String, Object> secondPage =
        TestUtils.jsonResponseToMap(httpResponsePage.getBodyPayload(), "findNodes");

    final List<Map<String, Object>> nodes = (List<Map<String, Object>>) secondPage.get("nodes");

    Assertions.assertThat(nodes).hasSize(1);
    Assertions.assertThat(nodes.get(0))
        .containsEntry("id", "00000000-0000-0000-0000-000000000001")
        .containsEntry("name", "aaa");
  }
}
