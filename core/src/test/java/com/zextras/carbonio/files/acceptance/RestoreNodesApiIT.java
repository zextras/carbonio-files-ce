// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.acceptance;

import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.acceptance.seam.FilesTestApp;
import com.zextras.carbonio.files.acceptance.seam.impl.GuiceNettyFilesTestAppBuilder;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.api.utilities.entities.SimplePopulatorTextFile;
import com.zextras.carbonio.files.utilities.http.HttpRequest;
import com.zextras.carbonio.files.utilities.http.HttpResponse;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class RestoreNodesApiIT {

  static FilesTestApp app;

  @BeforeAll
  static void init() {
    app =
        GuiceNettyFilesTestAppBuilder.aFilesTestApp()
            .withDatabase()
            .withServiceDiscover()
            .withUserManagement( // create a fake token to use in cookie for auth
                Map.of(
                    "fake-token",
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"))
            .build();
  }

  @AfterEach
  void cleanUp() {
    app.backdoor().resetDatabase();
  }

  @AfterAll
  static void cleanUpAll() {
    app.close();
  }

  /**
   * Functionally identical to the pre-migration hand-rolled trashNode() (which loaded the node,
   * flipped its ancestor/parent ids to TRASH_ROOT and called nodeRepository.trashNode(...)
   * directly): {@link com.zextras.carbonio.files.api.utilities.DatabasePopulator#addNodeToTrash}
   * already does exactly this. All nodes in this file are created via {@link
   * SimplePopulatorTextFile}, whose parent is always {@code "LOCAL_ROOT"}.
   */
  private void trashNode(String nodeId) {
    app.backdoor().populator().addNodeToTrash(nodeId, "LOCAL_ROOT");
  }

  @Test
  void givenATrashedNodeRestoreNodesShouldRestoreThatNode() {
    // Given
    app.backdoor()
        .populator()
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
    final HttpResponse httpResponse = app.send(httpRequest);

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
    app.backdoor().populator()
        .addNode(
            new SimplePopulatorTextFile(
                "00000000-0000-0000-0000-000000000000", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));

    trashNode("00000000-0000-0000-0000-000000000000");

    app.backdoor().populator()
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
    final HttpResponse httpResponse = app.send(httpRequest);

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
