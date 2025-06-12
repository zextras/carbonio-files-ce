// SPDX-FileCopyrightText: 2025 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.api.notifications;

import com.google.inject.Injector;
import com.zextras.carbonio.files.Simulator;
import com.zextras.carbonio.files.Simulator.SimulatorBuilder;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.api.utilities.DatabasePopulator;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.api.utilities.entities.SimplePopulatorFolder;
import com.zextras.carbonio.files.config.FilesConfig;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.LinkRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.utilities.MockFilesConfig;
import com.zextras.carbonio.files.utilities.http.HttpRequest;
import com.zextras.carbonio.files.utilities.http.HttpResponse;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class RemovedNodeNotificationMoveApiIT {

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
            .withUserManagement(
                Map.of(
                    "fake-token",
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                    "fake-token-2",
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaab"))
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

  private void createBaseScenario() {
    // Create the folder to be shared
    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(
            new SimplePopulatorFolder(
                "00000000-0000-0000-0000-000000000000", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "folder"));

    // Create a node inside it
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("createFolder")
            .withString("destination_id", "00000000-0000-0000-0000-000000000000")
            .withString("name", "other_folder")
            .withWantedResultFormat("{ id }")
            .build();

    HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);

    HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    Map<String, Object> page =
        TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "createFolder");

    String nodeId = (String) page.get("id");

    // Share base folder with second user
    bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("createShare")
            .withString("node_id", "00000000-0000-0000-0000-000000000000")
            .withString("share_target_id", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaab")
            .withEnum("permission", ACL.SharePermission.READ_AND_SHARE)
            .withWantedResultFormat("{ created_at }")
            .build();

    httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);

    TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Move node inside of base node away
    bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("moveNodes")
            .withString("node_ids", nodeId)
            .withString("destination_id", "LOCAL_ROOT")
            .withWantedResultFormat("{ id }")
            .build();

    httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);

    httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
  }

  @Test
  void givenANodeRemovalByMoveOnASharedDirectoryItShouldCreateANotificationForTheUsersItHasBeenSharedWith() {
    // Given
    createBaseScenario();

    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("getNotifications")
            .withBoolean("update_last_seen", true)
            .withWantedResultFormat("{ notifications { ... on RemovedNode { created_at }, ... on NewShare { created_at } } }")
            .build();

    HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token-2", bodyPayload);

    // When
    HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    Map<String, Object> page =
        TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "getNotifications");

    final List<Map<String, Object>> notifications = (List<Map<String, Object>>) page.get("notifications");

    Assertions.assertThat(notifications).hasSize(2); // One newShare and one removedNode
  }

  @Test
  void givenANodeRemovalByMoveOnASharedDirectoryAndDisabledNotificationsNoNotificationShouldBeSavedOrReturned() {
    // Given
    ((MockFilesConfig)
        simulator
            .getInjector()
            .getInstance(FilesConfig.class))
        .setAreNotificationsEnabled(false);
    createBaseScenario();

    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("getNotifications")
            .withBoolean("update_last_seen", true)
            .withWantedResultFormat("{ notifications { ... on RemovedNode { created_at }, ... on NewShare { created_at } } }")
            .build();

    HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token-2", bodyPayload);

    // When
    HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    Map<String, Object> page =
        TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "getNotifications");

    final List<Map<String, Object>> notifications = (List<Map<String, Object>>) page.get("notifications");

    Assertions.assertThat(notifications).hasSize(0);

    //reset
    ((MockFilesConfig)
        simulator
            .getInjector()
            .getInstance(FilesConfig.class))
        .setAreNotificationsEnabled(true);
  }
}
