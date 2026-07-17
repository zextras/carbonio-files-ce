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
import java.util.Optional;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Deepens {@code NodeDataFetcher} JaCoCo branch coverage for {@code trashNodes}, {@code
 * flagNodes}, and {@code deleteAllNodesAndBlobs} beyond Waves 0-6, per the measurement loop. Picked
 * from a line-by-line read of {@code core/target/jacoco-it-report/jacoco.xml}'s missed branches,
 * cross-checked against {@code TrashNodesApiIT}/{@code FlagNodesApiIT}/{@code
 * DeleteAllNodesAndBlobsApiIT} to avoid duplicating existing coverage.
 *
 * <ul>
 *   <li>{@code trashNodes}: the truster ≠ the trashed node's CURRENT parent's owner — same shape
 *       and same reachability trick as {@code MoveRestoreEdgeCasesApiIT}'s move-remove-notify
 *       tests (a node's owner does not have to match its structural parent's owner) — both the
 *       "owner not yet in the notify list" (fresh add) and "owner already there" (skip, avoid
 *       duplicate) outcomes of {@code !usersToNotify.contains(parent.getOwnerId())}.
 *   <li>{@code flagNodes}: a ROOT id in the request — the {@code getNodeType() != ROOT} filter
 *       (every existing {@code FlagNodesApiIT} test uses a plain file, never a root).
 *   <li>{@code deleteAllNodesAndBlobs}: PowerStore returns a null/empty bulk-delete response
 *       (neither success nor a thrown exception) — {@code DeleteNodesApiIT} already covers this
 *       exact shape for {@code deleteNodes}, but {@code deleteAllNodesAndBlobs} has its own,
 *       separate copy of the same null-check that no existing test reaches.
 * </ul>
 *
 * <p><b>Explicitly SKIPPED as unreachable (not attempted):</b> {@code createFolderFetcher}'s
 * analogous {@code !usersToNotify.contains(parent.getOwnerId())} check (line ~617) can NEVER
 * observe {@code true} (skip) — its notify list is seeded ONLY from {@code
 * createIndirectShare(parentId, ...)}, i.e. shares whose TARGET is a user other than {@code
 * parentId}'s own owner (a node's shares can never target its own owner — enforced by {@code
 * ShareDataFetcher#createShareFetcher}'s {@code targetUserId.equals(ownerId)} rejection), so the
 * parent's owner can never already be in that list by the time the check runs. This differs from
 * {@code trashNodes}/{@code moveNodes} (asserted above), whose notify lists are seeded from the
 * MOVED/TRASHED NODE's OWN shares — and a node's owner CAN differ from its current parent's owner
 * (ownership does not follow structure on move), so a share targeting the parent's owner is a
 * valid, constructible share on the child. A newly-confirmed dead branch, not previously
 * documented by the plan's §9 findings list.
 */
@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class DeleteTrashCopyEdgeCasesApiIT {

  static FilesTestApp app;

  private static final String OWNER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OTHER_USER_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String OWNER_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String OTHER_COOKIE = "ZM_AUTH_TOKEN=fake-token-b";

  @BeforeAll
  static void init() {
    app =
        QuarkusFilesTestAppBuilder.aFilesTestApp()
            .withDatabase()
            .withServiceDiscover()
            .withStorages()
            .withUserManagement(
                Map.of(
                    "fake-token", OWNER_ID,
                    "fake-token-b", OTHER_USER_ID))
            .build();
  }

  @AfterEach
  void cleanUp() {
    app.backdoor().resetDatabase();
    app.backdoor().clearTombstones();
    app.mocks().reset();
  }

  @AfterAll
  static void cleanUpAll() {
    app.close();
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

  private HttpResponse trashNodes(String nodeId, String cookie) {
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("trashNodes")
            .withListOfStrings("node_ids", new String[] {nodeId})
            .withWantedResultFormat("")
            .build();
    return app.send(HttpRequest.of("POST", "/graphql/", cookie, bodyPayload));
  }

  // ---------------------------------------------------------------------------------------------
  // trashNodes
  // ---------------------------------------------------------------------------------------------

  @Test
  void givenATrasherWhoIsNotTheNodesParentOwnerTrashNodesShouldNotifyTheParentOwnerFreshly() {
    // Given — folder F owned by OWNER_ID, shared WRITE to OTHER_USER_ID; a node OWNED by
    // OTHER_USER_ID but structurally parented under F, with no shares of its own
    String parentFolderId = "b1000000-0000-0000-0000-000000000001";
    String nodeId = "b1000000-0000-0000-0000-000000000002";
    app.backdoor()
        .populator()
        .addNode(new SimplePopulatorFolder(parentFolderId, OWNER_ID, "F"))
        .addShare(parentFolderId, OTHER_USER_ID, SharePermission.READ_AND_WRITE)
        .addNode(
            new PopulatorNode(
                nodeId, OTHER_USER_ID, OTHER_USER_ID, parentFolderId, "child.txt", "",
                NodeType.TEXT, "LOCAL_ROOT," + parentFolderId, 1L, "text/plain"));

    // When — the node's owner (not F's owner) trashes it
    HttpResponse httpResponse = trashNodes(nodeId, OTHER_COOKIE);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload())).isEmpty();
    Assertions.assertThat(notificationCountOf(OWNER_COOKIE, "... on RemovedNode { created_at }"))
        .isEqualTo(1);
  }

  @Test
  void givenATrasherWhoIsNotTheNodesParentOwnerButTheOwnerIsAlreadyAShareTargetTrashNodesShouldNotDuplicateTheNotification() {
    // Given — same shape, but the node ALSO has a DIRECT share to F's owner already
    String parentFolderId = "b1000000-0000-0000-0000-000000000011";
    String nodeId = "b1000000-0000-0000-0000-000000000012";
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
    HttpResponse httpResponse = trashNodes(nodeId, OTHER_COOKIE);

    // Then — F's owner still gets EXACTLY ONE notification
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Assertions.assertThat(notificationCountOf(OWNER_COOKIE, "... on RemovedNode { created_at }"))
        .isEqualTo(1);
  }

  // ---------------------------------------------------------------------------------------------
  // flagNodes
  // ---------------------------------------------------------------------------------------------

  @Test
  void givenARootIdFlagNodesShouldFilterItAsAnError() {
    // When
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("flagNodes")
            .withListOfStrings("node_ids", new String[] {"LOCAL_ROOT"})
            .withBoolean("flag", true)
            .withWantedResultFormat("")
            .build();
    HttpResponse httpResponse =
        app.send(HttpRequest.of("POST", "/graphql/", OWNER_COOKIE, bodyPayload));

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("There was a problem while executing requested operation on node: LOCAL_ROOT");
  }

  // ---------------------------------------------------------------------------------------------
  // deleteAllNodesAndBlobs
  // ---------------------------------------------------------------------------------------------

  @Test
  void givenNullResponseFromPowerStoreDeleteAllNodesAndBlobsShouldStillReturnTrueButKeepTheTombstone() {
    // Given
    String fileId = "b1000000-0000-0000-0000-000000000021";
    app.backdoor().populator().addNode(new SimplePopulatorTextFile(fileId, OWNER_ID, "file.txt"));
    app.mocks().storagesBulkDeleteReturnsNullResponse();

    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("deleteAllNodesAndBlobs")
            .withString("user_id", OWNER_ID)
            .withWantedResultFormat("")
            .build();
    List<Map.Entry<String, String>> headers = List.of(Map.entry("Internal", ""));

    // When
    HttpResponse httpResponse =
        app.send(HttpRequest.of("POST", "/graphql/", OWNER_COOKIE, headers, bodyPayload));

    // Then — DB-first: node deleted, mutation reports true, but a null/empty PowerStore response is
    // NOT treated as a success signal so the tombstone is kept for the purge retry loop
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Optional<Object> result =
        TestUtils.jsonResponseToValue(httpResponse.getBodyPayload(), "deleteAllNodesAndBlobs");
    Assertions.assertThat(result).contains(true);
    Assertions.assertThat(app.backdoor().nodeExists(fileId)).isFalse();
    Assertions.assertThat(app.backdoor().tombstoneCount())
        .as("null/empty PowerStore response must NOT be treated as success")
        .isEqualTo(1);
  }
}
