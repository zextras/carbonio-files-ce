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
 * {@code com.zextras.carbonio.files.acceptance.CopyNodesApiIT} rewritten as an out-of-process
 * {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}. All 12 methods and their assertions
 * are preserved verbatim; only the seeding mechanism (API calls capturing server-generated ids),
 * the storages-copy-failure trigger ({@link FilesStackTestResource#getStoragesService()}'s {@code
 * setCopyFails}, replacing the seam's {@code Mocks#storagesCopyFails}), and the transport changed.
 *
 * <p><b>Reading the source (behaviour this class pins down):</b>
 *
 * <ul>
 *   <li>The requester needs {@code READ_AND_WRITE} on {@code destination_id}, which must resolve to
 *       an existing {@code FOLDER}/{@code ROOT} node. Both a non-existent destination and an
 *       existing-but-wrong-type destination fall through the SAME top-level {@code if}/{@code else}
 *       into the SAME single-element {@code nodeWriteError(destination_id)} result.
 *   <li>Each source node only needs {@code canRead()} (a plain {@code READ_ONLY} share suffices).
 *   <li>A source {@code FOLDER} is excluded from the copy when the destination is nested somewhere
 *       under it — UNLESS the source's own current parent IS the destination (the explicit "copy a
 *       folder into its own parent" exception).
 *   <li>A filestore-copy failure deletes the just-created node row and reports {@code
 *       nodeCopyError(sourceId, sourceVersion, path)} using the SOURCE node's id/version.
 *   <li>Both single-element-error shapes leave the {@code copyNodes} GraphQL list value {@code
 *       null} (schema declares {@code copyNodes: [Node!]}), so the top-level {@code errors} array
 *       carries TWO messages for a single blocked node (the app's own error PLUS graphql-java's
 *       null-propagation error) — asserted below via the {@code errors} array and an EMPTY {@code
 *       data} extraction, not the raw JSON shape.
 * </ul>
 */
class CopyNodesApiIT extends AbstractFilesIT {

  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OTHER_USER_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String REQUESTER_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String OTHER_COOKIE = "ZM_AUTH_TOKEN=fake-token-b";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", REQUESTER_ID);
    FilesStackTestResource.getUserManagementService().registerToken("fake-token-b", OTHER_USER_ID);
  }

  private Response copyNodes(String[] nodeIds, String destinationId, String cookie) {
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("copyNodes")
            .withListOfStrings("node_ids", nodeIds)
            .withString("destination_id", destinationId)
            .withWantedResultFormat(
                "{ id name parent { id } owner { id } ... on File { extension } }")
            .build();
    return graphql(bodyPayload, cookie);
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> copiedNodes(Response response) {
    Map<String, Object> page =
        TestUtils.jsonResponseToMap(response.getBody().asString(), "copyNodes");
    Object data = page.get("data");
    return data == null ? List.of() : (List<Map<String, Object>>) data;
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> childrenOf(String folderId, String cookie) {
    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("getNode")
            .withString("node_id", folderId)
            .withWantedResultFormat(
                "{ ... on Folder { children(limit: 50, sort: NAME_ASC) { nodes { id name } } } }")
            .build();
    Response response = graphql(bodyPayload, cookie);
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Map<String, Object> node =
        TestUtils.jsonResponseToMap(response.getBody().asString(), "getNode");
    Map<String, Object> children = (Map<String, Object>) node.get("children");
    return (List<Map<String, Object>>) children.get("nodes");
  }

  @Test
  void givenAPlainFileCopyItShouldCreateACopyInDestinationAndLeaveTheOriginalIntact() {
    // Given
    String sourceId =
        seedFile(
            "source.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    String destFolderId = seedFolder("dest", LOCAL_ROOT, REQUESTER_COOKIE);

    // When
    Response response = copyNodes(new String[] {sourceId}, destFolderId, REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(response.getBody().asString())).isEmpty();
    List<Map<String, Object>> copied = copiedNodes(response);
    Assertions.assertThat(copied).hasSize(1);
    Map<String, Object> copiedNode = copied.get(0);
    Assertions.assertThat(copiedNode.get("id")).isNotEqualTo(sourceId);
    Assertions.assertThat(copiedNode)
        .containsEntry("name", "source")
        .containsEntry("extension", "txt");
    Assertions.assertThat((Map<String, Object>) copiedNode.get("parent"))
        .containsEntry("id", destFolderId);

    // the source is a COPY, not a move: it still exists, unchanged, at its original location
    Assertions.assertThat(nodeExists(sourceId, REQUESTER_COOKIE)).isTrue();
    Assertions.assertThat(childrenOf(LOCAL_ROOT, REQUESTER_COOKIE))
        .extracting(node -> node.get("id"))
        .contains(sourceId);
  }

  @Test
  void givenAFolderWithNestedContentCopyShouldCascadeAllDescendants() {
    // Given — srcFolder/child.txt, srcFolder/subFolder/grandchild.txt
    String srcFolderId = seedFolder("srcFolder", LOCAL_ROOT, REQUESTER_COOKIE);
    String childFileId =
        seedFile(
            "child.txt", srcFolderId, "content".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    String subFolderId = seedFolder("subFolder", srcFolderId, REQUESTER_COOKIE);
    String grandchildFileId =
        seedFile(
            "grandchild.txt",
            subFolderId,
            "content".getBytes(StandardCharsets.UTF_8),
            REQUESTER_COOKIE);
    String destFolderId = seedFolder("dest2", LOCAL_ROOT, REQUESTER_COOKIE);

    // When
    Response response = copyNodes(new String[] {srcFolderId}, destFolderId, REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<Map<String, Object>> copied = copiedNodes(response);
    Assertions.assertThat(copied).hasSize(1);
    String copiedFolderId = (String) copied.get(0).get("id");
    Assertions.assertThat(copiedFolderId).isNotEqualTo(srcFolderId);
    Assertions.assertThat(copied.get(0)).containsEntry("name", "srcFolder");

    List<Map<String, Object>> copiedFolderChildren = childrenOf(copiedFolderId, REQUESTER_COOKIE);
    Assertions.assertThat(copiedFolderChildren)
        .extracting(node -> node.get("name"))
        .containsExactlyInAnyOrder("child", "subFolder");

    String copiedSubFolderId =
        copiedFolderChildren.stream()
            .filter(node -> "subFolder".equals(node.get("name")))
            .findFirst()
            .orElseThrow()
            .get("id")
            .toString();
    Assertions.assertThat(copiedSubFolderId).isNotEqualTo(subFolderId);
    List<Map<String, Object>> copiedSubChildren = childrenOf(copiedSubFolderId, REQUESTER_COOKIE);
    Assertions.assertThat(copiedSubChildren)
        .extracting(node -> node.get("name"))
        .containsExactly("grandchild");

    // the original subtree is untouched
    Assertions.assertThat(nodeExists(srcFolderId, REQUESTER_COOKIE)).isTrue();
    Assertions.assertThat(nodeExists(childFileId, REQUESTER_COOKIE)).isTrue();
    Assertions.assertThat(nodeExists(subFolderId, REQUESTER_COOKIE)).isTrue();
    Assertions.assertThat(nodeExists(grandchildFileId, REQUESTER_COOKIE)).isTrue();
  }

  @Test
  void givenANameClashInDestinationCopyShouldDedupTheName() {
    // Given — destination already has a file named "clash.txt"
    String destFolderId = seedFolder("dest3", LOCAL_ROOT, REQUESTER_COOKIE);
    seedFile(
        "clash.txt", destFolderId, "content".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    String sourceId =
        seedFile(
            "clash.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);

    // When
    Response response = copyNodes(new String[] {sourceId}, destFolderId, REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<Map<String, Object>> copied = copiedNodes(response);
    Assertions.assertThat(copied).hasSize(1);
    Assertions.assertThat(copied.get(0))
        .containsEntry("name", "clash (1)")
        .containsEntry("extension", "txt");
  }

  @Test
  void givenNoNameClashCopyShouldKeepTheOriginalName() {
    // Given — destination has an unrelated file; no clash with the source's name
    String destFolderId = seedFolder("dest4", LOCAL_ROOT, REQUESTER_COOKIE);
    seedFile(
        "other.txt", destFolderId, "content".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    String sourceId =
        seedFile(
            "unique.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);

    // When
    Response response = copyNodes(new String[] {sourceId}, destFolderId, REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<Map<String, Object>> copied = copiedNodes(response);
    Assertions.assertThat(copied).hasSize(1);
    Assertions.assertThat(copied.get(0))
        .containsEntry("name", "unique")
        .containsEntry("extension", "txt");
  }

  @Test
  void givenSourceWithOnlyReadPermissionCopyShouldSucceed() {
    // Given — source owned by OTHER_USER_ID, shared READ_ONLY (no write) with the requester
    String sourceId =
        seedFile(
            "shared.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OTHER_COOKIE);
    seedShare(sourceId, REQUESTER_ID, ACL.SharePermission.READ_ONLY, OTHER_COOKIE);
    String destFolderId = seedFolder("dest5", LOCAL_ROOT, REQUESTER_COOKIE);

    // When
    Response response = copyNodes(new String[] {sourceId}, destFolderId, REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(response.getBody().asString())).isEmpty();
    Assertions.assertThat(copiedNodes(response)).hasSize(1);
  }

  @Test
  void givenCopyIntoOwnDescendantItShouldBeBlockedWithNodeWriteError() {
    // Given — parentF contains childF; copying parentF INTO childF would nest it inside its own
    // descendant
    String parentFolderId = seedFolder("parentF", LOCAL_ROOT, REQUESTER_COOKIE);
    String childFolderId = seedFolder("childF", parentFolderId, REQUESTER_COOKIE);

    // When
    Response response = copyNodes(new String[] {parentFolderId}, childFolderId, REQUESTER_COOKIE);

    // Then — the single blocked source node bubbles TWO errors: the app's own nodeWriteError, PLUS
    // a graphql-java-generated null-propagation error (see class javadoc) because the schema
    // declares `copyNodes: [Node!]` (non-null items) and this one item resolved with a null value.
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors)
        .hasSize(2)
        .contains(
            "There was a problem while executing requested operation on node: " + parentFolderId);
    Assertions.assertThat(copiedNodes(response)).isEmpty();
    Assertions.assertThat(childrenOf(childFolderId, REQUESTER_COOKIE)).isEmpty();
  }

  @Test
  void givenCopyIntoItsOwnCurrentParentItShouldBeAllowedAndDedupTheName() {
    // Given — childF7 already sits inside parentF7; copying it back into parentF7 (its OWN
    // current parent) is the explicit exception to the descendant-block rule, and collides with
    // itself by name
    String parentFolderId = seedFolder("parentF7", LOCAL_ROOT, REQUESTER_COOKIE);
    String folderId = seedFolder("childF7", parentFolderId, REQUESTER_COOKIE);

    // When
    Response response = copyNodes(new String[] {folderId}, parentFolderId, REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(response.getBody().asString())).isEmpty();
    List<Map<String, Object>> copied = copiedNodes(response);
    Assertions.assertThat(copied).hasSize(1);
    Assertions.assertThat(copied.get(0).get("id")).isNotEqualTo(folderId);
    Assertions.assertThat(copied.get(0)).containsEntry("name", "childF7 (1)");
  }

  @Test
  void givenANonExistentDestinationCopyShouldReturnNodeWriteError() {
    // Given
    String nonExistentDest = "40000000-0000-0000-0000-00000000ffff";
    String sourceId =
        seedFile(
            "src8.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);

    // When
    Response response = copyNodes(new String[] {sourceId}, nonExistentDest, REQUESTER_COOKIE);

    // Then — see the descendant-block test above for why this is 2 errors, not 1
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors)
        .hasSize(2)
        .contains(
            "There was a problem while executing requested operation on node: " + nonExistentDest);
    Assertions.assertThat(copiedNodes(response)).isEmpty();
  }

  @Test
  void givenADestinationThatIsAFileNotAFolderCopyShouldReturnTheSameNodeWriteError() {
    // Given — destination exists and the requester owns it (so permission alone would pass), but
    // it is a FILE, not a FOLDER/ROOT
    String sourceId =
        seedFile(
            "src9.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    String destFileId =
        seedFile(
            "notAFolder.txt",
            LOCAL_ROOT,
            "content".getBytes(StandardCharsets.UTF_8),
            REQUESTER_COOKIE);

    // When
    Response response = copyNodes(new String[] {sourceId}, destFileId, REQUESTER_COOKIE);

    // Then — see the descendant-block test above for why this is 2 errors, not 1
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors)
        .hasSize(2)
        .contains("There was a problem while executing requested operation on node: " + destFileId);
  }

  @Test
  void givenNoWritePermissionOnDestinationCopyShouldReturnNodeWriteError() {
    // Given — destination folder owned by OTHER_USER_ID, never shared with the requester
    String sourceId =
        seedFile(
            "src10.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    String destFolderId = seedFolder("privateDest", LOCAL_ROOT, OTHER_COOKIE);

    // When
    Response response = copyNodes(new String[] {sourceId}, destFolderId, REQUESTER_COOKIE);

    // Then — see the descendant-block test above for why this is 2 errors, not 1
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors)
        .hasSize(2)
        .contains(
            "There was a problem while executing requested operation on node: " + destFolderId);
  }

  @Test
  void givenAFilestoreCopyFailureCopyShouldReturnNodeCopyErrorAndLeaveNoOrphan() {
    // Given
    String sourceId =
        seedFile(
            "src11.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    String destFolderId = seedFolder("dest11", LOCAL_ROOT, REQUESTER_COOKIE);
    FilesStackTestResource.getStoragesService().setCopyFails(true);

    // When
    Response response = copyNodes(new String[] {sourceId}, destFolderId, REQUESTER_COOKIE);

    // Then — copyFile's Try#onFailure deletes the just-created node row and reports the error
    // using the SOURCE node's id/version (a freshly-seeded file's current version is 1). See the
    // descendant-block test above for why this is 2 errors, not 1.
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors)
        .hasSize(2)
        .contains("There was a problem while copying the node " + sourceId + " with version 1");
    Assertions.assertThat(copiedNodes(response)).isEmpty();

    // the source is untouched, and no orphan row was left behind in the destination
    Assertions.assertThat(nodeExists(sourceId, REQUESTER_COOKIE)).isTrue();
    Assertions.assertThat(childrenOf(destFolderId, REQUESTER_COOKIE)).isEmpty();
  }

  @Test
  void givenACopyIntoASharedFolderItShouldNotifyTheShareTargetWithAddedNodeTypeCopy() {
    // Given — destination folder owned by the requester, shared with OTHER_USER_ID. Unlike the
    // original seam (which seeded the share via a direct repository write, bypassing the
    // notification pipeline entirely), seedShare drives the real createShare mutation here — so
    // OTHER_USER_ID legitimately also receives a NewShare notification from the seeding step
    // itself. That is correct product behaviour (see NewShareNotificationApiIT), not an artifact
    // to suppress: filter to the AddedNode-typed notification (via __typename) rather than
    // asserting the total notification count, preserving this test's actual intent (the target
    // was notified of the COPY) without a false claim about how many notifications exist overall.
    String destFolderId = seedFolder("sharedDest12", LOCAL_ROOT, REQUESTER_COOKIE);
    seedShare(destFolderId, OTHER_USER_ID, ACL.SharePermission.READ_AND_WRITE, REQUESTER_COOKIE);
    String sourceId =
        seedFile(
            "toCopy12.txt",
            LOCAL_ROOT,
            "content".getBytes(StandardCharsets.UTF_8),
            REQUESTER_COOKIE);

    // When
    Response copyResponse = copyNodes(new String[] {sourceId}, destFolderId, REQUESTER_COOKIE);
    Assertions.assertThat(copyResponse.getStatusCode()).isEqualTo(200);

    String notificationsPayload =
        GraphqlCommandBuilder.aQueryBuilder("getNotifications")
            .withBoolean("update_last_seen", true)
            .withWantedResultFormat(
                "{ notifications { __typename ... on AddedNode { added_node_type added_node {"
                    + " node_id } } } }")
            .build();
    Response notificationsResponse = graphql(notificationsPayload, OTHER_COOKIE);

    // Then
    Assertions.assertThat(notificationsResponse.getStatusCode()).isEqualTo(200);
    Map<String, Object> page =
        TestUtils.jsonResponseToMap(notificationsResponse.getBody().asString(), "getNotifications");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> notifications = (List<Map<String, Object>>) page.get("notifications");
    List<Map<String, Object>> addedNodeNotifications =
        notifications.stream()
            .filter(notification -> "AddedNode".equals(notification.get("__typename")))
            .toList();
    Assertions.assertThat(addedNodeNotifications).hasSize(1);
    Assertions.assertThat(addedNodeNotifications.get(0)).containsEntry("added_node_type", "COPY");
  }
}
