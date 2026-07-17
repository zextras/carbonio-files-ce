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
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
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
 * Task 2.5 of the acceptance coverage-expansion plan: the {@code copyNodes} mutation (bound to
 * {@code NodeDataFetcher#copyNodesFetcher}), driven through {@code Mocks#storagesCopySucceeds}/
 * {@code storagesCopyFails} and seeded via {@code PopulatorNode}/{@code DatabasePopulator}.
 *
 * <p><b>Reading the source (behaviour this class pins down):</b>
 *
 * <ul>
 *   <li>The requester needs {@code READ_AND_WRITE} on {@code destination_id}, which must resolve
 *       to an existing {@code FOLDER}/{@code ROOT} node. Both a non-existent destination and an
 *       existing-but-wrong-type destination fall through the SAME top-level {@code if}/{@code
 *       else} into the SAME single-element {@code nodeWriteError(destination_id)} result — there
 *       is no separate not-found branch here (unlike {@code createFolder}'s two-branch shape).
 *   <li>Each source node only needs {@code canRead()} (a plain {@code READ_ONLY} share suffices).
 *   <li>A source {@code FOLDER} is excluded from the copy (and reported via {@code
 *       nodeWriteError(sourceId)}, NOT a distinct "descendant" error) when the destination is
 *       nested somewhere under it (i.e. the destination's ancestor chain contains the source's
 *       id) — UNLESS the source's own current parent IS the destination, which is the explicit
 *       "copy a folder into its own parent" exception carved out in the filter condition.
 *   <li>Name-clash handling reuses {@code searchAlternativeName} exactly like {@code createFolder}/
 *       upload (" (1)" suffix, extension preserved separately for files).
 *   <li>A filestore-copy failure ({@code copyFile}'s {@code Try#onFailure}) deletes the just-created
 *       node row and reports {@code nodeCopyError(sourceId, sourceVersion, path)} — using the
 *       SOURCE node's id/version, not the (deleted) destination copy's. This degrades gracefully,
 *       unlike {@code cloneVersion}'s hard abort on the same failure (a separate, already-filed
 *       finding — not exercised here).
 *   <li>Both single-element-error shapes above (destination-rejected and per-source-node
 *       rejected/failed) leave the {@code copyNodes} GraphQL list value itself {@code null} (the
 *       schema declares {@code copyNodes: [Node!]}, so a single non-null list item resolving to
 *       null propagates to the nearest nullable ancestor, the list field) while the top-level
 *       {@code errors} array still carries the message — asserted below via the {@code errors}
 *       array and via an EMPTY {@code data} extraction (which reads the same whether the raw JSON
 *       is {@code null} or {@code []}), not by asserting the raw JSON shape of {@code data} itself.
 * </ul>
 */
@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class CopyNodesApiIT {

  static FilesTestApp app;

  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OTHER_USER_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String REQUESTER_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String OTHER_COOKIE = "ZM_AUTH_TOKEN=fake-token-b";

  @BeforeAll
  static void init() {
    app =
        QuarkusFilesTestAppBuilder.aFilesTestApp()
            .withDatabase()
            .withServiceDiscover()
            .withUserManagement(
                Map.of(
                    "fake-token", REQUESTER_ID,
                    "fake-token-b", OTHER_USER_ID))
            .withStorages()
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

  private HttpResponse copyNodes(String[] nodeIds, String destinationId, String cookie) {
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("copyNodes")
            .withListOfStrings("node_ids", nodeIds)
            .withString("destination_id", destinationId)
            .withWantedResultFormat(
                "{ id name parent { id } owner { id } ... on File { extension } }")
            .build();
    HttpRequest httpRequest = HttpRequest.of("POST", "/graphql/", cookie, bodyPayload);
    return app.send(httpRequest);
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> copiedNodes(HttpResponse httpResponse) {
    Map<String, Object> page =
        TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "copyNodes");
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
    HttpResponse httpResponse = app.send(HttpRequest.of("POST", "/graphql/", cookie, bodyPayload));
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Map<String, Object> node = TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "getNode");
    Map<String, Object> children = (Map<String, Object>) node.get("children");
    return (List<Map<String, Object>>) children.get("nodes");
  }

  @Test
  void givenAPlainFileCopyItShouldCreateACopyInDestinationAndLeaveTheOriginalIntact() {
    // Given
    String sourceId = "40000000-0000-0000-0000-000000000001";
    String destFolderId = "40000000-0000-0000-0000-000000000002";
    app.backdoor()
        .populator()
        .addNode(new SimplePopulatorTextFile(sourceId, REQUESTER_ID, "source.txt"))
        .addNode(new SimplePopulatorFolder(destFolderId, REQUESTER_ID, "dest"));
    app.mocks().storagesCopySucceeds();

    // When
    HttpResponse httpResponse = copyNodes(new String[] {sourceId}, destFolderId, REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload())).isEmpty();
    List<Map<String, Object>> copied = copiedNodes(httpResponse);
    Assertions.assertThat(copied).hasSize(1);
    Map<String, Object> copiedNode = copied.get(0);
    Assertions.assertThat(copiedNode.get("id")).isNotEqualTo(sourceId);
    Assertions.assertThat(copiedNode).containsEntry("name", "source").containsEntry("extension", "txt");
    Assertions.assertThat((Map<String, Object>) copiedNode.get("parent")).containsEntry("id", destFolderId);

    // the source is a COPY, not a move: it still exists, unchanged, at its original location
    Assertions.assertThat(app.backdoor().nodeExists(sourceId)).isTrue();
    Assertions.assertThat(childrenOf("LOCAL_ROOT", REQUESTER_COOKIE))
        .extracting(node -> node.get("id"))
        .contains(sourceId);
  }

  @Test
  void givenAFolderWithNestedContentCopyShouldCascadeAllDescendants() {
    // Given — srcFolder/child.txt, srcFolder/subFolder/grandchild.txt
    String srcFolderId = "40000000-0000-0000-0000-000000000101";
    String childFileId = "40000000-0000-0000-0000-000000000102";
    String subFolderId = "40000000-0000-0000-0000-000000000103";
    String grandchildFileId = "40000000-0000-0000-0000-000000000104";
    String destFolderId = "40000000-0000-0000-0000-000000000105";
    app.backdoor()
        .populator()
        .addNode(new SimplePopulatorFolder(srcFolderId, REQUESTER_ID, "srcFolder"))
        .addNode(
            new PopulatorNode(
                childFileId, REQUESTER_ID, REQUESTER_ID, srcFolderId, "child.txt", "",
                NodeType.TEXT, "LOCAL_ROOT," + srcFolderId, 1L, "text/plain"))
        .addNode(
            new PopulatorNode(
                subFolderId, REQUESTER_ID, REQUESTER_ID, srcFolderId, "subFolder", "",
                NodeType.FOLDER, "LOCAL_ROOT," + srcFolderId, 0L, null))
        .addNode(
            new PopulatorNode(
                grandchildFileId, REQUESTER_ID, REQUESTER_ID, subFolderId, "grandchild.txt", "",
                NodeType.TEXT, "LOCAL_ROOT," + srcFolderId + "," + subFolderId, 1L, "text/plain"))
        .addNode(new SimplePopulatorFolder(destFolderId, REQUESTER_ID, "dest2"));
    app.mocks().storagesCopySucceeds();

    // When
    HttpResponse httpResponse = copyNodes(new String[] {srcFolderId}, destFolderId, REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    List<Map<String, Object>> copied = copiedNodes(httpResponse);
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
    Assertions.assertThat(copiedSubChildren).extracting(node -> node.get("name")).containsExactly("grandchild");

    // the original subtree is untouched
    Assertions.assertThat(app.backdoor().nodeExists(srcFolderId)).isTrue();
    Assertions.assertThat(app.backdoor().nodeExists(childFileId)).isTrue();
    Assertions.assertThat(app.backdoor().nodeExists(subFolderId)).isTrue();
    Assertions.assertThat(app.backdoor().nodeExists(grandchildFileId)).isTrue();
  }

  @Test
  void givenANameClashInDestinationCopyShouldDedupTheName() {
    // Given — destination already has a file named "clash.txt"
    String destFolderId = "40000000-0000-0000-0000-000000000201";
    String existingId = "40000000-0000-0000-0000-000000000202";
    String sourceId = "40000000-0000-0000-0000-000000000203";
    app.backdoor()
        .populator()
        .addNode(new SimplePopulatorFolder(destFolderId, REQUESTER_ID, "dest3"))
        .addNode(
            new PopulatorNode(
                existingId, REQUESTER_ID, REQUESTER_ID, destFolderId, "clash.txt", "",
                NodeType.TEXT, "LOCAL_ROOT," + destFolderId, 1L, "text/plain"))
        .addNode(new SimplePopulatorTextFile(sourceId, REQUESTER_ID, "clash.txt"));
    app.mocks().storagesCopySucceeds();

    // When
    HttpResponse httpResponse = copyNodes(new String[] {sourceId}, destFolderId, REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    List<Map<String, Object>> copied = copiedNodes(httpResponse);
    Assertions.assertThat(copied).hasSize(1);
    Assertions.assertThat(copied.get(0)).containsEntry("name", "clash (1)").containsEntry("extension", "txt");
  }

  @Test
  void givenNoNameClashCopyShouldKeepTheOriginalName() {
    // Given — destination has an unrelated file; no clash with the source's name
    String destFolderId = "40000000-0000-0000-0000-000000000211";
    String existingId = "40000000-0000-0000-0000-000000000212";
    String sourceId = "40000000-0000-0000-0000-000000000213";
    app.backdoor()
        .populator()
        .addNode(new SimplePopulatorFolder(destFolderId, REQUESTER_ID, "dest4"))
        .addNode(
            new PopulatorNode(
                existingId, REQUESTER_ID, REQUESTER_ID, destFolderId, "other.txt", "",
                NodeType.TEXT, "LOCAL_ROOT," + destFolderId, 1L, "text/plain"))
        .addNode(new SimplePopulatorTextFile(sourceId, REQUESTER_ID, "unique.txt"));
    app.mocks().storagesCopySucceeds();

    // When
    HttpResponse httpResponse = copyNodes(new String[] {sourceId}, destFolderId, REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    List<Map<String, Object>> copied = copiedNodes(httpResponse);
    Assertions.assertThat(copied).hasSize(1);
    Assertions.assertThat(copied.get(0)).containsEntry("name", "unique").containsEntry("extension", "txt");
  }

  @Test
  void givenSourceWithOnlyReadPermissionCopyShouldSucceed() {
    // Given — source owned by OTHER_USER_ID, shared READ_ONLY (no write) with the requester
    String sourceId = "40000000-0000-0000-0000-000000000301";
    String destFolderId = "40000000-0000-0000-0000-000000000302";
    app.backdoor()
        .populator()
        .addNode(new SimplePopulatorTextFile(sourceId, OTHER_USER_ID, "shared.txt"))
        .addShare(sourceId, REQUESTER_ID, ACL.SharePermission.READ_ONLY)
        .addNode(new SimplePopulatorFolder(destFolderId, REQUESTER_ID, "dest5"));
    app.mocks().storagesCopySucceeds();

    // When
    HttpResponse httpResponse = copyNodes(new String[] {sourceId}, destFolderId, REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload())).isEmpty();
    Assertions.assertThat(copiedNodes(httpResponse)).hasSize(1);
  }

  @Test
  void givenCopyIntoOwnDescendantItShouldBeBlockedWithNodeWriteError() {
    // Given — parentF contains childF; copying parentF INTO childF would nest it inside its own
    // descendant
    String parentFolderId = "40000000-0000-0000-0000-000000000401";
    String childFolderId = "40000000-0000-0000-0000-000000000402";
    app.backdoor()
        .populator()
        .addNode(new SimplePopulatorFolder(parentFolderId, REQUESTER_ID, "parentF"))
        .addNode(
            new PopulatorNode(
                childFolderId, REQUESTER_ID, REQUESTER_ID, parentFolderId, "childF", "",
                NodeType.FOLDER, "LOCAL_ROOT," + parentFolderId, 0L, null));

    // When
    HttpResponse httpResponse =
        copyNodes(new String[] {parentFolderId}, childFolderId, REQUESTER_COOKIE);

    // Then — the single blocked source node bubbles TWO errors: the app's own nodeWriteError, PLUS
    // a graphql-java-generated null-propagation error (see class javadoc) because the schema
    // declares `copyNodes: [Node!]` (non-null items) and this one item resolved with a null value.
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors)
        .hasSize(2)
        .contains("There was a problem while executing requested operation on node: " + parentFolderId);
    Assertions.assertThat(copiedNodes(httpResponse)).isEmpty();
    Assertions.assertThat(childrenOf(childFolderId, REQUESTER_COOKIE)).isEmpty();
  }

  @Test
  void givenCopyIntoItsOwnCurrentParentItShouldBeAllowedAndDedupTheName() {
    // Given — childF7 already sits inside parentF7; copying it back into parentF7 (its OWN
    // current parent) is the explicit exception to the descendant-block rule, and collides with
    // itself by name
    String parentFolderId = "40000000-0000-0000-0000-000000000411";
    String folderId = "40000000-0000-0000-0000-000000000412";
    app.backdoor()
        .populator()
        .addNode(new SimplePopulatorFolder(parentFolderId, REQUESTER_ID, "parentF7"))
        .addNode(
            new PopulatorNode(
                folderId, REQUESTER_ID, REQUESTER_ID, parentFolderId, "childF7", "",
                NodeType.FOLDER, "LOCAL_ROOT," + parentFolderId, 0L, null));
    app.mocks().storagesCopySucceeds();

    // When
    HttpResponse httpResponse = copyNodes(new String[] {folderId}, parentFolderId, REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload())).isEmpty();
    List<Map<String, Object>> copied = copiedNodes(httpResponse);
    Assertions.assertThat(copied).hasSize(1);
    Assertions.assertThat(copied.get(0).get("id")).isNotEqualTo(folderId);
    Assertions.assertThat(copied.get(0)).containsEntry("name", "childF7 (1)");
  }

  @Test
  void givenANonExistentDestinationCopyShouldReturnNodeWriteError() {
    // Given
    String sourceId = "40000000-0000-0000-0000-000000000501";
    String nonExistentDest = "40000000-0000-0000-0000-00000000ffff";
    app.backdoor().populator().addNode(new SimplePopulatorTextFile(sourceId, REQUESTER_ID, "src8.txt"));

    // When
    HttpResponse httpResponse = copyNodes(new String[] {sourceId}, nonExistentDest, REQUESTER_COOKIE);

    // Then — see the descendant-block test above for why this is 2 errors, not 1
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors)
        .hasSize(2)
        .contains("There was a problem while executing requested operation on node: " + nonExistentDest);
    Assertions.assertThat(copiedNodes(httpResponse)).isEmpty();
  }

  @Test
  void givenADestinationThatIsAFileNotAFolderCopyShouldReturnTheSameNodeWriteError() {
    // Given — destination exists and the requester owns it (so permission alone would pass), but
    // it is a FILE, not a FOLDER/ROOT
    String sourceId = "40000000-0000-0000-0000-000000000511";
    String destFileId = "40000000-0000-0000-0000-000000000512";
    app.backdoor()
        .populator()
        .addNode(new SimplePopulatorTextFile(sourceId, REQUESTER_ID, "src9.txt"))
        .addNode(new SimplePopulatorTextFile(destFileId, REQUESTER_ID, "notAFolder.txt"));

    // When
    HttpResponse httpResponse = copyNodes(new String[] {sourceId}, destFileId, REQUESTER_COOKIE);

    // Then — see the descendant-block test above for why this is 2 errors, not 1
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors)
        .hasSize(2)
        .contains("There was a problem while executing requested operation on node: " + destFileId);
  }

  @Test
  void givenNoWritePermissionOnDestinationCopyShouldReturnNodeWriteError() {
    // Given — destination folder owned by OTHER_USER_ID, never shared with the requester
    String sourceId = "40000000-0000-0000-0000-000000000521";
    String destFolderId = "40000000-0000-0000-0000-000000000522";
    app.backdoor()
        .populator()
        .addNode(new SimplePopulatorTextFile(sourceId, REQUESTER_ID, "src10.txt"))
        .addNode(new SimplePopulatorFolder(destFolderId, OTHER_USER_ID, "privateDest"));

    // When
    HttpResponse httpResponse = copyNodes(new String[] {sourceId}, destFolderId, REQUESTER_COOKIE);

    // Then — see the descendant-block test above for why this is 2 errors, not 1
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors)
        .hasSize(2)
        .contains("There was a problem while executing requested operation on node: " + destFolderId);
  }

  @Test
  void givenAFilestoreCopyFailureCopyShouldReturnNodeCopyErrorAndLeaveNoOrphan() {
    // Given
    String sourceId = "40000000-0000-0000-0000-000000000601";
    String destFolderId = "40000000-0000-0000-0000-000000000602";
    app.backdoor()
        .populator()
        .addNode(new SimplePopulatorTextFile(sourceId, REQUESTER_ID, "src11.txt"))
        .addNode(new SimplePopulatorFolder(destFolderId, REQUESTER_ID, "dest11"));
    app.mocks().storagesCopyFails();

    // When
    HttpResponse httpResponse = copyNodes(new String[] {sourceId}, destFolderId, REQUESTER_COOKIE);

    // Then — copyFile's Try#onFailure deletes the just-created node row and reports the error
    // using the SOURCE node's id/version (a freshly-seeded file's current version is 1). See the
    // descendant-block test above for why this is 2 errors, not 1.
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors)
        .hasSize(2)
        .contains("There was a problem while copying the node " + sourceId + " with version 1");
    Assertions.assertThat(copiedNodes(httpResponse)).isEmpty();

    // the source is untouched, and no orphan row was left behind in the destination
    Assertions.assertThat(app.backdoor().nodeExists(sourceId)).isTrue();
    Assertions.assertThat(childrenOf(destFolderId, REQUESTER_COOKIE)).isEmpty();
  }

  @Test
  void givenACopyIntoASharedFolderItShouldNotifyTheShareTargetWithAddedNodeTypeCopy() {
    // Given — destination folder owned by the requester, shared with OTHER_USER_ID
    String destFolderId = "40000000-0000-0000-0000-000000000701";
    String sourceId = "40000000-0000-0000-0000-000000000702";
    app.backdoor()
        .populator()
        .addNode(new SimplePopulatorFolder(destFolderId, REQUESTER_ID, "sharedDest12"))
        .addShare(destFolderId, OTHER_USER_ID, ACL.SharePermission.READ_AND_WRITE)
        .addNode(new SimplePopulatorTextFile(sourceId, REQUESTER_ID, "toCopy12.txt"));
    app.mocks().storagesCopySucceeds();

    // When
    HttpResponse copyResponse = copyNodes(new String[] {sourceId}, destFolderId, REQUESTER_COOKIE);
    Assertions.assertThat(copyResponse.getStatus()).isEqualTo(200);

    String notificationsPayload =
        GraphqlCommandBuilder.aQueryBuilder("getNotifications")
            .withBoolean("update_last_seen", true)
            .withWantedResultFormat(
                "{ notifications { ... on AddedNode { added_node_type added_node { node_id } } } }")
            .build();
    HttpResponse notificationsResponse =
        app.send(HttpRequest.of("POST", "/graphql/", OTHER_COOKIE, notificationsPayload));

    // Then
    Assertions.assertThat(notificationsResponse.getStatus()).isEqualTo(200);
    Map<String, Object> page =
        TestUtils.jsonResponseToMap(notificationsResponse.getBodyPayload(), "getNotifications");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> notifications = (List<Map<String, Object>>) page.get("notifications");
    Assertions.assertThat(notifications).hasSize(1);
    Assertions.assertThat(notifications.get(0)).containsEntry("added_node_type", "COPY");
  }
}
