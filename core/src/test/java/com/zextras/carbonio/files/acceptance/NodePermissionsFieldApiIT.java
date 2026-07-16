// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.acceptance;

import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.acceptance.seam.FilesTestApp;
import com.zextras.carbonio.files.acceptance.seam.impl.GuiceNettyFilesTestAppBuilder;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.api.utilities.entities.SimplePopulatorTextFile;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.utilities.http.HttpRequest;
import com.zextras.carbonio.files.utilities.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Task 1.4 of the acceptance coverage-expansion plan: {@code getNode { permissions { ... } } }
 * exercises {@code NodeDataFetcher#getPermissionsNodeFetcher}, which no existing acceptance test
 * touches at all (0/9 lines covered before this file).
 *
 * <p><b>FINDING (overrides the plan's third scenario):</b> the plan asked for "a NONE user still
 * gets an all-false Permissions object, not an error". This is NOT what happens for {@code
 * getNode}. {@code NodeDataFetcher#getNodeFetcher} gates entry with the exact same {@code
 * permissionsChecker.getPermissions(nodeId, requesterId).has(SharePermission.READ_ONLY)} check
 * BEFORE any field (including {@code permissions}) is ever resolved: when the requester has zero
 * relationship to the node (not owner, no share row at all), that top-level check fails and {@code
 * getNode} returns a {@code nodeNotFound} GraphQL error — {@code getPermissionsNodeFetcher} is
 * never invoked, so a "NONE" {@code Permissions} object can never be observed through {@code
 * getNode}. Since {@code can_read}/{@code can_read_link}/{@code can_read_share} all map to {@code
 * ACL#canRead()}, and the top-level gate requires exactly that bit, ANY node successfully returned
 * by {@code getNode} is guaranteed to have those three fields {@code true} — an all-false {@code
 * Permissions} is structurally unreachable via this query, similar to the other dead/defensive
 * paths documented in the plan's §0. This test pins the ACTUAL behaviour (an error, not a
 * Permissions object) instead of the plan's prediction.
 */
class NodePermissionsFieldApiIT {

  static FilesTestApp app;

  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String SHARE_TARGET_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String NO_RELATIONSHIP_USER_ID = "cccccccc-cccc-cccc-cccc-cccccccccccc";
  private static final String NODE_ID = "00000000-0000-0000-0000-000000000001";

  private static final String PERMISSIONS_RESULT_FORMAT =
      "{ id permissions { can_read can_write_file can_write_folder can_delete can_add_version"
          + " can_read_link can_change_link can_share can_read_share can_change_share } }";

  @BeforeAll
  static void init() {
    app =
        GuiceNettyFilesTestAppBuilder.aFilesTestApp()
            .withDatabase()
            .withServiceDiscover()
            .withUserManagement(
                Map.of(
                    "fake-token", REQUESTER_ID,
                    "fake-token-b", SHARE_TARGET_ID,
                    "fake-token-c", NO_RELATIONSHIP_USER_ID))
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

  @SuppressWarnings("unchecked")
  private Map<String, Object> getNodePermissions(String cookie) {
    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("getNode")
            .withString("node_id", NODE_ID)
            .withWantedResultFormat(PERMISSIONS_RESULT_FORMAT)
            .build();

    HttpRequest httpRequest = HttpRequest.of("POST", "/graphql/", cookie, bodyPayload);
    HttpResponse httpResponse = app.send(httpRequest);
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    Map<String, Object> node = TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "getNode");
    return (Map<String, Object>) node.get("permissions");
  }

  @Test
  void givenOwnerGetNodePermissionsShouldReturnAllTruePermissions() {
    // Given
    app.backdoor()
        .populator()
        .addNode(new SimplePopulatorTextFile(NODE_ID, REQUESTER_ID, "owned.txt"));

    // When
    Map<String, Object> permissions = getNodePermissions("ZM_AUTH_TOKEN=fake-token");

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
    app.backdoor()
        .populator()
        .addNode(new SimplePopulatorTextFile(NODE_ID, REQUESTER_ID, "shared.txt"))
        .addShare(NODE_ID, SHARE_TARGET_ID, ACL.SharePermission.READ_ONLY);

    // When
    Map<String, Object> permissions = getNodePermissions("ZM_AUTH_TOKEN=fake-token-b");

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
    app.backdoor()
        .populator()
        .addNode(new SimplePopulatorTextFile(NODE_ID, REQUESTER_ID, "private.txt"));

    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("getNode")
            .withString("node_id", NODE_ID)
            .withWantedResultFormat(PERMISSIONS_RESULT_FORMAT)
            .build();

    HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token-c", bodyPayload);

    // When
    HttpResponse httpResponse = app.send(httpRequest);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors).hasSize(1).containsExactly("Could not find node with id " + NODE_ID);

    Map<String, Object> node = TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "getNode");
    Assertions.assertThat(node).isEmpty();
  }
}
