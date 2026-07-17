// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.acceptance;

import com.zextras.carbonio.files.FilesStackTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zextras.carbonio.files.Constants;
import com.zextras.carbonio.files.acceptance.seam.FilesTestApp;
import com.zextras.carbonio.files.acceptance.seam.impl.QuarkusFilesTestAppBuilder;
import com.zextras.carbonio.files.api.utilities.entities.PopulatorNode;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.utilities.http.HttpRequest;
import com.zextras.carbonio.files.utilities.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Public-link-specific ZIP-building branches of {@code rest.services.BlobService} that are NOT
 * reachable through the authenticated path (see {@link MultiDownloadZipApiIT}, which covers the
 * shared {@code createZip}/{@code addNodeToZip}/{@code getUniqueNameForZip} internals via the
 * authenticated route). {@link PublicDownloadMultipleApiIT} already covers the public
 * happy-path/basic-validation surface; this file targets the branches jacoco showed as uncovered
 * that are unique to {@code checkDownloadPublicMultiple}/{@code downloadPublicMultiple}'s own
 * accessChecker (link-validity + not-trashed, instead of permission-based) and to the
 * public-only {@code requesterId == null} branch of {@code checkDownloadMultipleInternal}'s
 * LOCAL_ROOT-alias handling.
 *
 * <p>See {@link MultiDownloadZipApiIT}'s class-level javadoc for the streamed-body / real-HTTP
 * transport seam-limitation note; the same {@code assertBodyContainsWhenObservable} pattern is
 * used here.
 */
