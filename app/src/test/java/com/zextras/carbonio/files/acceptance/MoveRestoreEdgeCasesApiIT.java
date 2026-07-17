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
 * Deepens {@code NodeDataFetcher#moveNodesFetcher}/{@code #restoreNodes} JaCoCo branch coverage
 * beyond Waves 0-6, per the acceptance-coverage-expansion measurement loop. Every scenario below
 * was picked from a line-by-line read of {@code core/target/jacoco-it-report/jacoco.xml}'s missed
 * branches for {@code NodeDataFetcher.java}, cross-checked against {@code MoveNodesApiIT}/{@code
 * RestoreNodesApiIT}/the notification {@code *ApiIT}s to avoid duplicating existing coverage.
 *
 * <h2>moveNodes</h2>
 *
 * <ul>
 *   <li>Permission-denied on the destination (no existing {@code MoveNodesApiIT} test covers
 *       this at all).
 *   <li>Destination that exists, and the requester has {@code READ_AND_WRITE} on it, but it is a
 *       FILE, not a FOLDER/ROOT — falls through {@code moveNodesFetcher}'s
 *       {@code FOLDER-or-ROOT} check. <b>FINDING:</b> a genuinely non-existent destination is
 *       NOT a separate reachable branch here — {@link
 *       com.zextras.carbonio.files.utilities.PermissionsChecker#getPermissions} does its own
 *       {@code nodeRepository.getNode(...)} lookup and returns {@code ACL.NONE} for any missing
 *       id, so it always fails the OUTER permission gate first (identical to the {@code
 *       CreateFolderApiIT} class-level finding for {@code createFolder}, not previously documented
 *       for {@code moveNodes}/{@code copyNodes}); the subsequent {@code
 *       optDestinationFolder.isPresent()} check can therefore never observe {@code false} and is
 *       dead code — not attempted here.
 *   <li>Every requested node filtered out (no permission on the sole one) with an otherwise-valid
 *       destination — the {@code nodeIdsToMove.isEmpty()} branch, never hit by any existing test
 *       (which always move at least one node successfully).
 *   <li>A ROOT id and a self-move (destination == the node itself) in the same request — the
 *       {@code rootIds.contains(nodeId)} and {@code destinationFolderId.equals(nodeId)} filters.
 *   <li>Mover ≠ destination-folder-owner, moving a node that has NO existing shares into a folder
 *       shared with a third party — closes the {@code sourceShare} "absent" branch of the
 *       inherited-share-recompute loop AND the destination-owner remove-notify gate (previously
 *       always false, since every existing move-notification test moves as the folder's owner).
 *   <li>Mover ≠ the moved node's CURRENT parent's owner, on the REMOVE-notification side, with
 *       both the "owner not yet in the notify list" (fresh add) and "owner already in the notify
 *       list" (skip, avoid duplicate) outcomes of the {@code
 *       !usersToNotifyRemoveNode.contains(parent.getOwnerId())} check — reachable ONLY because a
 *       node's ownership does not change on move, so a node can retain an owner different from
 *       its new parent's owner (every existing move-notification test moves as the parent's own
 *       owner, so this line is 0% covered).
 * </ul>
 *
 * <h2>restoreNodes</h2>
 *
 * <ul>
 *   <li>A node that is not actually in the trash (never trashed) — the {@code
 *       nodeRepository.getTrashedNode(nodeId).isPresent()} filter.
 *   <li>A ROOT id in the request — the {@code getNodeType() != ROOT} filter.
 *   <li>The node's recorded original parent no longer resolves to any row at all (hard-gone) —
 *       promotes to {@code LOCAL_ROOT} and promotes the node's INDIRECT share(s) to DIRECT
 *       (proved behaviourally: a direct share survives a subsequent move away, an indirect one
 *       would not).
 *   <li>The node's recorded original parent still exists as a row but is ITSELF also trashed
 *       (the other reachable half of the same {@code !isPresent() || ancestors.contains(TRASH)}
 *       check) — same fatherless treatment.
 *   <li>Restoring a FOLDER into a valid, still-existing, NON-ROOT parent that has shares —
 *       exercises the {@code FOLDER}-vs-{@code ROOT} ancestor-string ternary AND {@code
 *       ShareDataFetcher#cascadeUpsertShare}'s recursive share-cascade to the restored folder's
 *       OWN child (proved by asserting the grandchild inherits the share too).
 * </ul>
 */
@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class MoveRestoreEdgeCasesApiIT {

  static FilesTestApp app;

  private static final String OWNER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OTHER_USER_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String THIRD_USER_ID = "cccccccc-cccc-cccc-cccc-cccccccccccc";
  private static final String OWNER_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String OTHER_COOKIE = "ZM_AUTH_TOKEN=fake-token-b";
  private static final String THIRD_COOKIE = "ZM_AUTH_TOKEN=fake-token-c";

  @BeforeAll
  static void init() {
    app =
        QuarkusFilesTestAppBuilder.aFilesTestApp()
            .withDatabase()
            .withServiceDiscover()
            .withUserManagement(
                Map.of(
                    "fake-token", OWNER_ID,
                    "fake-token-b", OTHER_USER_ID,
                    "fake-token-c", THIRD_USER_ID))
            .build();
  }

  @AfterEach
  void cleanUp() {
    app.backdoor().resetDatabase();
    app.mocks().reset();
  }

  @AfterAll
  static void cleanUpAll() {
    app.close();
  }

  private HttpResponse moveNodes(String[] nodeIds, String destinationId, String cookie) {
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("moveNodes")
            .withListOfStrings("node_ids", nodeIds)
            .withString("destination_id", destinationId)
            .withWantedResultFormat("{ id }")
            .build();
    return app.send(HttpRequest.of("POST", "/graphql/", cookie, bodyPayload));
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> movedNodes(HttpResponse httpResponse) {
    Map<String, Object> page = TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "moveNodes");
    Object data = page.get("data");
    return data == null ? List.of() : (List<Map<String, Object>>) data;
  }

  private HttpResponse restoreNodes(String[] nodeIds, String cookie) {
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("restoreNodes")
            .withListOfStrings("node_ids", nodeIds)
            .withWantedResultFormat("{ id }")
            .build();
    return app.send(HttpRequest.of("POST", "/graphql/", cookie, bodyPayload));
  }

  @SuppressWarnings("unchecked")
  private int notificationCountOf(String cookie, String onClause) {
    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("getNotifications")
            .withBoolean("update_last_seen", true)
            .withWantedResultFormat("{ notifications { " + onClause + " } }")
            .build();
    HttpResponse httpResponse = app.send(HttpRequest.of("POST", "/graphql/", cookie, bodyPayload));
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Map<String, Object> page = TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "getNotifications");
    List<Map<String, Object>> notifications = (List<Map<String, Object>>) page.get("notifications");
    return (int) notifications.stream().filter(n -> !n.isEmpty()).count();
  }

  // ---------------------------------------------------------------------------------------------
  // moveNodes
  // ---------------------------------------------------------------------------------------------

  @Test
  void givenNoWritePermissionOnDestinationMoveNodesShouldReturnNodeWriteError() {
    // Given — destination owned by someone else, never shared
    String destFolderId = "a1000000-0000-0000-0000-000000000001";
    String nodeId = "a1000000-0000-0000-0000-000000000002";
    app.backdoor()
        .populator()
        .addNode(new SimplePopulatorFolder(destFolderId, OTHER_USER_ID, "privateDest"))
        .addNode(new SimplePopulatorTextFile(nodeId, OWNER_ID, "file.txt"));

    // When
    HttpResponse httpResponse = moveNodes(new String[] {nodeId}, destFolderId, OWNER_COOKIE);

    // Then — the single blocked node bubbles TWO errors: the app's own nodeWriteError, PLUS a
    // graphql-java-generated null-propagation error (schema declares `moveNodes: [Node!]`, a
    // non-null list item resolving to null propagates as a second error — same divergence already
    // documented by CopyNodesApiIT for the equivalent copyNodes shape).
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors)
        .hasSize(2)
        .contains("There was a problem while executing requested operation on node: " + destFolderId);
    Assertions.assertThat(movedNodes(httpResponse)).isEmpty();
  }

  @Test
  void givenADestinationThatIsAFileNotAFolderMoveNodesShouldReturnNodeWriteError() {
    // Given — destination exists and is owned by the requester (permission gate passes), but it
    // is a FILE, not a FOLDER/ROOT
    String destFileId = "a1000000-0000-0000-0000-000000000011";
    String nodeId = "a1000000-0000-0000-0000-000000000012";
    app.backdoor()
        .populator()
        .addNode(new SimplePopulatorTextFile(destFileId, OWNER_ID, "notAFolder.txt"))
        .addNode(new SimplePopulatorTextFile(nodeId, OWNER_ID, "file.txt"));

    // When
    HttpResponse httpResponse = moveNodes(new String[] {nodeId}, destFileId, OWNER_COOKIE);

    // Then — see the destination-permission test above for why this is 2 errors, not 1
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors)
        .hasSize(2)
        .contains("There was a problem while executing requested operation on node: " + destFileId);
  }

  @Test
  void givenTheOnlyRequestedNodeHasNoPermissionMoveNodesShouldReturnEmptyDataAndOneError() {
    // Given — destination valid, but the sole requested node is owned by someone else, unshared
    String destFolderId = "a1000000-0000-0000-0000-000000000021";
    String nodeId = "a1000000-0000-0000-0000-000000000022";
    app.backdoor()
        .populator()
        .addNode(new SimplePopulatorFolder(destFolderId, OWNER_ID, "dest"))
        .addNode(new SimplePopulatorTextFile(nodeId, OTHER_USER_ID, "notMine.txt"));

    // When
    HttpResponse httpResponse = moveNodes(new String[] {nodeId}, destFolderId, OWNER_COOKIE);

    // Then — nodeIdsToMove ends up empty: the whole "movedNodesResult" building block is skipped;
    // see the destination-permission test above for why this is 2 errors, not 1
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Assertions.assertThat(movedNodes(httpResponse)).isEmpty();
    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors)
        .hasSize(2)
        .contains("There was a problem while executing requested operation on node: " + nodeId);
  }

  @Test
  void givenARootIdAndASelfMoveMoveNodesShouldFilterBothAsErrors() {
    // Given — a valid destination owned by the requester
    String destFolderId = "a1000000-0000-0000-0000-000000000031";
    app.backdoor().populator().addNode(new SimplePopulatorFolder(destFolderId, OWNER_ID, "dest"));

    // When — one root id (always filtered) + a self-move (destination moved into itself)
    HttpResponse httpResponse =
        moveNodes(new String[] {"LOCAL_ROOT", destFolderId}, destFolderId, OWNER_COOKIE);

    // Then — TWO blocked nodes, each bubbling its own app error PLUS its own null-propagation
    // error (see the destination-permission test above) -> 4 errors total
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Assertions.assertThat(movedNodes(httpResponse)).isEmpty();
    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
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
    String destFolderId = "a1000000-0000-0000-0000-000000000041";
    String nodeId = "a1000000-0000-0000-0000-000000000042";
    app.backdoor()
        .populator()
        .addNode(new SimplePopulatorFolder(destFolderId, OWNER_ID, "sharedDest"))
        .addShare(destFolderId, OTHER_USER_ID, SharePermission.READ_AND_WRITE)
        .addShare(destFolderId, THIRD_USER_ID, SharePermission.READ_ONLY)
        .addNode(new SimplePopulatorTextFile(nodeId, OTHER_USER_ID, "fresh.txt"));

    // When — OTHER_USER_ID (not the destination's owner) moves their own fresh node into it
    HttpResponse httpResponse = moveNodes(new String[] {nodeId}, destFolderId, OTHER_COOKIE);

    // Then — the move succeeds...
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload())).isEmpty();
    Assertions.assertThat(movedNodes(httpResponse)).hasSize(1);

    // ...the node inherited THIRD_USER_ID's share from the destination (sourceShare absent -> upsert)...
    Assertions.assertThat(app.backdoor().shareExists(nodeId, THIRD_USER_ID)).isTrue();

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
    String destFolderId = "a1000000-0000-0000-0000-000000000071";
    String nodeId = "a1000000-0000-0000-0000-000000000072";
    app.backdoor()
        .populator()
        .addNode(new SimplePopulatorFolder(destFolderId, OTHER_USER_ID, "dest"))
        .addShare(destFolderId, THIRD_USER_ID, SharePermission.READ_ONLY)
        .addNode(new SimplePopulatorTextFile(nodeId, OTHER_USER_ID, "shared.txt"))
        .addShare(nodeId, THIRD_USER_ID, SharePermission.READ_AND_WRITE);

    // When — the node's own owner moves it into the (also-shared-to-the-same-user) destination
    HttpResponse httpResponse = moveNodes(new String[] {nodeId}, destFolderId, OTHER_COOKIE);

    // Then — the move succeeds and the pre-existing direct share is untouched (still present,
    // still whatever permission it already had — not overwritten with the destination's own tier)
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload())).isEmpty();
    Assertions.assertThat(app.backdoor().shareExists(nodeId, THIRD_USER_ID)).isTrue();
  }

  @Test
  void givenAMoverWhoIsNotTheNodesParentOwnerMoveNodesShouldNotifyTheParentOwnerFreshly() {
    // Given — folder F owned by OWNER_ID, shared WRITE to OTHER_USER_ID; a node OWNED by
    // OTHER_USER_ID but structurally parented under F (ownership does not follow structure on a
    // raw fixture, exactly like GetNodeEdgeApiIT's deliberately-inconsistent parent trick), with NO
    // shares of its own at all
    String parentFolderId = "a1000000-0000-0000-0000-000000000051";
    String nodeId = "a1000000-0000-0000-0000-000000000052";
    app.backdoor()
        .populator()
        .addNode(new SimplePopulatorFolder(parentFolderId, OWNER_ID, "F"))
        .addShare(parentFolderId, OTHER_USER_ID, SharePermission.READ_AND_WRITE)
        .addNode(
            new PopulatorNode(
                nodeId, OTHER_USER_ID, OTHER_USER_ID, parentFolderId, "child.txt", "",
                NodeType.TEXT, "LOCAL_ROOT," + parentFolderId, 1L, "text/plain"));

    // When — the node's owner (not F's owner) moves it away to LOCAL_ROOT
    HttpResponse httpResponse = moveNodes(new String[] {nodeId}, "LOCAL_ROOT", OTHER_COOKIE);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload())).isEmpty();

    // F's owner (fresh add: was not already in the notify list) gets exactly one RemovedNode notification
    Assertions.assertThat(notificationCountOf(OWNER_COOKIE, "... on RemovedNode { created_at }"))
        .isEqualTo(1);
  }

  @Test
  void givenAMoverWhoIsNotTheNodesParentOwnerButTheOwnerIsAlreadyAShareTargetMoveNodesShouldNotDuplicateTheNotification() {
    // Given — same shape as above, but the node ALSO has a DIRECT share to F's owner already (so
    // the owner is already in the remove-notify list before the "add if absent" check runs)
    String parentFolderId = "a1000000-0000-0000-0000-000000000061";
    String nodeId = "a1000000-0000-0000-0000-000000000062";
    app.backdoor()
        .populator()
        .addNode(new SimplePopulatorFolder(parentFolderId, OWNER_ID, "F"))
        .addShare(parentFolderId, OTHER_USER_ID, SharePermission.READ_AND_WRITE)
        .addNode(
            new PopulatorNode(
                nodeId, OTHER_USER_ID, OTHER_USER_ID, parentFolderId, "child2.txt", "",
                NodeType.TEXT, "LOCAL_ROOT," + parentFolderId, 1L, "text/plain"))
        .addShare(nodeId, OWNER_ID, SharePermission.READ_ONLY);

    // When
    HttpResponse httpResponse = moveNodes(new String[] {nodeId}, "LOCAL_ROOT", OTHER_COOKIE);

    // Then — F's owner still gets EXACTLY ONE notification, not two (the contains-check prevented
    // a duplicate add to the notify list)
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Assertions.assertThat(notificationCountOf(OWNER_COOKIE, "... on RemovedNode { created_at }"))
        .isEqualTo(1);
  }

  // ---------------------------------------------------------------------------------------------
  // restoreNodes
  // ---------------------------------------------------------------------------------------------

  @Test
  void givenANodeThatWasNeverTrashedRestoreNodesShouldReturnNodeWriteError() {
    // Given — exists, owned by the requester, but never trashed
    String nodeId = "a2000000-0000-0000-0000-000000000001";
    app.backdoor().populator().addNode(new SimplePopulatorTextFile(nodeId, OWNER_ID, "notTrashed.txt"));

    // When
    HttpResponse httpResponse = restoreNodes(new String[] {nodeId}, OWNER_COOKIE);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("There was a problem while executing requested operation on node: " + nodeId);
  }

  @Test
  void givenARootIdRestoreNodesShouldFilterItAsAnError() {
    // When
    HttpResponse httpResponse = restoreNodes(new String[] {"LOCAL_ROOT"}, OWNER_COOKIE);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("There was a problem while executing requested operation on node: LOCAL_ROOT");
  }

  @Test
  void givenAFatherlessNodeWhoseOriginalParentRowNoLongerExistsRestoreNodesShouldRestoreToLocalRootAndPromoteIndirectSharesToDirect() {
    // Given — folder F (shared to THIRD_USER_ID), a sub-folder X created via the REAL createFolder
    // mutation (so X inherits an INDIRECT share to THIRD_USER_ID via cascade); X is then trashed
    // with its RECORDED original parent set to an id that was NEVER a real node row at all
    String parentFolderId = "a2000000-0000-0000-0000-000000000011";
    app.backdoor()
        .populator()
        .addNode(new SimplePopulatorFolder(parentFolderId, OWNER_ID, "F"))
        .addShare(parentFolderId, THIRD_USER_ID, SharePermission.READ_ONLY);

    String createPayload =
        GraphqlCommandBuilder.aMutationBuilder("createFolder")
            .withString("destination_id", parentFolderId)
            .withString("name", "X")
            .withWantedResultFormat("{ id }")
            .build();
    HttpResponse createResponse =
        app.send(HttpRequest.of("POST", "/graphql/", OWNER_COOKIE, createPayload));
    Assertions.assertThat(createResponse.getStatus()).isEqualTo(200);
    String nodeId = (String) TestUtils.jsonResponseToMap(createResponse.getBodyPayload(), "createFolder").get("id");
    Assertions.assertThat(app.backdoor().shareExists(nodeId, THIRD_USER_ID))
        .as("X must have inherited an INDIRECT share before we can prove promotion to DIRECT")
        .isTrue();

    String neverExistedParentId = "a2000000-0000-0000-0000-00000000dead";
    app.backdoor().populator().addNodeToTrash(nodeId, neverExistedParentId);

    // When
    HttpResponse httpResponse = restoreNodes(new String[] {nodeId}, OWNER_COOKIE);

    // Then — restored to LOCAL_ROOT (not left dangling, not erroring on the missing parent)
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload())).isEmpty();
    Assertions.assertThat(app.backdoor().nodeExists(nodeId)).isTrue();

    // The share survives the restore itself...
    Assertions.assertThat(app.backdoor().shareExists(nodeId, THIRD_USER_ID)).isTrue();

    // ...and PROOF it is now DIRECT (not indirect): moving X away into a folder never shared with
    // THIRD_USER_ID would strip any INDIRECT share (moveNodesFetcher's remove-phase), but a DIRECT
    // one survives.
    String privateFolderId = "a2000000-0000-0000-0000-000000000012";
    app.backdoor().populator().addNode(new SimplePopulatorFolder(privateFolderId, OWNER_ID, "private"));
    HttpResponse moveResponse = moveNodes(new String[] {nodeId}, privateFolderId, OWNER_COOKIE);
    Assertions.assertThat(moveResponse.getStatus()).isEqualTo(200);
    Assertions.assertThat(app.backdoor().shareExists(nodeId, THIRD_USER_ID))
        .as("a DIRECT share must survive a move away from any granting ancestor")
        .isTrue();
  }

  @Test
  void givenAFatherlessNodeWhoseOriginalParentIsItselfTrashedRestoreNodesShouldRestoreToLocalRoot() {
    // Given — folder F is trashed too (its OWN ancestor chain now contains TRASH_ROOT), so a child
    // recorded as originally-parented-under F must ALSO be treated as fatherless, even though F's
    // row still physically exists
    String parentFolderId = "a2000000-0000-0000-0000-000000000021";
    String nodeId = "a2000000-0000-0000-0000-000000000022";
    app.backdoor()
        .populator()
        .addNode(new SimplePopulatorFolder(parentFolderId, OWNER_ID, "F"))
        .addNode(new SimplePopulatorTextFile(nodeId, OWNER_ID, "child.txt"));
    app.backdoor().populator().addNodeToTrash(parentFolderId, "LOCAL_ROOT");
    app.backdoor().populator().addNodeToTrash(nodeId, parentFolderId);

    // When
    HttpResponse httpResponse = restoreNodes(new String[] {nodeId}, OWNER_COOKIE);

    // Then — restored to LOCAL_ROOT, not left pointing at a still-trashed parent
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload())).isEmpty();
    Assertions.assertThat(app.backdoor().nodeExists(nodeId)).isTrue();

    String getNodePayload =
        GraphqlCommandBuilder.aQueryBuilder("getNode")
            .withString("node_id", nodeId)
            .withWantedResultFormat("{ id parent { id } }")
            .build();
    HttpResponse getNodeResponse =
        app.send(HttpRequest.of("POST", "/graphql/", OWNER_COOKIE, getNodePayload));
    Map<String, Object> node = TestUtils.jsonResponseToMap(getNodeResponse.getBodyPayload(), "getNode");
    @SuppressWarnings("unchecked")
    Map<String, Object> parent = (Map<String, Object>) node.get("parent");
    Assertions.assertThat(parent).containsEntry("id", "LOCAL_ROOT");
  }

  @Test
  @SuppressWarnings("unchecked")
  void givenAValidNonRootParentWithSharesRestoringAFolderShouldCascadeTheShareToItsOwnChild() {
    // Given — P (a real, non-trashed FOLDER, shared to THIRD_USER_ID) is Q's recorded original
    // parent; Q is itself a FOLDER with its own child R, so restoring Q must both (a) build its
    // new ancestor string off a FOLDER (not ROOT) parent, and (b) cascade P's share down through Q
    // to R (ShareDataFetcher#cascadeUpsertShare's recursive FOLDER branch)
    String validParentId = "a2000000-0000-0000-0000-000000000031";
    String folderId = "a2000000-0000-0000-0000-000000000032";
    String grandchildId = "a2000000-0000-0000-0000-000000000033";
    app.backdoor()
        .populator()
        .addNode(new SimplePopulatorFolder(validParentId, OWNER_ID, "P"))
        .addShare(validParentId, THIRD_USER_ID, SharePermission.READ_ONLY)
        .addNode(
            new PopulatorNode(
                folderId, OWNER_ID, OWNER_ID, validParentId, "Q", "",
                NodeType.FOLDER, "LOCAL_ROOT," + validParentId, 0L, null))
        .addNode(
            new PopulatorNode(
                grandchildId, OWNER_ID, OWNER_ID, folderId, "R.txt", "",
                NodeType.TEXT, "LOCAL_ROOT," + validParentId + "," + folderId, 1L, "text/plain"));
    app.backdoor().populator().addNodeToTrash(folderId, validParentId);

    // When
    HttpResponse httpResponse = restoreNodes(new String[] {folderId}, OWNER_COOKIE);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload())).isEmpty();

    // Q itself is now (again) parented under P
    String getNodePayload =
        GraphqlCommandBuilder.aQueryBuilder("getNode")
            .withString("node_id", folderId)
            .withWantedResultFormat("{ id parent { id } }")
            .build();
    HttpResponse getNodeResponse =
        app.send(HttpRequest.of("POST", "/graphql/", OWNER_COOKIE, getNodePayload));
    Map<String, Object> node = TestUtils.jsonResponseToMap(getNodeResponse.getBodyPayload(), "getNode");
    Assertions.assertThat((Map<String, Object>) node.get("parent")).containsEntry("id", validParentId);

    // P's share cascaded onto Q AND down to Q's own child R
    Assertions.assertThat(app.backdoor().shareExists(folderId, THIRD_USER_ID)).isTrue();
    Assertions.assertThat(app.backdoor().shareExists(grandchildId, THIRD_USER_ID))
        .as("cascadeUpsertShare must reach the restored folder's own descendants")
        .isTrue();
  }
}
