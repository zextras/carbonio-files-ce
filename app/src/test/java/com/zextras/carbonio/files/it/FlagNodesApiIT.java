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
 * {@code com.zextras.carbonio.files.acceptance.FlagNodesApiIT} rewritten as an out-of-process
 * {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}. All 4 methods and their assertions
 * are preserved verbatim; only the seeding mechanism (API calls capturing server-generated ids) and
 * transport changed.
 */
class FlagNodesApiIT extends AbstractFilesIT {

  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OTHER_USER_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String REQUESTER_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String OTHER_COOKIE = "ZM_AUTH_TOKEN=fake-token-b";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", REQUESTER_ID);
    FilesStackTestResource.getUserManagementService().registerToken("fake-token-b", OTHER_USER_ID);
  }

  private Response flagNodes(String[] nodeIds, String cookie) {
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("flagNodes")
            .withListOfStrings("node_ids", nodeIds)
            .withBoolean("flag", true)
            .withWantedResultFormat("")
            .build();
    return graphql(bodyPayload, cookie);
  }

  @Test
  void givenANodeFlagNodesShouldFlagIt() {
    // Given
    String nodeId =
        seedFile(
            "aFile.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);

    // When
    Response response = flagNodes(new String[] {nodeId}, REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Map<String, Object> page =
        TestUtils.jsonResponseToMap(response.getBody().asString(), "flagNodes");
    List<String> nodes = (List<String>) page.get("data");

    Assertions.assertThat(nodes).hasSize(1);
    Assertions.assertThat(nodes.get(0)).isEqualTo(nodeId);
  }

  @Test
  void givenANotExistingNodeFlagNodesShouldReturn200WithAnErrorMessage() {
    // Given
    String nonExistentId = "00000000-0000-0000-0000-000000000001";

    // When
    Response response = flagNodes(new String[] {nonExistentId}, REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errorResponse = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errorResponse)
        .hasSize(1)
        .containsExactly(
            "There was a problem while executing requested operation on node: " + nonExistentId);
  }

  @Test
  void givenANodeAndAUserWithoutPermissionsFlagNodesShouldReturn200WithAnErrorMessage() {
    // Given — node owned by a DIFFERENT user, never shared with the requester
    String nodeId =
        seedFile(
            "notMine.txt", LOCAL_ROOT, "notmine".getBytes(StandardCharsets.UTF_8), OTHER_COOKIE);

    // When
    Response response = flagNodes(new String[] {nodeId}, REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errorResponse = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errorResponse)
        .hasSize(1)
        .containsExactly(
            "There was a problem while executing requested operation on node: " + nodeId);
  }

  @Test
  void givenTwoNodesWithOneNotExistingNodeFlagNodesShouldReturn200WithAnErrorMessage() {
    // Given
    String nodeId =
        seedFile(
            "aFile.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    String nonExistentId = "00000000-0000-0000-0000-000000000004";

    // When
    Response response = flagNodes(new String[] {nodeId, nonExistentId}, REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errorResponse = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errorResponse)
        .hasSize(1)
        .containsExactly(
            "There was a problem while executing requested operation on node: " + nonExistentId);
  }
}