@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class PublicMultiDownloadZipApiIT {

  static FilesTestApp app;
  static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private static final String USER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

  @BeforeAll
  static void init() {
    app =
        QuarkusFilesTestAppBuilder.aFilesTestApp()
            .withDatabase()
            .withServiceDiscover()
            .withUserManagement(Map.of())
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

  private void addFile(String id, String parentId, String ancestorIds, String name, long size) {
    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                id, USER_ID, USER_ID, parentId, name, "", NodeType.TEXT, ancestorIds, size, "text/plain"));
  }

  private void addFolder(String id, String parentId, String ancestorIds, String name) {
    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                id, USER_ID, USER_ID, parentId, name, "", NodeType.FOLDER, ancestorIds, 0L, null));
  }

  private HttpResponse checkPublicMultiple(List<String> nodeIds, String nodeLinkId) throws Exception {
    Map<String, Object> body = new HashMap<>();
    body.put("nodeIds", nodeIds);
    body.put("nodeLinkId", nodeLinkId);
    String jsonBody = OBJECT_MAPPER.writeValueAsString(body);
    List<Map.Entry<String, String>> headers = List.of(Map.entry("Content-Type", "application/json"));
    return app.sendForm(HttpRequest.of("POST", "/public/download-multiple/check", null, headers, jsonBody));
  }

  private HttpResponse downloadPublicMultiple(List<String> nodeIds, String nodeLinkId) throws Exception {
    String jsonArray = OBJECT_MAPPER.writeValueAsString(nodeIds);
    String requestBody =
        "nodeIds=" + URLEncoder.encode(jsonArray, StandardCharsets.UTF_8) + "&nodeLinkId=" + nodeLinkId;
    List<Map.Entry<String, String>> headers =
        List.of(Map.entry("Content-Type", "application/x-www-form-urlencoded"));
    return app.sendForm(HttpRequest.of("POST", "/public/download-multiple", null, headers, requestBody));
  }

  private void assertBodyContainsWhenObservable(HttpResponse response, String expectedSubstring) {
    if (response.getBodyPayload() != null) {
      Assertions.assertThat(response.getBodyPayload()).contains(expectedSubstring);
    }
  }

  private void assertBodyDoesNotContainWhenObservable(HttpResponse response, String unexpectedSubstring) {
    if (response.getBodyPayload() != null) {
      Assertions.assertThat(response.getBodyPayload()).doesNotContain(unexpectedSubstring);
    }
  }

  /**
   * {@code checkDownloadPublicMultiple}'s accessChecker is {@code
   * linkRepository.isLinkValidForNode(nodeLinkId, node) && !trashed} (link-scope validity), a
   * DIFFERENT check than the authenticated path's permission-based one (see {@code
   * LinkRepositoryEbean#isLinkValidForNode}: valid iff the node IS the link's node or one of its
   * ancestors). A node entirely outside the linked folder's tree fails it and is silently
   * skipped — same {@code continue} branch as the authenticated path, but a different reason.
   */
  @Test
  void givenNodeOutsideLinkScopeMixedWithValidNodeCheckDownloadPublicMultipleShouldReturn204SkippingIt()
      throws Exception {
    String folderId = "11111111-1111-1111-1111-1111c0000001";
    String insideFileId = "00000000-0000-0000-0000-00000000c001";
    String outsideFileId = "00000000-0000-0000-0000-00000000c002";
    String linkId = UUID.randomUUID().toString();
    String publicId = UUID.randomUUID().toString();

    addFolder(folderId, Constants.Db.RootId.LOCAL_ROOT, Constants.Db.RootId.LOCAL_ROOT, "linked-folder");
    addFile(
        insideFileId,
        folderId,
        Constants.Db.RootId.LOCAL_ROOT + "," + folderId,
        "inside.txt",
        10L);
    // A completely separate top-level file, outside the linked folder's ancestor chain.
    addFile(outsideFileId, Constants.Db.RootId.LOCAL_ROOT, Constants.Db.RootId.LOCAL_ROOT, "outside.txt", 10L);
    app.backdoor()
        .populator()
        .addLink(linkId, folderId, publicId, Optional.empty(), Optional.empty(), Optional.empty());

    HttpResponse response = checkPublicMultiple(List.of(insideFileId, outsideFileId), publicId);

    Assertions.assertThat(response.getStatus()).isEqualTo(204);
  }

  /**
   * Distinguishes the {@code checkDownloadMultipleInternal}'s own {@code nodeIds.isEmpty()} ->
   * {@code Optional.empty()} -> 404 branch from the "link not found" -> {@code Optional.empty()}
   * -> 404 branch already exercised by {@code PublicDownloadMultipleApiIT}'s
   * "givenEmptyNodeListTheDownloadMultipleShouldReturn404" (which uses a random, never-created
   * public id, so the request never reaches {@code checkDownloadMultipleInternal} at all). Here
   * the link genuinely exists and resolves, so the empty-list check inside {@code
   * checkDownloadMultipleInternal} is what actually fires.
   */
  @Test
  void givenEmptyNodeIdsWithAValidPublicLinkCheckDownloadPublicMultipleShouldReturn404() throws Exception {
    String folderId = "11111111-1111-1111-1111-1111c0000003";
    String linkId = UUID.randomUUID().toString();
    String publicId = UUID.randomUUID().toString();
    addFolder(folderId, Constants.Db.RootId.LOCAL_ROOT, Constants.Db.RootId.LOCAL_ROOT, "real-folder");
    app.backdoor()
        .populator()
        .addLink(linkId, folderId, publicId, Optional.empty(), Optional.empty(), Optional.empty());

    HttpResponse response = checkPublicMultiple(List.of(), publicId);

    Assertions.assertThat(response.getStatus()).isEqualTo(404);
  }

  /**
   * The LOCAL_ROOT-alias branch's {@code if (requesterId == null) return Optional.empty();} is
   * UNREACHABLE for the authenticated path (requester is never null there) — it only exists for
   * the public path, where {@code checkDownloadPublicMultiple} always passes {@code null} as the
   * requesterId. This is the only way to reach it.
   */
  @Test
  void givenLocalRootAliasWithAValidPublicLinkCheckDownloadPublicMultipleShouldReturn404()
      throws Exception {
    String folderId = "11111111-1111-1111-1111-1111c0000004";
    String linkId = UUID.randomUUID().toString();
    String publicId = UUID.randomUUID().toString();
    addFolder(folderId, Constants.Db.RootId.LOCAL_ROOT, Constants.Db.RootId.LOCAL_ROOT, "another-folder");
    app.backdoor()
        .populator()
        .addLink(linkId, folderId, publicId, Optional.empty(), Optional.empty(), Optional.empty());

    HttpResponse response = checkPublicMultiple(List.of(Constants.Db.RootId.LOCAL_ROOT), publicId);

    Assertions.assertThat(response.getStatus()).isEqualTo(404);
  }

  /**
   * NOTE on what this test actually exercises (corrected after investigation — see below):
   * {@code DatabasePopulator#addNodeToTrash} mirrors real production trashing ({@code
   * NodeDataFetcher}'s trash mutation, core lines ~872-876): both reparent the node to {@code
   * TRASH_ROOT} (changing {@code parentId}/{@code ancestorIds}), in addition to inserting the
   * {@code TrashedNode} marker row. Because {@code NodeRepository#getChildrenIds} filters purely
   * on {@code parent_id} (no anti-join against {@code TrashedNode}, no ancestor/TRASH_ROOT check),
   * a trashed child is excluded from {@code getChildrenIds(originalFolder)}'s result INCIDENTALLY,
   * one level above {@code addNodeToZip} — {@code BlobService}'s own {@code
   * !nodeRepository.getTrashedNode(node.getId()).isEmpty()} half of the public accessChecker
   * (lines 219-220) is never actually reached for a normally-trashed node, because
   * {@code isLinkValidForNode} would ALSO already be false (ancestorIds no longer include the
   * folder) and/or the node is never yielded as a child at all. This test still documents a real,
   * valuable behaviour (a folder download naturally omits a since-trashed sibling), but it is NOT
   * evidence that {@code addNodeToZip}'s accessChecker-level trashed-check is reachable. That
   * check appears to be defense-in-depth for a "TrashedNode row exists but the node was NOT
   * reparented" state that the sanctioned seam (whose only trash-simulating primitive,
   * {@code addNodeToTrash}, always reparents, matching production) cannot construct. Reported as a
   * seam-capability gap rather than faked with a nonstandard DB state.
   */
  @Test
  void givenTrashedChildInsideAPubliclyLinkedFolderDownloadPublicMultipleOmitsItFromTheZip()
      throws Exception {
    String folderId = "11111111-1111-1111-1111-1111c0000005";
    String keptFileId = "00000000-0000-0000-0000-00000000c005";
    String trashedFileId = "00000000-0000-0000-0000-00000000c006";
    String linkId = UUID.randomUUID().toString();
    String publicId = UUID.randomUUID().toString();

    addFolder(folderId, Constants.Db.RootId.LOCAL_ROOT, Constants.Db.RootId.LOCAL_ROOT, "public-folder");
    addFile(keptFileId, folderId, Constants.Db.RootId.LOCAL_ROOT + "," + folderId, "kept.txt", 10L);
    addFile(trashedFileId, folderId, Constants.Db.RootId.LOCAL_ROOT + "," + folderId, "trashed.txt", 10L);
    app.backdoor()
        .populator()
        .addLink(linkId, folderId, publicId, Optional.empty(), Optional.empty(), Optional.empty());
    app.backdoor().populator().addNodeToTrash(trashedFileId, folderId);
    app.mocks().storagesServesBlob(keptFileId, 1);

    HttpResponse response = downloadPublicMultiple(List.of(folderId), publicId);

    Assertions.assertThat(response.getStatus()).isEqualTo(200);
    app.mocks().verifyStoragesDownloaded(keptFileId, 1);
    assertBodyContainsWhenObservable(response, "kept.txt");
    assertBodyDoesNotContainWhenObservable(response, "trashed.txt");
  }
}
