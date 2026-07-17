// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
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
import com.zextras.carbonio.files.api.utilities.entities.PopulatorNode;
import com.zextras.carbonio.files.api.utilities.entities.SimplePopulatorFolder;
import com.zextras.carbonio.files.api.utilities.entities.SimplePopulatorTextFile;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.utilities.http.HttpRequest;
import com.zextras.carbonio.files.utilities.http.HttpResponse;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;

@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class MoveNodesApiIT {

  static FilesTestApp app;

  @BeforeAll
  static void init() {
    app =
        QuarkusFilesTestAppBuilder.aFilesTestApp()
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

  @Test
  void givenANodeInRootAndANodeInAnotherFolderWithSameNameMovingThemInTheSameFolderShouldRenameTheMovedOne() {
    // Given
    app.backdoor()
        .populator()
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
    final HttpResponse httpResponse = app.send(httpRequest);

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
    app.backdoor()
        .populator()
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
    final HttpResponse httpResponse = app.send(httpRequest);

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
