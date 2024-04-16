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
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
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

class FindNodesApiIT {

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
                "00000000-0000-0000-0000-000000000001", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", 1L))
        .addNode(
            new SimplePopulatorTextFile(
                "00000000-0000-0000-0000-000000000002", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", 2L))
        .addNode(
            new SimplePopulatorTextFile(
                "00000000-0000-0000-0000-000000000003",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                3L));
  }

  @Test
  void givenFilesOnRootSearchWithSortNameAscShouldReturnCorrectlySortedNodes() {
    // Given
    createNodesDifferentNames();
    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("findNodes")
            .withString("folder_id", "LOCAL_ROOT")
            .withBoolean("cascade", true)
            .withEnum("sort", NodeSort.NAME_ASC)
            .withInteger("limit", 5)
            .withWantedResultFormat("{ nodes { id name }, page_token }")
            .build();

    final HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);

    // When
    final HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    final Map<String, Object> page =
        TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "findNodes");

    final List<Map<String, Object>> nodes = (List<Map<String, Object>>) page.get("nodes");

    Assertions.assertThat(nodes).hasSize(5);
    // folders always on top
    Assertions.assertThat(nodes.get(0))
        .containsEntry("id", "10000000-0000-0000-0000-000000000001")
        .containsEntry("name", "folderA");
    Assertions.assertThat(nodes.get(1))
        .containsEntry("id", "10000000-0000-0000-0000-000000000002")
        .containsEntry("name", "folderB");
    Assertions.assertThat(nodes.get(2))
        .containsEntry("id", "00000000-0000-0000-0000-000000000001")
        .containsEntry("name", "aaa");
    Assertions.assertThat(nodes.get(3))
        .containsEntry("id", "00000000-0000-0000-0000-000000000002")
        .containsEntry("name", "bbb");
    Assertions.assertThat(nodes.get(4))
        .containsEntry("id", "00000000-0000-0000-0000-000000000003")
        .containsEntry("name", "ccc");
  }

  @Test
  void givenFilesOnRootSearchWithSortNameDescShouldReturnCorrectlySortedNodes() {
    // Given
    createNodesDifferentNames();
    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("findNodes")
            .withString("folder_id", "LOCAL_ROOT")
            .withBoolean("cascade", true)
            .withEnum("sort", NodeSort.NAME_DESC)
            .withInteger("limit", 5)
            .withWantedResultFormat("{ nodes { id name }, page_token }")
            .build();

    final HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);

    // When
    final HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    final Map<String, Object> page =
        TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "findNodes");

    final List<Map<String, Object>> nodes = (List<Map<String, Object>>) page.get("nodes");

    Assertions.assertThat(nodes).hasSize(5);
    // folders always on top
    Assertions.assertThat(nodes.get(1))
        .containsEntry("id", "10000000-0000-0000-0000-000000000001")
        .containsEntry("name", "folderA");
    Assertions.assertThat(nodes.get(0))
        .containsEntry("id", "10000000-0000-0000-0000-000000000002")
        .containsEntry("name", "folderB");
    Assertions.assertThat(nodes.get(4))
        .containsEntry("id", "00000000-0000-0000-0000-000000000001")
        .containsEntry("name", "aaa");
    Assertions.assertThat(nodes.get(3))
        .containsEntry("id", "00000000-0000-0000-0000-000000000002")
        .containsEntry("name", "bbb");
    Assertions.assertThat(nodes.get(2))
        .containsEntry("id", "00000000-0000-0000-0000-000000000003")
        .containsEntry("name", "ccc");
  }

  @Test
  void givenFilesOnRootSearchWithSortSizeAscShouldReturnCorrectlySortedNodes() {
    // Given
    createNodesDifferentSizes();
    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("findNodes")
            .withString("folder_id", "LOCAL_ROOT")
            .withBoolean("cascade", true)
            .withEnum("sort", NodeSort.SIZE_ASC)
            .withInteger("limit", 5)
            .withWantedResultFormat("{ nodes { id name }, page_token }")
            .build();

    final HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);

    // When
    final HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    final Map<String, Object> page =
        TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "findNodes");

    final List<Map<String, Object>> nodes = (List<Map<String, Object>>) page.get("nodes");

    Assertions.assertThat(nodes).hasSize(5);
    // folders have size 0 and are on top
    Assertions.assertThat(nodes.get(0))
        .containsEntry("id", "10000000-0000-0000-0000-000000000001")
        .containsEntry("name", "folder");
    Assertions.assertThat(nodes.get(1))
        .containsEntry("id", "10000000-0000-0000-0000-000000000002")
        .containsEntry("name", "folder");
    Assertions.assertThat(nodes.get(2))
        .containsEntry("id", "00000000-0000-0000-0000-000000000001")
        .containsEntry("name", "fake");
    Assertions.assertThat(nodes.get(3))
        .containsEntry("id", "00000000-0000-0000-0000-000000000002")
        .containsEntry("name", "fake");
    Assertions.assertThat(nodes.get(4))
        .containsEntry("id", "00000000-0000-0000-0000-000000000003")
        .containsEntry("name", "fake");
  }

  @Test
  void givenFilesOnRootSearchWithSortSizeDescShouldReturnCorrectlySortedNodes() {
    // Given
    createNodesDifferentSizes();
    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("findNodes")
            .withString("folder_id", "LOCAL_ROOT")
            .withBoolean("cascade", true)
            .withEnum("sort", NodeSort.SIZE_DESC)
            .withInteger("limit", 5)
            .withWantedResultFormat("{ nodes { id name }, page_token }")
            .build();

    final HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);

    // When
    final HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    final Map<String, Object> page =
        TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "findNodes");

    final List<Map<String, Object>> nodes = (List<Map<String, Object>>) page.get("nodes");

    Assertions.assertThat(nodes).hasSize(5);
    // folders have size 0 so they are always on bottom with size desc sorting
    Assertions.assertThat(nodes.get(3))
        .containsEntry("id", "10000000-0000-0000-0000-000000000001")
        .containsEntry("name", "folder");
    Assertions.assertThat(nodes.get(4))
        .containsEntry("id", "10000000-0000-0000-0000-000000000002")
        .containsEntry("name", "folder");
    Assertions.assertThat(nodes.get(2))
        .containsEntry("id", "00000000-0000-0000-0000-000000000001")
        .containsEntry("name", "fake");
    Assertions.assertThat(nodes.get(1))
        .containsEntry("id", "00000000-0000-0000-0000-000000000002")
        .containsEntry("name", "fake");
    Assertions.assertThat(nodes.get(0))
        .containsEntry("id", "00000000-0000-0000-0000-000000000003")
        .containsEntry("name", "fake");
  }

  @Test
  void givenFilesOnRootSearchWithSortLastUpdateAscShouldReturnCorrectlySortedNodes() {
    // Given
    createNodesDifferentNames();
    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("findNodes")
            .withString("folder_id", "LOCAL_ROOT")
            .withBoolean("cascade", true)
            .withEnum("sort", NodeSort.UPDATED_AT_ASC)
            .withInteger("limit", 5)
            .withWantedResultFormat("{ nodes { id name }, page_token }")
            .build();

    final HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);

    // When
    final HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    final Map<String, Object> page =
        TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "findNodes");

    final List<Map<String, Object>> nodes = (List<Map<String, Object>>) page.get("nodes");

    Assertions.assertThat(nodes).hasSize(5);
    // folders always on top
    Assertions.assertThat(nodes.get(0))
        .containsEntry("id", "10000000-0000-0000-0000-000000000001")
        .containsEntry("name", "folderA");
    Assertions.assertThat(nodes.get(1))
        .containsEntry("id", "10000000-0000-0000-0000-000000000002")
        .containsEntry("name", "folderB");
    Assertions.assertThat(nodes.get(2))
        .containsEntry("id", "00000000-0000-0000-0000-000000000001")
        .containsEntry("name", "aaa");
    Assertions.assertThat(nodes.get(3))
        .containsEntry("id", "00000000-0000-0000-0000-000000000002")
        .containsEntry("name", "bbb");
    Assertions.assertThat(nodes.get(4))
        .containsEntry("id", "00000000-0000-0000-0000-000000000003")
        .containsEntry("name", "ccc");
  }

  @Test
  void givenFilesOnRootSearchWithSortLastUpdateDescShouldReturnCorrectlySortedNodes() { // recents
    // Given
    createNodesDifferentNames();
    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("findNodes")
            .withString("folder_id", "LOCAL_ROOT")
            .withBoolean("cascade", true)
            .withEnum("sort", NodeSort.UPDATED_AT_DESC)
            .withInteger("limit", 5)
            .withWantedResultFormat("{ nodes { id name }, page_token }")
            .build();

    final HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);

    // When
    final HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    final Map<String, Object> page =
        TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "findNodes");

    final List<Map<String, Object>> nodes = (List<Map<String, Object>>) page.get("nodes");

    Assertions.assertThat(nodes).hasSize(5);
    // folders always on top
    Assertions.assertThat(nodes.get(1))
        .containsEntry("id", "10000000-0000-0000-0000-000000000001")
        .containsEntry("name", "folderA");
    Assertions.assertThat(nodes.get(0))
        .containsEntry("id", "10000000-0000-0000-0000-000000000002")
        .containsEntry("name", "folderB");
    Assertions.assertThat(nodes.get(4))
        .containsEntry("id", "00000000-0000-0000-0000-000000000001")
        .containsEntry("name", "aaa");
    Assertions.assertThat(nodes.get(3))
        .containsEntry("id", "00000000-0000-0000-0000-000000000002")
        .containsEntry("name", "bbb");
    Assertions.assertThat(nodes.get(2))
        .containsEntry("id", "00000000-0000-0000-0000-000000000003")
        .containsEntry("name", "ccc");
  }

  @Test
  void givenFilesOnRootSearchWithFlaggedShouldReturnFlaggedNodes() {
    // Given
    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(
            new SimplePopulatorTextFile(
                "00000000-0000-0000-0000-000000000001",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "flagged.txt"))
        .addFlag("00000000-0000-0000-0000-000000000001", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        .addNode(
            new SimplePopulatorTextFile(
                "00000000-0000-0000-0000-000000000002",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "not_flagged.txt"));
    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("findNodes")
            .withString("folder_id", "LOCAL_ROOT")
            .withBoolean("cascade", true)
            .withEnum("sort", NodeSort.NAME_ASC)
            .withInteger("limit", 5)
            .withBoolean("flagged", true)
            .withWantedResultFormat("{ nodes { id name }, page_token }")
            .build();

    final HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);

    // When
    final HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    final Map<String, Object> page =
        TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "findNodes");

    final List<Map<String, Object>> nodes = (List<Map<String, Object>>) page.get("nodes");

    Assertions.assertThat(nodes).hasSize(1);
    Assertions.assertThat(nodes.get(0))
        .containsEntry("id", "00000000-0000-0000-0000-000000000001")
        .containsEntry("name", "flagged");
  }

  @Test
  void givenFilesOnRootSearchSharedByMeShouldReturnSharedByMeNodes() {
    // Given
    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(
            new SimplePopulatorTextFile(
                "00000000-0000-0000-0000-000000000001",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "shared_by_me.txt"))
        .addShare(
            "00000000-0000-0000-0000-000000000001",
            "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaab",
            ACL.SharePermission.READ_AND_SHARE)
        .addNode(
            new SimplePopulatorTextFile(
                "00000000-0000-0000-0000-000000000002",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "not_shared_by_me.txt"));
    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("findNodes")
            .withString("folder_id", "LOCAL_ROOT")
            .withBoolean("cascade", true)
            .withEnum("sort", NodeSort.NAME_ASC)
            .withInteger("limit", 5)
            .withBoolean("shared_by_me", true)
            .withBoolean("direct_share", true)
            .withWantedResultFormat("{ nodes { id name }, page_token }")
            .build();

    final HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);

    // When
    final HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    final Map<String, Object> page =
        TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "findNodes");

    final List<Map<String, Object>> nodes = (List<Map<String, Object>>) page.get("nodes");

    Assertions.assertThat(nodes).hasSize(1);
    Assertions.assertThat(nodes.get(0))
        .containsEntry("id", "00000000-0000-0000-0000-000000000001")
        .containsEntry("name", "shared_by_me");
  }

  @Test
  void givenFilesOnRootSearchSharedWithMeShouldReturnSharedWithMeNodes() {
    // Given
    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(
            new SimplePopulatorTextFile(
                "00000000-0000-0000-0000-000000000001",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "shared_with_me.txt"))
        .addShare(
            "00000000-0000-0000-0000-000000000001",
            "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
            ACL.SharePermission.READ_AND_SHARE)
        .addNode(
            new SimplePopulatorTextFile(
                "00000000-0000-0000-0000-000000000002",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "not_shared_with_me.txt"));
    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("findNodes")
            .withString("folder_id", "LOCAL_ROOT")
            .withBoolean("cascade", true)
            .withEnum("sort", NodeSort.NAME_ASC)
            .withInteger("limit", 5)
            .withBoolean("shared_with_me", true)
            .withBoolean("direct_share", true)
            .withWantedResultFormat("{ nodes { id name }, page_token }")
            .build();

    final HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);

    // When
    final HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    final Map<String, Object> page =
        TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "findNodes");

    final List<Map<String, Object>> nodes = (List<Map<String, Object>>) page.get("nodes");

    Assertions.assertThat(nodes).hasSize(1);
    Assertions.assertThat(nodes.get(0))
        .containsEntry("id", "00000000-0000-0000-0000-000000000001")
        .containsEntry("name", "shared_with_me");
  }

  @Test
  void givenFilesOnTrashSearchTrashBinShouldReturnTrashedNodes() {
    // Given
    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(
            new SimplePopulatorTextFile(
                "00000000-0000-0000-0000-000000000001",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "trashed.txt"))
        .addNodeToTrash("00000000-0000-0000-0000-000000000001", "LOCAL_ROOT")
        .addNode(
            new SimplePopulatorTextFile(
                "00000000-0000-0000-0000-000000000002",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "not_trashed.txt"));
    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("findNodes")
            .withString("folder_id", "TRASH_ROOT")
            .withBoolean("cascade", false)
            .withEnum("sort", NodeSort.NAME_ASC)
            .withInteger("limit", 5)
            .withWantedResultFormat("{ nodes { id name }, page_token }")
            .build();

    final HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);

    // When
    final HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    final Map<String, Object> page =
        TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "findNodes");

    final List<Map<String, Object>> nodes = (List<Map<String, Object>>) page.get("nodes");

    Assertions.assertThat(nodes).hasSize(1);
    Assertions.assertThat(nodes.get(0))
        .containsEntry("id", "00000000-0000-0000-0000-000000000001")
        .containsEntry("name", "trashed");
  }

  @Test
  void givenExistingFilesTrashSearchSharedWithMeInTrashBinShouldReturnSharedTrashedNodes() {
    // Given
    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(
            new SimplePopulatorTextFile(
                "00000000-0000-0000-0000-000000000001",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "trashed.txt"))
        .addShare(
            "00000000-0000-0000-0000-000000000001",
            "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
            ACL.SharePermission.READ_AND_SHARE)
        .addNodeToTrash("00000000-0000-0000-0000-000000000001", "LOCAL_ROOT")
        .addNode(
            new SimplePopulatorTextFile(
                "00000000-0000-0000-0000-000000000002",
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "not_trashed.txt"));
    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("findNodes")
            .withString("folder_id", "TRASH_ROOT")
            .withBoolean("cascade", false)
            .withEnum("sort", NodeSort.NAME_ASC)
            .withInteger("limit", 5)
            .withBoolean("shared_with_me", true)
            .withWantedResultFormat("{ nodes { id name }, page_token }")
            .build();

    final HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);

    // When
    final HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    final Map<String, Object> page =
        TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "findNodes");

    final List<Map<String, Object>> nodes = (List<Map<String, Object>>) page.get("nodes");

    Assertions.assertThat(nodes).hasSize(1);
    Assertions.assertThat(nodes.get(0))
        .containsEntry("id", "00000000-0000-0000-0000-000000000001")
        .containsEntry("name", "trashed");
  }

  @Test
  void givenFilesOnRootSearchByKeywordsShouldReturnCorrectNodes() {
    // Given
    createNodesDifferentNames();
    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("findNodes")
            .withString("folder_id", "LOCAL_ROOT")
            .withBoolean("cascade", true)
            .withEnum("sort", NodeSort.NAME_ASC)
            .withInteger("limit", 5)
            .withListOfStrings("keywords", new String[] {"a"})
            .withWantedResultFormat("{ nodes { id name }, page_token }")
            .build();

    final HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);

    // When
    final HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    final Map<String, Object> page =
        TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "findNodes");

    final List<Map<String, Object>> nodes = (List<Map<String, Object>>) page.get("nodes");

    Assertions.assertThat(nodes).hasSize(2);
    // folders always on top
    Assertions.assertThat(nodes.get(0))
        .containsEntry("id", "10000000-0000-0000-0000-000000000001")
        .containsEntry("name", "folderA");
    Assertions.assertThat(nodes.get(1))
        .containsEntry("id", "00000000-0000-0000-0000-000000000001")
        .containsEntry("name", "aaa");
  }
}
