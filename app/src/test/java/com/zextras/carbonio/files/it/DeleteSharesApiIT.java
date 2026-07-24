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
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code com.zextras.carbonio.files.acceptance.DeleteSharesApiIT} rewritten as an out-of-process
 * {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}. All 5 methods and their assertions
 * are preserved verbatim; only the seeding mechanism (API calls capturing server-generated ids;
 * fixed tokens {@code fake-token-account-for-sharing}/{@code -2} were renamed to the suite-wide
 * {@code fake-token-b}/{@code fake-token-c} convention documented on {@link AbstractFilesIT} — the
 * user ids themselves are unchanged) and the transport changed.
 */
class DeleteSharesApiIT extends AbstractFilesIT {

  private static final String OWNER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String TARGET_B_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String TARGET_C_ID = "cccccccc-cccc-cccc-cccc-cccccccccccc";
  private static final String OWNER_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String TARGET_B_COOKIE = "ZM_AUTH_TOKEN=fake-token-b";
  private static final String TARGET_C_COOKIE = "ZM_AUTH_TOKEN=fake-token-c";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", OWNER_ID);
    FilesStackTestResource.getUserManagementService().registerToken("fake-token-b", TARGET_B_ID);
    FilesStackTestResource.getUserManagementService().registerToken("fake-token-c", TARGET_C_ID);
  }

  @SuppressWarnings("unchecked")
  private List<String> deletedIds(Response response) {
    return (List<String>)
        TestUtils.jsonResponseToValue(response.getBody().asString(), "deleteShares").orElse(List.of());
  }

  private Response deleteShares(String nodeId, String[] targetUserIds, String cookie) {
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("deleteShares")
            .withString("node_id", nodeId)
            .withListOfStrings("share_target_ids", targetUserIds)
            .withWantedResultFormat("")
            .build();
    return graphql(bodyPayload, cookie);
  }

  @Test
  void givenExistingSharesTheDeleteSharesShouldDeleteAllAndReturnTargetIds() {
    // Given
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    seedShare(nodeId, TARGET_B_ID, SharePermission.READ_ONLY, OWNER_COOKIE);
    seedShare(nodeId, TARGET_C_ID, SharePermission.READ_ONLY, OWNER_COOKIE);

    // When
    Response response = deleteShares(nodeId, new String[] {TARGET_B_ID, TARGET_C_ID}, OWNER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(deletedIds(response)).hasSize(2).containsExactly(TARGET_B_ID, TARGET_C_ID);

    Assertions.assertThat(shareExists(nodeId, TARGET_B_ID, OWNER_COOKIE)).isFalse();
    Assertions.assertThat(shareExists(nodeId, TARGET_C_ID, OWNER_COOKIE)).isFalse();
  }

  @Test
  void givenOneNonExistingShareTheDeleteSharesShouldReturnPartialSuccessWithErrors() {
    // Given
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    seedShare(nodeId, TARGET_B_ID, SharePermission.READ_ONLY, OWNER_COOKIE);

    // When
    Response response = deleteShares(nodeId, new String[] {TARGET_B_ID, TARGET_C_ID}, OWNER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(deletedIds(response)).hasSize(1).containsExactly(TARGET_B_ID);

    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("Could not find share for node: " + nodeId + " and user " + TARGET_C_ID);
  }

  @Test
  void givenATargetUserTheDeleteSharesShouldAllowSelfDeletion() {
    // Given
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    seedShare(nodeId, TARGET_B_ID, SharePermission.READ_ONLY, OWNER_COOKIE);

    // When — the target deletes its own share
    Response response = deleteShares(nodeId, new String[] {TARGET_B_ID}, TARGET_B_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(deletedIds(response)).hasSize(1).containsExactly(TARGET_B_ID);
    Assertions.assertThat(shareExists(nodeId, TARGET_B_ID, OWNER_COOKIE)).isFalse();
  }

  @Test
  void givenAUserWithoutPermissionsTheDeleteSharesShouldReturnErrors() {
    // Given
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    seedShare(nodeId, TARGET_B_ID, SharePermission.READ_ONLY, OWNER_COOKIE);

    // When — TARGET_C_ID is not the target and has no share permission
    Response response = deleteShares(nodeId, new String[] {TARGET_B_ID}, TARGET_C_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(deletedIds(response)).isEmpty();

    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors).hasSize(1);
  }

  @Test
  void givenOwnerAsTargetTheDeleteSharesShouldReturnErrorForOwner() {
    // Given
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    seedShare(nodeId, TARGET_B_ID, SharePermission.READ_ONLY, OWNER_COOKIE);

    // When
    Response response = deleteShares(nodeId, new String[] {OWNER_ID, TARGET_B_ID}, OWNER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(deletedIds(response)).hasSize(1).containsExactly(TARGET_B_ID);

    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("Could not find share for node: " + nodeId + " and user " + OWNER_ID);
  }
}
