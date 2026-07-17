// SPDX-FileCopyrightText: 2025 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.acceptance;

import com.zextras.carbonio.files.FilesStackTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.acceptance.seam.FilesTestApp;
import com.zextras.carbonio.files.acceptance.seam.impl.QuarkusFilesTestAppBuilder;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.api.utilities.entities.SimplePopulatorTextFile;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.utilities.http.HttpRequest;
import com.zextras.carbonio.files.utilities.http.HttpResponse;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class NewShareNotificationApiIT {

  static FilesTestApp app;

  @BeforeAll
  static void init() {
    app =
        QuarkusFilesTestAppBuilder.aFilesTestApp()
            .withDatabase()
            .withServiceDiscover()
            .withUserManagement(
                Map.of(
                    "fake-token",
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                    "fake-token-2",
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaab"))
            .build();
  }

  @AfterEach
  void cleanUp() {
    app.backdoor().resetDatabase();
    app.mocks().reset();
    app.mocks().setNotificationsEnabled(true);
  }

  @AfterAll
  static void cleanUpAll() {
    app.close();
  }

  private void createBaseScenario() {
    // Create the node to be shared, owned by first user
    app.backdoor()
        .populator()
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

    app.send(httpRequest);
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
    HttpResponse httpResponse = app.send(httpRequest);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    Map<String, Object> page = TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "getNotifications");
    final List<Map<String, Object>> notifications = (List<Map<String, Object>>) page.get("notifications");

    Assertions.assertThat(notifications).hasSize(1);
  }

  @Test
  void givenANodeCreatingAndDisabledNotificationsNoNotificationShouldBeSavedOrReturned() {
    // Given
    app.mocks().setNotificationsEnabled(false);
    createBaseScenario();

    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("getNotifications")
            .withBoolean("update_last_seen", true)
            .withWantedResultFormat("{ notifications { ... on NewShare { created_at } } }")
            .build();

    HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token-2", bodyPayload);

    // When
    HttpResponse httpResponse = app.send(httpRequest);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    Map<String, Object> page = TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "getNotifications");
    final List<Map<String, Object>> notifications = (List<Map<String, Object>>) page.get("notifications");

    Assertions.assertThat(notifications).hasSize(0);

    //reset
    app.mocks().setNotificationsEnabled(true);
  }
}
