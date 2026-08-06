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
 * {@code com.zextras.carbonio.files.acceptance.CreateShareApiIT} rewritten as an out-of-process
 * {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}. All 6 methods and their assertions
 * are preserved verbatim; only the seeding mechanism (API calls capturing server-generated ids; the
 * folder/subFolder/grandchild hierarchy is built via three plain {@code seedFolder}/{@code
 * seedFile} calls under the same owner rather than direct-DB {@code PopulatorNode} rows — the
 * production {@code createFolder}/{@code upload} mutations compute the correct {@code ancestor_ids}
 * themselves) and the transport changed.
 */
class CreateShareApiIT extends AbstractFilesIT {

  private static final String OWNER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String TARGET_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String THIRD_USER_ID = "cccccccc-cccc-cccc-cccc-cccccccccccc";
  private static final String OWNER_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String TARGET_COOKIE = "ZM_AUTH_TOKEN=fake-token-b";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", OWNER_ID);
    FilesStackTestResource.getUserManagementService().registerToken("fake-token-b", TARGET_ID);
  }

  private Response createShare(
      String nodeId, String targetUserId, SharePermission permission, String cookie) {
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("createShare")
            .withString("node_id", nodeId)
            .withString("share_target_id", targetUserId)
            .withEnum("permission", permission)
            .withWantedResultFormat("{ created_at }")
            .build();
    return graphql(bodyPayload, cookie);
  }

  private Response getShare(String nodeId, String targetUserId, String cookie) {
    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("getShare")
            .withString("node_id", nodeId)
            .withString("share_target_id", targetUserId)
            .withWantedResultFormat("{ permission }")
            .build();
    return graphql(bodyPayload, cookie);
  }

  @Test
  void givenShareRightsCreateShareShouldSucceed() {
    // Given
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);

    // When
    Response response = createShare(nodeId, TARGET_ID, SharePermission.READ_ONLY, OWNER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(response.getBody().asString())).isEmpty();
    Assertions.assertThat(shareExists(nodeId, TARGET_ID, OWNER_COOKIE)).isTrue();
  }

  @Test
  void givenNoShareRightsCreateShareShouldReturnShareCreationError() {
    // Given — owned by OWNER_ID, never shared with the requester at all (no READ_AND_SHARE)
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);

    // When — a third, unrelated user attempts to create a share on a node they cannot access
    Response response = createShare(nodeId, TARGET_ID, SharePermission.READ_ONLY, TARGET_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("Could not create share for node: " + nodeId + " and user: " + TARGET_ID);
    Assertions.assertThat(shareExists(nodeId, TARGET_ID, OWNER_COOKIE)).isFalse();
  }

  @Test
  void givenTheNodesOwnOwnerAsTargetCreateShareShouldReturnShareCreationError() {
    // Given
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);

    // When — the owner tries to share the node with themselves
    Response response = createShare(nodeId, OWNER_ID, SharePermission.READ_ONLY, OWNER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("Could not create share for node: " + nodeId + " and user: " + OWNER_ID);
    Assertions.assertThat(shareExists(nodeId, OWNER_ID, OWNER_COOKIE)).isFalse();
  }

  @Test
  void givenAFolderWithANestedSubFolderCreateShareShouldCascadeToTheGrandchild() {
    // Given — folder/subFolder/grandchild.txt, all owned by OWNER_ID; sharing the TOP folder must
    // recurse THROUGH the folder-type child (subFolder) to reach the file-type grandchild — the
    // single-level shares used everywhere else in the suite never exercise this recursion branch.
    String topFolderId = seedFolder("topFolder", LOCAL_ROOT, OWNER_COOKIE);
    String subFolderId = seedFolder("subFolder", topFolderId, OWNER_COOKIE);
    String grandchildId =
        seedFile(
            "grandchild.txt",
            subFolderId,
            "content".getBytes(StandardCharsets.UTF_8),
            OWNER_COOKIE);

    // When
    Response response =
        createShare(topFolderId, TARGET_ID, SharePermission.READ_ONLY, OWNER_COOKIE);

    // Then — the share cascaded all the way down to the grandchild file
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(shareExists(topFolderId, TARGET_ID, OWNER_COOKIE)).isTrue();
    Assertions.assertThat(shareExists(subFolderId, TARGET_ID, OWNER_COOKIE)).isTrue();
    Assertions.assertThat(shareExists(grandchildId, TARGET_ID, OWNER_COOKIE)).isTrue();
  }

  @Test
  void givenNoPermissionGetShareShouldReturnShareNotFoundError() {
    // Given — a real share exists, but the CALLER has no rights to read it at all
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    seedShare(nodeId, THIRD_USER_ID, SharePermission.READ_ONLY, OWNER_COOKIE);

    // When — TARGET_ID has no share/permission on this node at all
    Response response = getShare(nodeId, THIRD_USER_ID, TARGET_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("Could not find share for node: " + nodeId + " and user " + THIRD_USER_ID);
  }

  @Test
  void givenAFolderWithASharedChildDeleteSharesOnTheFolderShouldCascadeDeleteToTheChild() {
    // Given — folder shared to TARGET_ID (creating a DIRECT share on the folder AND an INHERITED
    // one on its child via createShareFetcher's own cascadeUpsertShare); deleting the share on the
    // FOLDER must cascade the deletion down to the child too (deleteSharesFetcher's
    // `getNodeType() == FOLDER` branch, unreached by DeleteSharesApiIT which only ever uses files).
    String folderId = seedFolder("folder", LOCAL_ROOT, OWNER_COOKIE);
    String childId =
        seedFile("child.txt", folderId, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);

    Response createResponse =
        createShare(folderId, TARGET_ID, SharePermission.READ_ONLY, OWNER_COOKIE);
    Assertions.assertThat(createResponse.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(shareExists(childId, TARGET_ID, OWNER_COOKIE))
        .as("the child must have inherited the share before we can prove cascade-delete removes it")
        .isTrue();

    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("deleteShares")
            .withString("node_id", folderId)
            .withListOfStrings("share_target_ids", new String[] {TARGET_ID})
            .withWantedResultFormat("")
            .build();

    // When
    Response response = graphql(bodyPayload, OWNER_COOKIE);

    // Then — both the folder's own share AND the child's inherited share are gone
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(shareExists(folderId, TARGET_ID, OWNER_COOKIE)).isFalse();
    Assertions.assertThat(shareExists(childId, TARGET_ID, OWNER_COOKIE)).isFalse();
  }
}
