// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code com.zextras.carbonio.files.acceptance.MoveNodesApiIT} rewritten as an out-of-process
 * {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}. Both methods and their assertions are
 * preserved verbatim; only the seeding mechanism (API calls capturing server-generated ids) and
 * transport changed.
 */
class MoveNodesApiIT extends AbstractFilesIT {

  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String REQUESTER_COOKIE = "ZM_AUTH_TOKEN=fake-token";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", REQUESTER_ID);
  }

  private Response moveNodes(String[] nodeIds, String destinationId, String cookie) {
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("moveNodes")
            .withListOfStrings("node_ids", nodeIds)
            .withString("destination_id", destinationId)
            .withWantedResultFormat("{ id name }")
            .build();
    return graphql(bodyPayload, cookie);
  }

  @Test
  void
      givenANodeInRootAndANodeInAnotherFolderWithSameNameMovingThemInTheSameFolderShouldRenameTheMovedOne() {
    // Given — "name.txt" already sits at LOCAL_ROOT; a second "name.txt" sits inside "folder"
    seedFile("name.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    String folderId = seedFolder("folder", LOCAL_ROOT, REQUESTER_COOKIE);
    String nodeToMoveId =
        seedFile(
            "name.txt", folderId, "content".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);

    // When
    Response response = moveNodes(new String[] {nodeToMoveId}, LOCAL_ROOT, REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Map<String, Object> page =
        TestUtils.jsonResponseToMap(response.getBody().asString(), "moveNodes");
    List<Map<String, Object>> nodes = (List<Map<String, Object>>) page.get("data");

    Assertions.assertThat(nodes).hasSize(1);
    Assertions.assertThat(nodes.get(0))
        .containsEntry("id", nodeToMoveId)
        .containsEntry("name", "name (1)");
  }

  @Test
  void givenANodeInAFolderMovingItInTheSameFolderShouldNotRenameTheNode() {
    // Given
    String nodeId =
        seedFile(
            "second.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);

    // When
    Response response = moveNodes(new String[] {nodeId}, LOCAL_ROOT, REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Map<String, Object> page =
        TestUtils.jsonResponseToMap(response.getBody().asString(), "moveNodes");
    List<Map<String, Object>> nodes = (List<Map<String, Object>>) page.get("data");

    Assertions.assertThat(nodes).hasSize(1);
    Assertions.assertThat(nodes.get(0)).containsEntry("id", nodeId).containsEntry("name", "second");
  }
}
