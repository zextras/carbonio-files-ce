// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code com.zextras.carbonio.files.acceptance.NodePermissionsFieldApiIT} rewritten as an
 * out-of-process {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}. All 3 methods and
 * their assertions (including the class-level FINDING that a zero-relationship user gets a
 * {@code nodeNotFound} error, never an all-false {@code Permissions} object) are preserved
 * verbatim; only the seeding mechanism (API calls capturing server-generated ids) and transport
 * changed.
 */
class NodePermissionsFieldApiIT extends AbstractFilesIT {

  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String SHARE_TARGET_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String NO_RELATIONSHIP_USER_ID = "cccccccc-cccc-cccc-cccc-cccccccccccc";
  private static final String REQUESTER_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String SHARE_TARGET_COOKIE = "ZM_AUTH_TOKEN=fake-token-b";
  private static final String NO_RELATIONSHIP_COOKIE = "ZM_AUTH_TOKEN=fake-token-c";

  private static final String PERMISSIONS_RESULT_FORMAT =
      "{ id permissions { can_read can_write_file can_write_folder can_delete can_add_version"
          + " can_read_link can_change_link can_share can_read_share can_change_share } }";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", REQUESTER_ID);
    FilesStackTestResource.getUserManagementService().registerToken("fake-token-b", SHARE_TARGET_ID);
    FilesStackTestResource.getUserManagementService().registerToken("fake-token-c", NO_RELATIONSHIP_USER_ID);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> getNodePermissions(String nodeId, String cookie) {
    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("getNode")
            .withString("node_id", nodeId)
            .withWantedResultFormat(PERMISSIONS_RESULT_FORMAT)
            .build();

    Response response = graphql(bodyPayload, cookie);
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);

    Map<String, Object> node = TestUtils.jsonResponseToMap(response.getBody().asString(), "getNode");
    return (Map<String, Object>) node.get("permissions");
  }

  @Test
  void givenOwnerGetNodePermissionsShouldReturnAllTruePermissions() {
    // Given
    String nodeId =
        seedFile("owned.txt", LOCAL_ROOT, "owned".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);

    // When
    Map<String, Object> permissions = getNodePermissions(nodeId, REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(permissions)
        .containsEntry("can_read", true)
        .containsEntry("can_write_file", true)
        .containsEntry("can_write_folder", true)
        .containsEntry("can_delete", true)
        .containsEntry("can_add_version", true)
        .containsEntry("can_read_link", true)
        .containsEntry("can_change_link", true)
        .containsEntry("can_share", true)
        .containsEntry("can_read_share", true)
        .containsEntry("can_change_share", true);
  }

  @Test
  void givenReadOnlyShareTargetGetNodePermissionsShouldReturnOnlyReadPermissionsTrue() {
    // Given
    String nodeId =
        seedFile("shared.txt", LOCAL_ROOT, "shared".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    seedShare(nodeId, SHARE_TARGET_ID, ACL.SharePermission.READ_ONLY, REQUESTER_COOKIE);

    // When
    Map<String, Object> permissions = getNodePermissions(nodeId, SHARE_TARGET_COOKIE);

    // Then — READ_ONLY grants only the READ bit; write/share/delete all false
    Assertions.assertThat(permissions)
        .containsEntry("can_read", true)
        .containsEntry("can_write_file", false)
        .containsEntry("can_write_folder", false)
        .containsEntry("can_delete", false)
        .containsEntry("can_add_version", false)
        .containsEntry("can_read_link", true)
        .containsEntry("can_change_link", false)
        .containsEntry("can_share", false)
        .containsEntry("can_read_share", true)
        .containsEntry("can_change_share", false);
  }

  /**
   * See the class-level FINDING: a user with zero relationship to the node cannot reach the
   * {@code permissions} field at all — {@code getNode} itself fails with {@code nodeNotFound}
   * before any field resolver (including {@code getPermissionsNodeFetcher}) runs.
   */
  @Test
  void givenUserWithNoRelationshipToNodeGetNodeFailsWithNodeNotFoundInsteadOfAllFalsePermissions() {
    // Given
    String nodeId =
        seedFile("private.txt", LOCAL_ROOT, "private".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);

    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("getNode")
            .withString("node_id", nodeId)
            .withWantedResultFormat(PERMISSIONS_RESULT_FORMAT)
            .build();

    // When
    Response response = graphql(bodyPayload, NO_RELATIONSHIP_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors).hasSize(1).containsExactly("Could not find node with id " + nodeId);

    Map<String, Object> node = TestUtils.jsonResponseToMap(response.getBody().asString(), "getNode");
    Assertions.assertThat(node).isEmpty();
  }
}
