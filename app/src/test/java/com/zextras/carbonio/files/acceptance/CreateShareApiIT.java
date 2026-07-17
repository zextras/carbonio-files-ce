// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
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
import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
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
 * NEW canonical file: the {@code createShare}/{@code getShare} queries (bound to {@code
 * ShareDataFetcher#createShareFetcher}/{@code getShareFetcher}) had no dedicated direct coverage —
 * every other {@code *ApiIT} only ever calls {@code createShare} as a happy-path SETUP trigger
 * (always with permission, never targeting the node's own owner, never on a multi-level folder
 * tree), so several real branches were never exercised:
 *
 * <ul>
 *   <li>{@code createShare} permission-denied ({@code READ_AND_SHARE} missing) -&gt; {@code
 *       shareCreationError}.
 *   <li>{@code createShare} targeting the shared node's OWN owner -&gt; {@code shareCreationError}
 *       (self-share is rejected).
 *   <li>{@code createShare} on a folder with a NESTED sub-folder -&gt; {@code
 *       ShareDataFetcher#cascadeUpsertShare}'s {@code getNodeType() == FOLDER} recursion branch,
 *       which every existing single-level-share test never reaches (needs a folder-type CHILD to
 *       recurse into).
 *   <li>{@code getShare} permission-denied -&gt; {@code shareNotfound}.
 *   <li>{@code deleteShares} on a FOLDER (not a file) -&gt; the {@code getNodeType() == FOLDER}
 *       cascade-trigger branch and {@code cascadeDeleteShare}'s own body, neither reached by
 *       {@code DeleteSharesApiIT} (file-only).
 * </ul>
 */
@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class CreateShareApiIT {

  static FilesTestApp app;

  private static final String OWNER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String TARGET_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String OWNER_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String TARGET_COOKIE = "ZM_AUTH_TOKEN=fake-token-b";

  @BeforeAll
  static void init() {
    app =
        QuarkusFilesTestAppBuilder.aFilesTestApp()
            .withDatabase()
            .withServiceDiscover()
            .withUserManagement(
                Map.of(
                    "fake-token", OWNER_ID,
                    "fake-token-b", TARGET_ID))
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

  private HttpResponse createShare(
      String nodeId, String targetUserId, SharePermission permission, String cookie) {
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("createShare")
            .withString("node_id", nodeId)
            .withString("share_target_id", targetUserId)
            .withEnum("permission", permission)
            .withWantedResultFormat("{ created_at }")
            .build();
    return app.send(HttpRequest.of("POST", "/graphql/", cookie, bodyPayload));
  }

  private HttpResponse getShare(String nodeId, String targetUserId, String cookie) {
    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("getShare")
            .withString("node_id", nodeId)
            .withString("share_target_id", targetUserId)
            .withWantedResultFormat("{ permission }")
            .build();
    return app.send(HttpRequest.of("POST", "/graphql/", cookie, bodyPayload));
  }

  @Test
  void givenShareRightsCreateShareShouldSucceed() {
    // Given
    String nodeId = "80000000-0000-0000-0000-000000000001";
    app.backdoor().populator().addNode(new SimplePopulatorTextFile(nodeId, OWNER_ID, "file.txt"));

    // When
    HttpResponse httpResponse =
        createShare(nodeId, TARGET_ID, SharePermission.READ_ONLY, OWNER_COOKIE);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload())).isEmpty();
    Assertions.assertThat(app.backdoor().shareExists(nodeId, TARGET_ID)).isTrue();
  }

  @Test
  void givenNoShareRightsCreateShareShouldReturnShareCreationError() {
    // Given — owned by OWNER_ID, never shared with the requester at all (no READ_AND_SHARE)
    String nodeId = "80000000-0000-0000-0000-000000000002";
    app.backdoor().populator().addNode(new SimplePopulatorTextFile(nodeId, OWNER_ID, "file.txt"));

    // When — a third, unrelated user attempts to create a share on a node they cannot access
    HttpResponse httpResponse =
        createShare(nodeId, TARGET_ID, SharePermission.READ_ONLY, TARGET_COOKIE);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("Could not create share for node: " + nodeId + " and user: " + TARGET_ID);
    Assertions.assertThat(app.backdoor().shareExists(nodeId, TARGET_ID)).isFalse();
  }

  @Test
  void givenTheNodesOwnOwnerAsTargetCreateShareShouldReturnShareCreationError() {
    // Given
    String nodeId = "80000000-0000-0000-0000-000000000003";
    app.backdoor().populator().addNode(new SimplePopulatorTextFile(nodeId, OWNER_ID, "file.txt"));

    // When — the owner tries to share the node with themselves
    HttpResponse httpResponse =
        createShare(nodeId, OWNER_ID, SharePermission.READ_ONLY, OWNER_COOKIE);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("Could not create share for node: " + nodeId + " and user: " + OWNER_ID);
    Assertions.assertThat(app.backdoor().shareExists(nodeId, OWNER_ID)).isFalse();
  }

  @Test
  void givenAFolderWithANestedSubFolderCreateShareShouldCascadeToTheGrandchild() {
    // Given — folder/subFolder/grandchild.txt, all owned by OWNER_ID; sharing the TOP folder must
    // recurse THROUGH the folder-type child (subFolder) to reach the file-type grandchild — the
    // single-level shares used everywhere else in the suite never exercise this recursion branch.
    String topFolderId = "80000000-0000-0000-0000-000000000101";
    String subFolderId = "80000000-0000-0000-0000-000000000102";
    String grandchildId = "80000000-0000-0000-0000-000000000103";
    app.backdoor()
        .populator()
        .addNode(new SimplePopulatorFolder(topFolderId, OWNER_ID, "topFolder"))
        .addNode(
            new PopulatorNode(
                subFolderId, OWNER_ID, OWNER_ID, topFolderId, "subFolder", "",
                NodeType.FOLDER, "LOCAL_ROOT," + topFolderId, 0L, null))
        .addNode(
            new PopulatorNode(
                grandchildId, OWNER_ID, OWNER_ID, subFolderId, "grandchild.txt", "",
                NodeType.TEXT, "LOCAL_ROOT," + topFolderId + "," + subFolderId, 1L, "text/plain"));

    // When
    HttpResponse httpResponse =
        createShare(topFolderId, TARGET_ID, SharePermission.READ_ONLY, OWNER_COOKIE);

    // Then — the share cascaded all the way down to the grandchild file
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Assertions.assertThat(app.backdoor().shareExists(topFolderId, TARGET_ID)).isTrue();
    Assertions.assertThat(app.backdoor().shareExists(subFolderId, TARGET_ID)).isTrue();
    Assertions.assertThat(app.backdoor().shareExists(grandchildId, TARGET_ID)).isTrue();
  }

  @Test
  void givenNoPermissionGetShareShouldReturnShareNotFoundError() {
    // Given — a real share exists, but the CALLER has no rights to read it at all
    String nodeId = "80000000-0000-0000-0000-000000000201";
    String thirdUserId = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    app.backdoor()
        .populator()
        .addNode(new SimplePopulatorTextFile(nodeId, OWNER_ID, "file.txt"))
        .addShare(nodeId, thirdUserId, SharePermission.READ_ONLY);

    // When — TARGET_ID has no share/permission on this node at all
    HttpResponse httpResponse = getShare(nodeId, thirdUserId, TARGET_COOKIE);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("Could not find share for node: " + nodeId + " and user " + thirdUserId);
  }

  @Test
  void givenAFolderWithASharedChildDeleteSharesOnTheFolderShouldCascadeDeleteToTheChild() {
    // Given — folder shared to TARGET_ID (creating a DIRECT share on the folder AND an INHERITED
    // one on its child via createShareFetcher's own cascadeUpsertShare); deleting the share on the
    // FOLDER must cascade the deletion down to the child too (deleteSharesFetcher's
    // `getNodeType() == FOLDER` branch, unreached by DeleteSharesApiIT which only ever uses files).
    String folderId = "80000000-0000-0000-0000-000000000301";
    String childId = "80000000-0000-0000-0000-000000000302";
    app.backdoor()
        .populator()
        .addNode(new SimplePopulatorFolder(folderId, OWNER_ID, "folder"))
        .addNode(
            new PopulatorNode(
                childId, OWNER_ID, OWNER_ID, folderId, "child.txt", "",
                NodeType.TEXT, "LOCAL_ROOT," + folderId, 1L, "text/plain"));

    HttpResponse createResponse =
        createShare(folderId, TARGET_ID, SharePermission.READ_ONLY, OWNER_COOKIE);
    Assertions.assertThat(createResponse.getStatus()).isEqualTo(200);
    Assertions.assertThat(app.backdoor().shareExists(childId, TARGET_ID))
        .as("the child must have inherited the share before we can prove cascade-delete removes it")
        .isTrue();

    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("deleteShares")
            .withString("node_id", folderId)
            .withListOfStrings("share_target_ids", new String[] {TARGET_ID})
            .withWantedResultFormat("")
            .build();

    // When
    HttpResponse httpResponse =
        app.send(HttpRequest.of("POST", "/graphql/", OWNER_COOKIE, bodyPayload));

    // Then — both the folder's own share AND the child's inherited share are gone
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Assertions.assertThat(app.backdoor().shareExists(folderId, TARGET_ID)).isFalse();
    Assertions.assertThat(app.backdoor().shareExists(childId, TARGET_ID)).isFalse();
  }
}
