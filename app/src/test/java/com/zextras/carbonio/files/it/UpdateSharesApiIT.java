// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code com.zextras.carbonio.files.acceptance.UpdateSharesApiIT} rewritten as an out-of-process
 * {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}. All 4 methods and their assertions
 * are preserved verbatim; only the seeding mechanism (API calls capturing server-generated ids;
 * fixed tokens {@code fake-token-account-for-sharing}/{@code -2} were renamed to the suite-wide
 * {@code fake-token-b}/{@code fake-token-c} convention documented on {@link AbstractFilesIT} — the
 * user ids themselves are unchanged) and the transport changed. {@code updateSharesFetcher}
 * iterates {@code share_target_ids} in ARGUMENT order (not DB order) and returns one result per
 * target in that same order, so the response-list index assertions below are unaffected by seeding
 * order.
 */
class UpdateSharesApiIT extends AbstractFilesIT {

  private static final String OWNER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String TARGET_B_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String TARGET_C_ID = "cccccccc-cccc-cccc-cccc-cccccccccccc";
  private static final String OWNER_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String TARGET_B_COOKIE = "ZM_AUTH_TOKEN=fake-token-b";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", OWNER_ID);
    FilesStackTestResource.getUserManagementService().registerToken("fake-token-b", TARGET_B_ID);
    FilesStackTestResource.getUserManagementService().registerToken("fake-token-c", TARGET_C_ID);
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> updateShares(
      String nodeId, String[] targetUserIds, SharePermission permission, String cookie) {
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("updateShares")
            .withString("node_id", nodeId)
            .withListOfStrings("share_target_ids", targetUserIds)
            .withEnum("permission", permission)
            .withWantedResultFormat("{ permission }")
            .build();
    Response response = graphql(bodyPayload, cookie);
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    return TestUtils.jsonResponseToList(response.getBody().asString(), "updateShares");
  }

  @Test
  void givenExistingSharesTheUpdateSharesShouldUpdatePermissionsForAllTargets() {
    // Given
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    seedShare(nodeId, TARGET_B_ID, SharePermission.READ_ONLY, OWNER_COOKIE);
    seedShare(nodeId, TARGET_C_ID, SharePermission.READ_ONLY, OWNER_COOKIE);

    // When
    List<Map<String, Object>> updatedShares =
        updateShares(
            nodeId, new String[] {TARGET_B_ID, TARGET_C_ID}, SharePermission.READ_AND_WRITE, OWNER_COOKIE);

    // Then
    Assertions.assertThat(updatedShares).hasSize(2);
    Assertions.assertThat(updatedShares.get(0)).containsEntry("permission", "READ_AND_WRITE");
    Assertions.assertThat(updatedShares.get(1)).containsEntry("permission", "READ_AND_WRITE");
  }

  @Test
  void givenOneNonExistingShareTheUpdateSharesShouldReturnPartialSuccessWithErrors() {
    // Given
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    seedShare(nodeId, TARGET_B_ID, SharePermission.READ_ONLY, OWNER_COOKIE);

    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("updateShares")
            .withString("node_id", nodeId)
            .withListOfStrings("share_target_ids", new String[] {TARGET_B_ID, TARGET_C_ID})
            .withEnum("permission", SharePermission.READ_AND_WRITE)
            .withWantedResultFormat("{ permission }")
            .build();

    // When
    Response response = graphql(bodyPayload, OWNER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<Map<String, Object>> updatedShares =
        TestUtils.jsonResponseToList(response.getBody().asString(), "updateShares");
    Assertions.assertThat(updatedShares).hasSize(1);
    Assertions.assertThat(updatedShares.get(0)).containsEntry("permission", "READ_AND_WRITE");

    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("Could not find share for node: " + nodeId + " and user " + TARGET_C_ID);
  }

  @Test
  void givenAUserWithoutSharePermissionsTheUpdateSharesShouldReturnErrors() {
    // Given
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    seedShare(nodeId, TARGET_B_ID, SharePermission.READ_ONLY, OWNER_COOKIE);

    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("updateShares")
            .withString("node_id", nodeId)
            .withListOfStrings("share_target_ids", new String[] {TARGET_B_ID})
            .withEnum("permission", SharePermission.READ_AND_WRITE)
            .withWantedResultFormat("{ permission }")
            .build();

    // When — TARGET_B_ID itself has no READ_AND_SHARE on the node, so it cannot update its own share
    Response response = graphql(bodyPayload, TARGET_B_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<Map<String, Object>> updatedShares =
        TestUtils.jsonResponseToList(response.getBody().asString(), "updateShares");
    Assertions.assertThat(updatedShares).isEmpty();

    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors).hasSize(1);
  }

  @Test
  void givenOwnerAsTargetTheUpdateSharesShouldReturnErrorForOwner() {
    // Given
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    seedShare(nodeId, TARGET_B_ID, SharePermission.READ_ONLY, OWNER_COOKIE);

    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("updateShares")
            .withString("node_id", nodeId)
            .withListOfStrings("share_target_ids", new String[] {OWNER_ID, TARGET_B_ID})
            .withEnum("permission", SharePermission.READ_AND_WRITE)
            .withWantedResultFormat("{ permission }")
            .build();

    // When
    Response response = graphql(bodyPayload, OWNER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<Map<String, Object>> updatedShares =
        TestUtils.jsonResponseToList(response.getBody().asString(), "updateShares");
    Assertions.assertThat(updatedShares).hasSize(1);
    Assertions.assertThat(updatedShares.get(0)).containsEntry("permission", "READ_AND_WRITE");

    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("Could not find share for node: " + nodeId + " and user " + OWNER_ID);
  }
}
