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
import com.zextras.carbonio.files.api.utilities.entities.SimplePopulatorTextFile;
import com.zextras.carbonio.files.config.FilesConfig;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.LinkRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.utilities.TestFilesConfig;
import com.zextras.carbonio.files.utilities.http.HttpRequest;
import com.zextras.carbonio.files.utilities.http.HttpResponse;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class NewShareNotificationApiIT {

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
    // Create the node to be shared, owned by first user
    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(
            new SimplePopulatorTextFile(
                "00000000-0000-0000-0000-000000000000", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "name.txt"));

    // Share the file with the second user using the createShare mutation
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("createShare")
            .withString("node_id", "00000000-0000-0000-0000-000000000000")
            .withString("share_target_id", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaab")
            .withEnum("permission", ACL.SharePermission.READ_AND_SHARE)
            .withWantedResultFormat("{ created_at }")
            .build();

    HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);

    TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());
  }

  @Test
  void givenANodeCreatingANewShareShouldCreateANotificationForTheReceiver() {
    // Given
    createBaseScenario();

    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("getNotifications")
            .withBoolean("update_last_seen", true)
            .withWantedResultFormat("{ notifications { ... on NewShare { created_at } } }")
            .build();

    HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token-2", bodyPayload);

    // When
    HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    Map<String, Object> page = TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "getNotifications");
    final List<Map<String, Object>> notifications = (List<Map<String, Object>>) page.get("notifications");

    Assertions.assertThat(notifications).hasSize(1);
  }

  @Test
  void givenANodeCreatingAndDisabledNotificationsNoNotificationShouldBeSavedOrReturned() {
    // Given
    ((TestFilesConfig)
        simulator
            .getInjector()
            .getInstance(FilesConfig.class))
        .setAreNotificationsEnabled(false);
    createBaseScenario();

    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("getNotifications")
            .withBoolean("update_last_seen", true)
            .withWantedResultFormat("{ notifications { ... on NewShare { created_at } } }")
            .build();

    HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token-2", bodyPayload);

    // When
    HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    Map<String, Object> page = TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "getNotifications");
    final List<Map<String, Object>> notifications = (List<Map<String, Object>>) page.get("notifications");

    Assertions.assertThat(notifications).hasSize(0);

    //reset
    ((TestFilesConfig)
        simulator
            .getInjector()
            .getInstance(FilesConfig.class))
        .setAreNotificationsEnabled(true);
  }
}
