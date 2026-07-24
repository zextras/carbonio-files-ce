// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code com.zextras.carbonio.files.acceptance.MoveRestoreEdgeCasesApiIT} rewritten as an
 * out-of-process {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}. Deepens {@code
 * NodeDataFetcher#moveNodesFetcher}/{@code #restoreNodes} branch coverage:
 *
 * <h2>moveNodes</h2>
 *
 * <ul>
 *   <li>Permission-denied on the destination; a FILE (not FOLDER/ROOT) destination; every
 *       requested node filtered out; a ROOT id + a self-move in the same request.
 *   <li>Mover ≠ destination-folder-owner (inherited-share upsert + destination-owner notify);
 *       moved node already has a DIRECT share to a destination target (left untouched); mover ≠
 *       moved node's CURRENT parent's owner on the REMOVE-notification side (fresh add / skip
 *       duplicate).
 * </ul>
 *
 * <h2>restoreNodes</h2>
 *
 * <ul>
 *   <li>Never-trashed node; a ROOT id; the node's recorded original parent no longer resolves to
 *       any row at all (promotes to {@code LOCAL_ROOT}, indirect share promoted to direct); the
 *       recorded original parent still exists but is ITSELF also trashed (same fatherless
 *       treatment); restoring a FOLDER into a valid non-ROOT parent with shares (cascades the
 *       share down to the restored folder's own child).
 * </ul>
 *
 * <p>Several scenarios need a node whose owner differs from its structural parent's owner, or a
 * child that must NOT have inherited its parent's share cascade yet — neither is producible via
 * the public API (a real {@code createFolder}/{@code upload} always inherits the parent's owner
 * AND cascades shares) — those are seeded via {@link #seedInconsistentNode} (raw JDBC, mirrors
 * exactly what {@code NodeRepositoryImpl#createNewNode} persists) and the recorded-original-parent
 * override via {@link #forceTrashedOldParentId} (the node is first genuinely trashed via the real
 * {@code trashNodes} mutation, then its {@code trashed.parent_id} is overridden — there is no
 * public mutation that lets a caller record an arbitrary/inconsistent original-parent value). All
 * 13 methods and their assertions are preserved verbatim; only the seeding mechanism and transport
 * changed.
 */
class MoveRestoreEdgeCasesApiIT extends AbstractFilesIT {

  private static final String OWNER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OTHER_USER_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String THIRD_USER_ID = "cccccccc-cccc-cccc-cccc-cccccccccccc";
  private static final String OWNER_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String OTHER_COOKIE = "ZM_AUTH_TOKEN=fake-token-b";
  private static final String THIRD_COOKIE = "ZM_AUTH_TOKEN=fake-token-c";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", OWNER_ID);
    FilesStackTestResource.getUserManagementService().registerToken("fake-token-b", OTHER_USER_ID);
    FilesStackTestResource.getUserManagementService().registerToken("fake-token-c", THIRD_USER_ID);
  }

  private Response moveNodes(String[] nodeIds, String destinationId, String cookie) {
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("moveNodes")
            .withListOfStrings("node_ids", nodeIds)
            .withString("destination_id", destinationId)
            .withWantedResultFormat("{ id }")
            .build();
    return graphql(bodyPayload, cookie);
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> movedNodes(Response response) {
    Map<String, Object> page = TestUtils.jsonResponseToMap(response.getBody().asString(), "moveNodes");
    Object data = page.get("data");
    return data == null ? List.of() : (List<Map<String, Object>>) data;
  }

  private Response restoreNodes(String[] nodeIds, String cookie) {
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("restoreNodes")
            .withListOfStrings("node_ids", nodeIds)
            .withWantedResultFormat("{ id }")
            .build();
    return graphql(bodyPayload, cookie);
  }

  @SuppressWarnings("unchecked")
  private int notificationCountOf(String cookie, String onClause) {
    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("getNotifications")
            .withBoolean("update_last_seen", true)
            .withWantedResultFormat("{ notifications { " + onClause + " } }")
            .build();
    Response response = graphql(bodyPayload, cookie);
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Map<String, Object> page = TestUtils.jsonResponseToMap(response.getBody().asString(), "getNotifications");
    List<Map<String, Object>> notifications = (List<Map<String, Object>>) page.get("notifications");
    return (int) notifications.stream().filter(n -> !n.isEmpty()).count();
  }

  // ---------------------------------------------------------------------------------------------
  // moveNodes
  // ---------------------------------------------------------------------------------------------

  @Test
  void givenNoWritePermissionOnDestinationMoveNodesShouldReturnNodeWriteError() {
    // Given — destination owned by someone else, never shared
    String destFolderId = seedFolder("privateDest", LOCAL_ROOT, OTHER_COOKIE);
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);

    // When
    Response response = moveNodes(new String[] {nodeId}, destFolderId, OWNER_COOKIE);

    // Then — the single blocked node bubbles TWO errors: the app's own nodeWriteError, PLUS a
    // graphql-java-generated null-propagation error (schema declares `moveNodes: [Node!]`, a
    // non-null list item resolving to null propagates as a second error — same divergence already
    // documented by CopyNodesApiIT for the equivalent copyNodes shape).
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors)
        .hasSize(2)
        .contains("There was a problem while executing requested operation on node: " + destFolderId);
    Assertions.assertThat(movedNodes(response)).isEmpty();
  }

  @Test
  void givenADestinationThatIsAFileNotAFolderMoveNodesShouldReturnNodeWriteError() {
    // Given — destination exists and is owned by the requester (permission gate passes), but it
    // is a FILE, not a FOLDER/ROOT
    String destFileId =
        seedFile("notAFolder.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);

    // When
    Response response = moveNodes(new String[] {nodeId}, destFileId, OWNER_COOKIE);

    // Then — see the destination-permission test above for why this is 2 errors, not 1
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors)
        .hasSize(2)
        .contains("There was a problem while executing requested operation on node: " + destFileId);
  }

  @Test
  void givenTheOnlyRequestedNodeHasNoPermissionMoveNodesShouldReturnEmptyDataAndOneError() {
    // Given — destination valid, but the sole requested node is owned by someone else, unshared
    String destFolderId = seedFolder("dest", LOCAL_ROOT, OWNER_COOKIE);
    String nodeId =
        seedFile("notMine.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OTHER_COOKIE);

    // When
    Response response = moveNodes(new String[] {nodeId}, destFolderId, OWNER_COOKIE);

    // Then — nodeIdsToMove ends up empty: the whole "movedNodesResult" building block is skipped;
    // see the destination-permission test above for why this is 2 errors, not 1
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(movedNodes(response)).isEmpty();
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors)
        .hasSize(2)
        .contains("There was a problem while executing requested operation on node: " + nodeId);
  }

  @Test
  void givenARootIdAndASelfMoveMoveNodesShouldFilterBothAsErrors() {
    // Given — a valid destination owned by the requester
    String destFolderId = seedFolder("dest", LOCAL_ROOT, OWNER_COOKIE);

    // When — one root id (always filtered) + a self-move (destination moved into itself)
    Response response = moveNodes(new String[] {"LOCAL_ROOT", destFolderId}, destFolderId, OWNER_COOKIE);

    // Then — TWO blocked nodes, each bubbling its own app error PLUS its own null-propagation
    // error (see the destination-permission test above) -> 4 errors total
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(movedNodes(response)).isEmpty();
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors)
        .hasSize(4)
        .contains(
            "There was a problem while executing requested operation on node: LOCAL_ROOT",
            "There was a problem while executing requested operation on node: " + destFolderId);
  }

  @Test
  void givenAMoverWhoIsNotTheDestinationOwnerMoveNodesShouldNotifyTheOwnerAndUpsertInheritedShares() {
    // Given — folder F owned by OWNER_ID, shared WRITE to OTHER_USER_ID (mover) and shared to
    // THIRD_USER_ID (an unrelated share target on the destination); a FRESH, completely unshared
    // node owned by the mover, sitting at LOCAL_ROOT
    String destFolderId = seedFolder("sharedDest", LOCAL_ROOT, OWNER_COOKIE);
    seedShare(destFolderId, OTHER_USER_ID, SharePermission.READ_AND_WRITE, OWNER_COOKIE);
    seedShare(destFolderId, THIRD_USER_ID, SharePermission.READ_ONLY, OWNER_COOKIE);
    String nodeId =
        seedFile("fresh.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OTHER_COOKIE);

    // When — OTHER_USER_ID (not the destination's owner) moves their own fresh node into it
    Response response = moveNodes(new String[] {nodeId}, destFolderId, OTHER_COOKIE);

    // Then — the move succeeds...
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(response.getBody().asString())).isEmpty();
    Assertions.assertThat(movedNodes(response)).hasSize(1);

    // ...the node inherited THIRD_USER_ID's share from the destination (sourceShare absent -> upsert).
    // Checked via OTHER_COOKIE (the node's own owner, per PermissionsChecker#getPermissions):
    // OWNER_ID has no permission to read this node at all (owning the ANCESTOR folder does not
    // grant access to a specific child — the destination's shares list has no entry for OWNER_ID,
    // who is the destination's OWNER rather than one of its share targets)...
    Assertions.assertThat(shareExists(nodeId, THIRD_USER_ID, OTHER_COOKIE)).isTrue();

    // ...and the destination's OWNER (who did not perform the move) got an AddedNode notification
    Assertions.assertThat(notificationCountOf(OWNER_COOKIE, "... on AddedNode { created_at }"))
        .isEqualTo(1);
  }

  @Test
  void givenTheMovedNodeAlreadyHasADirectShareToADestinationTargetMoveNodesShouldLeaveItUntouched() {
    // Given — node X owned by OTHER_USER_ID already has a DIRECT (not inherited) share to
    // THIRD_USER_ID; the destination folder is ALSO shared to THIRD_USER_ID. Unlike an INHERITED
    // share (deleted by the same move's own remove-phase before this check ever runs — see
    // moveNodesFetcher's `!share.isDirect()` remove-loop, which always runs first), a DIRECT share
    // survives into the add-phase's `sourceShare` lookup, so the recompute loop must find it
    // PRESENT and DIRECT and skip re-upserting it.
    String destFolderId = seedFolder("dest", LOCAL_ROOT, OTHER_COOKIE);
    seedShare(destFolderId, THIRD_USER_ID, SharePermission.READ_ONLY, OTHER_COOKIE);
    String nodeId =
        seedFile("shared.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OTHER_COOKIE);
    seedShare(nodeId, THIRD_USER_ID, SharePermission.READ_AND_WRITE, OTHER_COOKIE);

    // When — the node's own owner moves it into the (also-shared-to-the-same-user) destination
    Response response = moveNodes(new String[] {nodeId}, destFolderId, OTHER_COOKIE);

    // Then — the move succeeds and the pre-existing direct share is untouched (still present,
    // still whatever permission it already had — not overwritten with the destination's own tier)
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(response.getBody().asString())).isEmpty();
    Assertions.assertThat(shareExists(nodeId, THIRD_USER_ID, OTHER_COOKIE)).isTrue();
  }

  @Test
  void givenAMoverWhoIsNotTheNodesParentOwnerMoveNodesShouldNotifyTheParentOwnerFreshly()
      throws SQLException {
    // Given — folder F owned by OWNER_ID, shared WRITE to OTHER_USER_ID; a node OWNED by
    // OTHER_USER_ID but structurally parented under F (ownership does not follow structure on a
    // raw fixture), with NO shares of its own at all
    String parentFolderId = seedFolder("F", LOCAL_ROOT, OWNER_COOKIE);
    seedShare(parentFolderId, OTHER_USER_ID, SharePermission.READ_AND_WRITE, OWNER_COOKIE);
    String nodeId = UUID.randomUUID().toString();
    seedInconsistentNode(
        nodeId,
        OTHER_USER_ID,
        OTHER_USER_ID,
        parentFolderId,
        "child.txt",
        NodeType.TEXT,
        "LOCAL_ROOT," + parentFolderId,
        1L,
        "text/plain");

    // When — the node's owner (not F's owner) moves it away to LOCAL_ROOT
    Response response = moveNodes(new String[] {nodeId}, LOCAL_ROOT, OTHER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(response.getBody().asString())).isEmpty();

    // F's owner (fresh add: was not already in the notify list) gets exactly one RemovedNode notification
    Assertions.assertThat(notificationCountOf(OWNER_COOKIE, "... on RemovedNode { created_at }"))
        .isEqualTo(1);
  }

  @Test
  void givenAMoverWhoIsNotTheNodesParentOwnerButTheOwnerIsAlreadyAShareTargetMoveNodesShouldNotDuplicateTheNotification()
      throws SQLException {
    // Given — same shape as above, but the node ALSO has a DIRECT share to F's owner already (so
    // the owner is already in the remove-notify list before the "add if absent" check runs)
    String parentFolderId = seedFolder("F", LOCAL_ROOT, OWNER_COOKIE);
    seedShare(parentFolderId, OTHER_USER_ID, SharePermission.READ_AND_WRITE, OWNER_COOKIE);
    String nodeId = UUID.randomUUID().toString();
    seedInconsistentNode(
        nodeId,
        OTHER_USER_ID,
        OTHER_USER_ID,
        parentFolderId,
        "child2.txt",
        NodeType.TEXT,
        "LOCAL_ROOT," + parentFolderId,
        1L,
        "text/plain");
    seedShare(nodeId, OWNER_ID, SharePermission.READ_ONLY, OTHER_COOKIE);

    // When
    Response response = moveNodes(new String[] {nodeId}, LOCAL_ROOT, OTHER_COOKIE);

    // Then — F's owner still gets EXACTLY ONE notification, not two (the contains-check prevented
    // a duplicate add to the notify list)
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(notificationCountOf(OWNER_COOKIE, "... on RemovedNode { created_at }"))
        .isEqualTo(1);
  }

  // ---------------------------------------------------------------------------------------------
  // restoreNodes
  // ---------------------------------------------------------------------------------------------

  @Test
  void givenANodeThatWasNeverTrashedRestoreNodesShouldReturnNodeWriteError() {
    // Given — exists, owned by the requester, but never trashed
    String nodeId =
        seedFile("notTrashed.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);

    // When
    Response response = restoreNodes(new String[] {nodeId}, OWNER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("There was a problem while executing requested operation on node: " + nodeId);
  }

  @Test
  void givenARootIdRestoreNodesShouldFilterItAsAnError() {
    // When
    Response response = restoreNodes(new String[] {"LOCAL_ROOT"}, OWNER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("There was a problem while executing requested operation on node: LOCAL_ROOT");
  }

  @Test
  void givenAFatherlessNodeWhoseOriginalParentRowNoLongerExistsRestoreNodesShouldRestoreToLocalRootAndPromoteIndirectSharesToDirect()
      throws SQLException {
    // Given — folder F (shared to THIRD_USER_ID), a sub-folder X created via the REAL createFolder
    // mutation (so X inherits an INDIRECT share to THIRD_USER_ID via cascade); X is then trashed
    // with its RECORDED original parent set to an id that was NEVER a real node row at all
    String parentFolderId = seedFolder("F", LOCAL_ROOT, OWNER_COOKIE);
    seedShare(parentFolderId, THIRD_USER_ID, SharePermission.READ_ONLY, OWNER_COOKIE);

    String nodeId = seedFolder("X", parentFolderId, OWNER_COOKIE);
    Assertions.assertThat(shareExists(nodeId, THIRD_USER_ID, OWNER_COOKIE))
        .as("X must have inherited an INDIRECT share before we can prove promotion to DIRECT")
        .isTrue();

    // trash X for real (records trashed.parent_id = F, its true current parent), then tamper the
    // recorded original parent to an id that was never a real node
    seedTrashed(nodeId, OWNER_COOKIE);
    String neverExistedParentId = UUID.randomUUID().toString();
    forceTrashedOldParentId(nodeId, neverExistedParentId);

    // When
    Response response = restoreNodes(new String[] {nodeId}, OWNER_COOKIE);

    // Then — restored to LOCAL_ROOT (not left dangling, not erroring on the missing parent)
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(response.getBody().asString())).isEmpty();
    Assertions.assertThat(nodeExists(nodeId, OWNER_COOKIE)).isTrue();

    // The share survives the restore itself...
    Assertions.assertThat(shareExists(nodeId, THIRD_USER_ID, OWNER_COOKIE)).isTrue();

    // ...and PROOF it is now DIRECT (not indirect): moving X away into a folder never shared with
    // THIRD_USER_ID would strip any INDIRECT share (moveNodesFetcher's remove-phase), but a DIRECT
    // one survives.
    String privateFolderId = seedFolder("private", LOCAL_ROOT, OWNER_COOKIE);
    Response moveResponse = moveNodes(new String[] {nodeId}, privateFolderId, OWNER_COOKIE);
    Assertions.assertThat(moveResponse.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(shareExists(nodeId, THIRD_USER_ID, OWNER_COOKIE))
        .as("a DIRECT share must survive a move away from any granting ancestor")
        .isTrue();
  }

  @Test
  void givenAFatherlessNodeWhoseOriginalParentIsItselfTrashedRestoreNodesShouldRestoreToLocalRoot()
      throws SQLException {
    // Given — folder F is trashed too (its OWN ancestor chain now contains TRASH_ROOT), so a child
    // recorded as originally-parented-under F must ALSO be treated as fatherless, even though F's
    // row still physically exists
    String parentFolderId = seedFolder("F", LOCAL_ROOT, OWNER_COOKIE);
    String nodeId =
        seedFile("child.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    seedTrashed(parentFolderId, OWNER_COOKIE);
    // nodeId's true current parent is LOCAL_ROOT; force its RECORDED original parent to F (itself
    // trashed) — there is no public mutation to record an inconsistent original-parent value.
    seedTrashed(nodeId, OWNER_COOKIE);
    forceTrashedOldParentId(nodeId, parentFolderId);

    // When
    Response response = restoreNodes(new String[] {nodeId}, OWNER_COOKIE);

    // Then — restored to LOCAL_ROOT, not left pointing at a still-trashed parent
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(response.getBody().asString())).isEmpty();
    Assertions.assertThat(nodeExists(nodeId, OWNER_COOKIE)).isTrue();

    String getNodePayload =
        GraphqlCommandBuilder.aQueryBuilder("getNode")
            .withString("node_id", nodeId)
            .withWantedResultFormat("{ id parent { id } }")
            .build();
    Response getNodeResponse = graphql(getNodePayload, OWNER_COOKIE);
    Map<String, Object> node = TestUtils.jsonResponseToMap(getNodeResponse.getBody().asString(), "getNode");
    @SuppressWarnings("unchecked")
    Map<String, Object> parent = (Map<String, Object>) node.get("parent");
    Assertions.assertThat(parent).containsEntry("id", "LOCAL_ROOT");
  }

  @Test
  @SuppressWarnings("unchecked")
  void givenAValidNonRootParentWithSharesRestoringAFolderShouldCascadeTheShareToItsOwnChild()
      throws SQLException {
    // Given — P (a real, non-trashed FOLDER, shared to THIRD_USER_ID) is Q's recorded original
    // parent; Q is itself a FOLDER with its own child R. Q/R must NOT have already inherited P's
    // share at seed time (unlike a real createFolder, which would cascade it immediately) so that
    // restoring Q is what proves ShareDataFetcher#cascadeUpsertShare's recursive FOLDER branch —
    // seeded via seedInconsistentNode (raw insert, no cascade side effect) rather than the API.
    String validParentId = seedFolder("P", LOCAL_ROOT, OWNER_COOKIE);
    seedShare(validParentId, THIRD_USER_ID, SharePermission.READ_ONLY, OWNER_COOKIE);

    String folderId = UUID.randomUUID().toString();
    seedInconsistentNode(
        folderId,
        OWNER_ID,
        OWNER_ID,
        validParentId,
        "Q",
        NodeType.FOLDER,
        "LOCAL_ROOT," + validParentId,
        0L,
        null);
    String grandchildId = UUID.randomUUID().toString();
    seedInconsistentNode(
        grandchildId,
        OWNER_ID,
        OWNER_ID,
        folderId,
        "R.txt",
        NodeType.TEXT,
        "LOCAL_ROOT," + validParentId + "," + folderId,
        1L,
        "text/plain");
    Assertions.assertThat(shareExists(folderId, THIRD_USER_ID, OWNER_COOKIE))
        .as("Q must start with NO share at all, to prove restore's OWN cascade below")
        .isFalse();

    // trash Q for real (its genuine current parent IS validParentId, so trashed.parent_id is
    // recorded correctly without needing the forceTrashedOldParentId escape hatch)
    seedTrashed(folderId, OWNER_COOKIE);

    // When
    Response response = restoreNodes(new String[] {folderId}, OWNER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(response.getBody().asString())).isEmpty();

    // Q itself is now (again) parented under P
    String getNodePayload =
        GraphqlCommandBuilder.aQueryBuilder("getNode")
            .withString("node_id", folderId)
            .withWantedResultFormat("{ id parent { id } }")
            .build();
    Response getNodeResponse = graphql(getNodePayload, OWNER_COOKIE);
    Map<String, Object> node = TestUtils.jsonResponseToMap(getNodeResponse.getBody().asString(), "getNode");
    Assertions.assertThat((Map<String, Object>) node.get("parent")).containsEntry("id", validParentId);

    // P's share cascaded onto Q AND down to Q's own child R
    Assertions.assertThat(shareExists(folderId, THIRD_USER_ID, OWNER_COOKIE)).isTrue();
    Assertions.assertThat(shareExists(grandchildId, THIRD_USER_ID, OWNER_COOKIE))
        .as("cascadeUpsertShare must reach the restored folder's own descendants")
        .isTrue();
  }
}
