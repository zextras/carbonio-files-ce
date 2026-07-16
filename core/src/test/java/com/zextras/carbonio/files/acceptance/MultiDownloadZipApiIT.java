// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zextras.carbonio.files.Constants;
import com.zextras.carbonio.files.acceptance.seam.FilesTestApp;
import com.zextras.carbonio.files.acceptance.seam.impl.GuiceNettyFilesTestAppBuilder;
import com.zextras.carbonio.files.api.utilities.entities.PopulatorNode;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.utilities.http.HttpRequest;
import com.zextras.carbonio.files.utilities.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * MEASURE-GUIDED coverage-deepening of the multi-download ZIP internals in {@code
 * rest.services.BlobService} ({@code checkDownloadMultipleInternal}, {@code createZip}, {@code
 * addNodeToZip}/{@code addFolderToZip}/{@code addFileToZip}, {@code getUniqueNameForZip}) and
 * {@code rest.controllers.BlobController} ({@code checkDownloadMultiple}, {@code
 * downloadMultiple}). {@link DownloadMultipleApiIT} already covers the happy-path/basic-validation
 * surface of {@code /download-multiple}; this file targets branches that jacoco showed as
 * genuinely uncovered: the {@code /download-multiple/check} endpoint (0% branch coverage before
 * this file — no existing acceptance test hits it at all), and the ZIP-building internals
 * (duplicate-name disambiguation, recursive folder zipping, single-vs-multi naming, silent
 * skip-on-inaccessible-node).
 *
 * <p><b>Seam limitation (body content):</b> {@code BlobController#downloadMultiple}/{@code
 * checkDownloadMultiple}'s success response streams its body in separate Netty frames (a
 * headers-only {@code DefaultHttpResponse} then content chunks — see {@code
 * NettyBufferWriter#writePipedStream}), never as one {@code DefaultFullHttpResponse}. The embedded
 * transport's {@code EmbeddedChannel#readOutbound()} only ever captures the first frame, so {@code
 * httpResponse.getBodyPayload()} is ALWAYS {@code null} for these two routes on the embedded
 * transport (pre-existing gap, see {@code AuthenticatedDownloadApiIT}'s class-level FINDING — not
 * introduced here). On the real-HTTP transport ({@code -Dfiles.test.transport=http}), {@code
 * java.net.http.HttpClient} drains the full body over the socket and decodes it with {@code
 * BodyHandlers.ofString(UTF_8)}. That decoding is lossy for the ZIP's compressed binary payload,
 * but every byte {@code < 0x80} (i.e. every plain-ASCII byte, which is exactly what our ZIP entry
 * names are) decodes to itself regardless of what invalid multi-byte sequences precede it — the
 * UTF-8 "maximal subpart" replacement algorithm always resyncs at the next byte and can never
 * mis-consume a low-ASCII byte as a continuation byte. So entry-name substrings survive the lossy
 * decode intact and ARE reliably assertable on the real-HTTP transport; they are simply skipped
 * (not asserted, not faked) whenever the body is unobservable (embedded transport). Every test
 * additionally verifies via {@code Mocks#verifyStoragesDownloaded}/{@code
 * verifyStoragesNeverDownloaded}, which is transport-invariant proof of which blobs the ZIP
 * builder actually fetched.
 */
class MultiDownloadZipApiIT {

  static FilesTestApp app;
  static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OTHER_USER_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String REQUESTER_COOKIE = "ZM_AUTH_TOKEN=fake-token";

  @BeforeAll
  static void init() {
    app =
        GuiceNettyFilesTestAppBuilder.aFilesTestApp()
            .withDatabase()
            .withServiceDiscover()
            .withUserManagement(Map.of("fake-token", REQUESTER_ID))
            .withStorages()
            .build();
  }

  @AfterEach
  void cleanUp() {
    app.backdoor().resetDatabase();
    app.mocks().reset();
    app.mocks().setMaxDownloadableSizeMb(null);
  }

  @AfterAll
  static void cleanUpAll() {
    app.close();
  }

  // ---------------------------------------------------------------------------------------
  // helpers
  // ---------------------------------------------------------------------------------------

  private HttpResponse checkMultiple(String jsonBody) {
    List<Map.Entry<String, String>> headers = List.of(Map.entry("Content-Type", "application/json"));
    return app.sendForm(HttpRequest.of("POST", "/download-multiple/check", REQUESTER_COOKIE, headers, jsonBody));
  }

  private String checkBodyOf(List<String> nodeIds) throws Exception {
    return OBJECT_MAPPER.writeValueAsString(Map.of("nodeIds", nodeIds));
  }

  private HttpResponse downloadMultipleForm(String rawBodyPayload) {
    List<Map.Entry<String, String>> headers =
        List.of(Map.entry("Content-Type", "application/x-www-form-urlencoded"));
    return app.sendForm(HttpRequest.of("POST", "/download-multiple", REQUESTER_COOKIE, headers, rawBodyPayload));
  }

  private HttpResponse downloadMultiple(List<String> nodeIds) throws Exception {
    String jsonArray = OBJECT_MAPPER.writeValueAsString(nodeIds);
    String requestBody = "nodeIds=" + URLEncoder.encode(jsonArray, StandardCharsets.UTF_8);
    return downloadMultipleForm(requestBody);
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

  private void addFile(String id, String parentId, String ancestorIds, String name, long size) {
    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                id, REQUESTER_ID, REQUESTER_ID, parentId, name, "", NodeType.TEXT, ancestorIds, size,
                "text/plain"));
  }

  private void addFolder(String id, String parentId, String ancestorIds, String name) {
    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                id, REQUESTER_ID, REQUESTER_ID, parentId, name, "", NodeType.FOLDER, ancestorIds, 0L, null));
  }

  // =========================================================================================
  // Section A: /download-multiple/check (BlobController#checkDownloadMultiple) — was 0% covered
  // =========================================================================================

  @Test
  void givenValidNodeIdsCheckDownloadMultipleShouldReturn204AndNeverTouchStorages() throws Exception {
    String fileId = "00000000-0000-0000-0000-00000000a001";
    addFile(fileId, Constants.Db.RootId.LOCAL_ROOT, Constants.Db.RootId.LOCAL_ROOT, "file.txt", 10L);

    HttpResponse response = checkMultiple(checkBodyOf(List.of(fileId)));

    Assertions.assertThat(response.getStatus()).isEqualTo(204);
    // /download-multiple/check only validates access + size; it never fetches a blob.
    app.mocks().verifyStoragesNeverDownloaded();
  }

  @Test
  void givenMissingBodyCheckDownloadMultipleShouldReturn400() {
    HttpResponse response = checkMultiple(null);

    Assertions.assertThat(response.getStatus()).isEqualTo(400);
  }

  /**
   * FINDING (real production behaviour, not a test-harness artifact): {@code
   * BlobController#checkDownloadMultiple}'s JSON-parse-failure branch wraps the caught {@code
   * JsonProcessingException} as the CAUSE of the {@code IllegalArgumentException} it fires
   * (unlike every other {@code IllegalArgumentException} in this method, and unlike its sibling
   * {@code downloadMultiple}'s equivalent form-parsing catch, neither of which set a cause).
   * {@code ExceptionsHandler#exceptionCaught} unconditionally unwraps to {@code
   * cause.getCause()} before classifying the status code, so it reclassifies based on the
   * INNER {@code JsonProcessingException} — which maps to 500 — instead of the outer, intended
   * {@code IllegalArgumentException} -> 400. Net effect: an invalid JSON body on {@code
   * /download-multiple/check} returns 500 Internal Server Error, not the 400 the message text
   * ("Can't parse JSON body...") implies. Verified reproducible in isolation (not test order/
   * classloading). Not fixed here (src/main is out of scope) — asserted as the real behaviour.
   */
  @Test
  void givenInvalidJsonBodyCheckDownloadMultipleActuallyReturns500NotTheIntended400() {
    HttpResponse response = checkMultiple("this-is-not-json");

    Assertions.assertThat(response.getStatus()).isEqualTo(500);
  }

  @Test
  void givenJsonBodyMissingNodeIdsFieldCheckDownloadMultipleShouldReturn400() {
    HttpResponse response = checkMultiple("{\"foo\":\"bar\"}");

    Assertions.assertThat(response.getStatus()).isEqualTo(400);
  }

  @Test
  void givenEmptyNodeIdsArrayCheckDownloadMultipleShouldReturn400() {
    HttpResponse response = checkMultiple("{\"nodeIds\":[]}");

    Assertions.assertThat(response.getStatus()).isEqualTo(400);
  }

  @Test
  void givenLocalRootWithOtherNodeCheckDownloadMultipleShouldReturn400() throws Exception {
    String fileId = "00000000-0000-0000-0000-00000000a002";
    addFile(fileId, Constants.Db.RootId.LOCAL_ROOT, Constants.Db.RootId.LOCAL_ROOT, "file.txt", 10L);

    HttpResponse response = checkMultiple(checkBodyOf(List.of(Constants.Db.RootId.LOCAL_ROOT, fileId)));

    Assertions.assertThat(response.getStatus()).isEqualTo(400);
  }

  /**
   * FINDING: {@code BlobService#checkDownloadMultipleInternal} silently {@code continue}s past
   * node ids that do not exist (or are inaccessible) rather than failing the whole request — the
   * method ALWAYS returns {@code Optional.of(nodes)} (possibly an empty list) once past the
   * up-front validation, and never {@code Optional.empty()} for an authenticated caller (that
   * outcome is reserved for the LOCAL_ROOT-alias-with-null-requester case, which cannot happen for
   * an authenticated request). Consequently {@code BlobController#checkDownloadMultiple}'s {@code
   * .orElseThrow(() -> new NoSuchElementException(...))} — whose message promises "some nodes do
   * not exist or user lacks permission" — is DEAD CODE for the authenticated endpoint: requesting
   * ONLY non-existent ids still returns 204, not 404.
   */
  @Test
  void givenOnlyNonExistentNodeIdsCheckDownloadMultipleShouldReturn204NotFound() throws Exception {
    String nonExistentId = "00000000-0000-0000-0000-0000dead0001";

    HttpResponse response = checkMultiple(checkBodyOf(List.of(nonExistentId)));

    Assertions.assertThat(response.getStatus()).isEqualTo(204);
  }

  @Test
  void givenMixOfValidAndNonExistentNodeIdsCheckDownloadMultipleShouldReturn204SkippingTheInvalidOne()
      throws Exception {
    String fileId = "00000000-0000-0000-0000-00000000a003";
    String nonExistentId = "00000000-0000-0000-0000-0000dead0002";
    addFile(fileId, Constants.Db.RootId.LOCAL_ROOT, Constants.Db.RootId.LOCAL_ROOT, "file.txt", 10L);

    HttpResponse response = checkMultiple(checkBodyOf(List.of(fileId, nonExistentId)));

    Assertions.assertThat(response.getStatus()).isEqualTo(204);
  }

  @Test
  void givenNodeWithoutPermissionMixedWithOwnedNodeCheckDownloadMultipleShouldReturn204SkippingTheDeniedOne()
      throws Exception {
    String ownedFileId = "00000000-0000-0000-0000-00000000a004";
    String deniedFileId = "00000000-0000-0000-0000-00000000a005";
    addFile(ownedFileId, Constants.Db.RootId.LOCAL_ROOT, Constants.Db.RootId.LOCAL_ROOT, "mine.txt", 10L);
    // Owned by another user, never shared with the requester -> PermissionsChecker yields NONE.
    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                deniedFileId,
                OTHER_USER_ID,
                OTHER_USER_ID,
                Constants.Db.RootId.LOCAL_ROOT,
                "notMine.txt",
                "",
                NodeType.TEXT,
                Constants.Db.RootId.LOCAL_ROOT,
                10L,
                "text/plain"));

    HttpResponse response = checkMultiple(checkBodyOf(List.of(ownedFileId, deniedFileId)));

    Assertions.assertThat(response.getStatus()).isEqualTo(204);
  }

  @Test
  void givenTotalSizeExceedingCapCheckDownloadMultipleShouldReturn413() throws Exception {
    String fileId = "00000000-0000-0000-0000-00000000a006";
    addFile(fileId, Constants.Db.RootId.LOCAL_ROOT, Constants.Db.RootId.LOCAL_ROOT, "big.bin", 1024L);
    app.mocks().setMaxDownloadableSizeMb(0);

    HttpResponse response = checkMultiple(checkBodyOf(List.of(fileId)));

    Assertions.assertThat(response.getStatus()).isEqualTo(413);
  }

  @Test
  void givenTotalSizeWithinAGenerousCapCheckDownloadMultipleShouldReturn204() throws Exception {
    String fileId = "00000000-0000-0000-0000-00000000a007";
    addFile(fileId, Constants.Db.RootId.LOCAL_ROOT, Constants.Db.RootId.LOCAL_ROOT, "small.bin", 10L);
    app.mocks().setMaxDownloadableSizeMb(100);

    HttpResponse response = checkMultiple(checkBodyOf(List.of(fileId)));

    Assertions.assertThat(response.getStatus()).isEqualTo(204);
  }

  // =========================================================================================
  // Section B: /download-multiple — ZIP-building internals (createZip / addNodeToZip /
  // addFolderToZip / addFileToZip / getUniqueNameForZip)
  // =========================================================================================

  @Test
  void givenDuplicateFileNamesTheDownloadMultipleShouldDisambiguateWithExtensionPreserved()
      throws Exception {
    String fileId1 = "00000000-0000-0000-0000-00000000b001";
    String fileId2 = "00000000-0000-0000-0000-00000000b002";
    // Both files requested DIRECTLY (not via their containing folder) -> createZip passes an
    // empty parentPath for each top-level requested node regardless of the node's real DB
    // parent, so the ZIP mirrors the REQUEST shape (flat here), not the node's absolute path.
    addFile(fileId1, Constants.Db.RootId.LOCAL_ROOT, Constants.Db.RootId.LOCAL_ROOT, "dup.txt", 10L);
    addFile(fileId2, Constants.Db.RootId.LOCAL_ROOT, Constants.Db.RootId.LOCAL_ROOT, "dup.txt", 10L);
    app.mocks().storagesServesBlob(fileId1, 1);
    app.mocks().storagesServesBlob(fileId2, 1);

    HttpResponse response = downloadMultiple(List.of(fileId1, fileId2));

    Assertions.assertThat(response.getStatus()).isEqualTo(200);
    app.mocks().verifyStoragesDownloaded(fileId1, 1);
    app.mocks().verifyStoragesDownloaded(fileId2, 1);
    assertBodyContainsWhenObservable(response, "dup.txt");
    assertBodyContainsWhenObservable(response, "dup (1).txt");
  }

  @Test
  void givenDuplicateFileNamesWithoutExtensionTheDownloadMultipleShouldDisambiguate() throws Exception {
    String fileId1 = "00000000-0000-0000-0000-00000000b003";
    String fileId2 = "00000000-0000-0000-0000-00000000b004";
    addFile(fileId1, Constants.Db.RootId.LOCAL_ROOT, Constants.Db.RootId.LOCAL_ROOT, "dupfile", 10L);
    addFile(fileId2, Constants.Db.RootId.LOCAL_ROOT, Constants.Db.RootId.LOCAL_ROOT, "dupfile", 10L);
    app.mocks().storagesServesBlob(fileId1, 1);
    app.mocks().storagesServesBlob(fileId2, 1);

    HttpResponse response = downloadMultiple(List.of(fileId1, fileId2));

    Assertions.assertThat(response.getStatus()).isEqualTo(200);
    app.mocks().verifyStoragesDownloaded(fileId1, 1);
    app.mocks().verifyStoragesDownloaded(fileId2, 1);
    assertBodyContainsWhenObservable(response, "dupfile (1)");
  }

  @Test
  void givenDuplicateFileNamesEndingWithDotTheDownloadMultipleShouldDisambiguate() throws Exception {
    String fileId1 = "00000000-0000-0000-0000-00000000b005";
    String fileId2 = "00000000-0000-0000-0000-00000000b006";
    addFile(fileId1, Constants.Db.RootId.LOCAL_ROOT, Constants.Db.RootId.LOCAL_ROOT, "dup.", 10L);
    addFile(fileId2, Constants.Db.RootId.LOCAL_ROOT, Constants.Db.RootId.LOCAL_ROOT, "dup.", 10L);
    app.mocks().storagesServesBlob(fileId1, 1);
    app.mocks().storagesServesBlob(fileId2, 1);

    HttpResponse response = downloadMultiple(List.of(fileId1, fileId2));

    Assertions.assertThat(response.getStatus()).isEqualTo(200);
    app.mocks().verifyStoragesDownloaded(fileId1, 1);
    app.mocks().verifyStoragesDownloaded(fileId2, 1);
    // lastDotIndex == originalName.length()-1 -> the "< length-1" half of the && is false, so no
    // extension is split off: the counter is appended after the trailing dot itself.
    assertBodyContainsWhenObservable(response, "dup. (1)");
  }

  @Test
  void givenTripleDuplicateFileNamesTheDownloadMultipleShouldIncrementCounterPastOne() throws Exception {
    String fileId1 = "00000000-0000-0000-0000-00000000b007";
    String fileId2 = "00000000-0000-0000-0000-00000000b008";
    String fileId3 = "00000000-0000-0000-0000-00000000b009";
    addFile(fileId1, Constants.Db.RootId.LOCAL_ROOT, Constants.Db.RootId.LOCAL_ROOT, "same.txt", 10L);
    addFile(fileId2, Constants.Db.RootId.LOCAL_ROOT, Constants.Db.RootId.LOCAL_ROOT, "same.txt", 10L);
    addFile(fileId3, Constants.Db.RootId.LOCAL_ROOT, Constants.Db.RootId.LOCAL_ROOT, "same.txt", 10L);
    app.mocks().storagesServesBlob(fileId1, 1);
    app.mocks().storagesServesBlob(fileId2, 1);
    app.mocks().storagesServesBlob(fileId3, 1);

    HttpResponse response = downloadMultiple(List.of(fileId1, fileId2, fileId3));

    Assertions.assertThat(response.getStatus()).isEqualTo(200);
    app.mocks().verifyStoragesDownloaded(fileId1, 1);
    app.mocks().verifyStoragesDownloaded(fileId2, 1);
    app.mocks().verifyStoragesDownloaded(fileId3, 1);
    assertBodyContainsWhenObservable(response, "same.txt");
    assertBodyContainsWhenObservable(response, "same (1).txt");
    // The 3rd collision must retry past "(1)" (already used) to "(2)" -> exercises the do-while
    // loop actually looping more than once.
    assertBodyContainsWhenObservable(response, "same (2).txt");
  }

  @Test
  void givenDuplicateFolderNamesTheDownloadMultipleShouldDisambiguateFolderEntries() throws Exception {
    String folderId1 = "11111111-1111-1111-1111-1111b0000010";
    String folderId2 = "11111111-1111-1111-1111-1111b0000011";
    addFolder(folderId1, Constants.Db.RootId.LOCAL_ROOT, Constants.Db.RootId.LOCAL_ROOT, "docs");
    addFolder(folderId2, Constants.Db.RootId.LOCAL_ROOT, Constants.Db.RootId.LOCAL_ROOT, "docs");

    HttpResponse response = downloadMultiple(List.of(folderId1, folderId2));

    Assertions.assertThat(response.getStatus()).isEqualTo(200);
    assertBodyContainsWhenObservable(response, "docs/");
    assertBodyContainsWhenObservable(response, "docs (1)/");
  }

  @Test
  void givenDeeplyNestedFolderTheDownloadMultipleShouldRecurseThroughAllLevels() throws Exception {
    String folderA = "11111111-1111-1111-1111-1111b0000020";
    String folderB = "11111111-1111-1111-1111-1111b0000021";
    String folderC = "11111111-1111-1111-1111-1111b0000022";
    String fileId = "00000000-0000-0000-0000-00000000b020";
    addFolder(folderA, Constants.Db.RootId.LOCAL_ROOT, Constants.Db.RootId.LOCAL_ROOT, "A");
    addFolder(folderB, folderA, Constants.Db.RootId.LOCAL_ROOT + "," + folderA, "B");
    addFolder(folderC, folderB, Constants.Db.RootId.LOCAL_ROOT + "," + folderA + "," + folderB, "C");
    addFile(
        fileId,
        folderC,
        Constants.Db.RootId.LOCAL_ROOT + "," + folderA + "," + folderB + "," + folderC,
        "leaf.txt",
        10L);
    app.mocks().storagesServesBlob(fileId, 1);

    HttpResponse response = downloadMultiple(List.of(folderA));

    Assertions.assertThat(response.getStatus()).isEqualTo(200);
    app.mocks().verifyStoragesDownloaded(fileId, 1);
    assertBodyContainsWhenObservable(response, "A/B/C/leaf.txt");
  }

  /**
   * {@code createZip}'s single-node branch names the ZIP after {@code nodes.get(0).getName()} —
   * NOT {@code getFullName()}. {@code Node#getName()} strips the extension for non-folder types
   * (see {@code Node#getName()}), so a single "report.pdf" download is named "report.zip", not
   * "report.pdf.zip" — a real, slightly surprising quirk, asserted as observed.
   */
  @Test
  void givenSingleFileRequestTheDownloadMultipleShouldNameZipAfterTheFile() throws Exception {
    String fileId = "00000000-0000-0000-0000-00000000b030";
    addFile(fileId, Constants.Db.RootId.LOCAL_ROOT, Constants.Db.RootId.LOCAL_ROOT, "report.pdf", 10L);
    app.mocks().storagesServesBlob(fileId, 1);

    HttpResponse response = downloadMultiple(List.of(fileId));

    Assertions.assertThat(response.getStatus()).isEqualTo(200);
    Assertions.assertThat(response.getHeaders())
        .anyMatch(
            header ->
                header.getKey().equalsIgnoreCase("content-disposition")
                    && header.getValue().contains("report.zip"));
    app.mocks().verifyStoragesDownloaded(fileId, 1);
  }

  @Test
  void givenEmptyFolderAlongsideAFileTheDownloadMultipleShouldIncludeBothEntries() throws Exception {
    String emptyFolderId = "11111111-1111-1111-1111-1111b0000040";
    String fileId = "00000000-0000-0000-0000-00000000b040";
    addFolder(emptyFolderId, Constants.Db.RootId.LOCAL_ROOT, Constants.Db.RootId.LOCAL_ROOT, "empty");
    addFile(fileId, Constants.Db.RootId.LOCAL_ROOT, Constants.Db.RootId.LOCAL_ROOT, "alone.txt", 10L);
    app.mocks().storagesServesBlob(fileId, 1);

    HttpResponse response = downloadMultiple(List.of(emptyFolderId, fileId));

    Assertions.assertThat(response.getStatus()).isEqualTo(200);
    app.mocks().verifyStoragesDownloaded(fileId, 1);
    assertBodyContainsWhenObservable(response, "empty/");
    assertBodyContainsWhenObservable(response, "alone.txt");
  }

  @Test
  void givenNonExistentNodeIdMixedWithValidFileTheDownloadMultipleShouldSilentlySkipIt() throws Exception {
    String fileId = "00000000-0000-0000-0000-00000000b050";
    String nonExistentId = "00000000-0000-0000-0000-0000dead0050";
    addFile(fileId, Constants.Db.RootId.LOCAL_ROOT, Constants.Db.RootId.LOCAL_ROOT, "present.txt", 10L);
    app.mocks().storagesServesBlob(fileId, 1);

    HttpResponse response = downloadMultiple(List.of(fileId, nonExistentId));

    Assertions.assertThat(response.getStatus()).isEqualTo(200);
    app.mocks().verifyStoragesDownloaded(fileId, 1);
    assertBodyContainsWhenObservable(response, "present.txt");
  }

  /**
   * Authenticated-path exercise of {@code addNodeToZip}'s per-node {@code accessChecker.apply}
   * during RECURSION (not just the top-level request list): the folder itself is shared
   * READ_ONLY with the requester (so the top-level {@code checkDownloadMultipleInternal} gate
   * passes for the folder), but its child file is owned by someone else and never individually
   * shared. {@code PermissionsChecker#getPermissions} is a per-node lookup (owner-or-direct-share,
   * NOT ancestor-cascading — see {@code PermissionsChecker#getPermissions}), so the child fails
   * the same {@code accessChecker} the folder itself used to pass, and {@code addNodeToZip}
   * silently excludes it while still zipping the (empty, from the requester's view) folder.
   */
  @Test
  void givenSharedFolderWithUnsharedChildTheDownloadMultipleShouldExcludeTheChildFromTheZip()
      throws Exception {
    String folderId = "11111111-1111-1111-1111-1111b0000060";
    String childFileId = "00000000-0000-0000-0000-00000000b060";
    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                folderId,
                OTHER_USER_ID,
                OTHER_USER_ID,
                Constants.Db.RootId.LOCAL_ROOT,
                "shared-folder",
                "",
                NodeType.FOLDER,
                Constants.Db.RootId.LOCAL_ROOT,
                0L,
                null))
        .addShare(folderId, REQUESTER_ID, ACL.SharePermission.READ_ONLY)
        .addNode(
            new PopulatorNode(
                childFileId,
                OTHER_USER_ID,
                OTHER_USER_ID,
                folderId,
                "notShared.txt",
                "",
                NodeType.TEXT,
                Constants.Db.RootId.LOCAL_ROOT + "," + folderId,
                10L,
                "text/plain"));
    // childFileId is deliberately NOT shared with REQUESTER_ID.

    HttpResponse response = downloadMultiple(List.of(folderId));

    Assertions.assertThat(response.getStatus()).isEqualTo(200);
    // The only downloadable content in this test is the excluded child; if it were wrongly
    // included, storages would have been hit.
    app.mocks().verifyStoragesNeverDownloaded();
    assertBodyContainsWhenObservable(response, "shared-folder/");
    assertBodyDoesNotContainWhenObservable(response, "notShared.txt");
  }

  @Test
  void givenTotalSizeExceedingCapTheDownloadMultipleShouldReturn413() throws Exception {
    String fileId = "00000000-0000-0000-0000-00000000b070";
    addFile(fileId, Constants.Db.RootId.LOCAL_ROOT, Constants.Db.RootId.LOCAL_ROOT, "big.bin", 1024L);
    app.mocks().setMaxDownloadableSizeMb(0);

    HttpResponse response = downloadMultiple(List.of(fileId));

    Assertions.assertThat(response.getStatus()).isEqualTo(413);
    app.mocks().verifyStoragesNeverDownloaded();
  }

  /**
   * FINDING: unlike the single-file download route (where a storages connection-drop is caught
   * synchronously, BEFORE any response is written, and cleanly surfaces as 500 — see {@code
   * DownloadByPublicLinkApiIT}'s equivalent 500 test), the multi-download ZIP route has ALREADY
   * committed its 200-with-chunked-headers response (in {@code
   * BlobController#downloadMultiple}, synchronously, before the async {@code
   * CompletableFuture.runAsync} ZIP-building task in {@code BlobService#createZip} even starts).
   * When a per-file storages fetch then fails mid-build ({@code addFileToZip}'s {@code
   * fileStore.download(...)} throwing), the failure is caught, wrapped ({@code
   * DependencyException} -> {@code ZipGenerationException}), logged, and the pipe is closed in the
   * {@code finally} — but there is no way left to change the response status. The client
   * (embedded and real-HTTP alike) sees a normal 200 with a truncated/incomplete ZIP body, not an
   * error. This is a genuine robustness gap (silent partial-content delivery), not fixed here per
   * the "no src/main changes" guardrail — only exercised and documented.
   */
  @Test
  void givenStoragesConnectionDropsMidZipTheDownloadMultipleStillReturns200WithATruncatedBody()
      throws Exception {
    String fileId = "00000000-0000-0000-0000-00000000b080";
    addFile(fileId, Constants.Db.RootId.LOCAL_ROOT, Constants.Db.RootId.LOCAL_ROOT, "flaky.bin", 10L);
    app.mocks().storagesDownloadConnectionDrops();

    HttpResponse response = downloadMultiple(List.of(fileId));

    // Headers are already committed by the time the async failure happens.
    Assertions.assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void givenNodeIdsJsonNullFormEncodedTheDownloadMultipleShouldReturn400() {
    // The literal JSON token "null" parses via ObjectMapper#readValue to an actual Java null,
    // reaching BlobController#downloadMultiple's own "nodeIds == null" check (distinct from the
    // "nodeIds.isEmpty()" case already covered by DownloadMultipleApiIT).
    String requestBody = "nodeIds=" + URLEncoder.encode("null", StandardCharsets.UTF_8);

    HttpResponse response = downloadMultipleForm(requestBody);

    Assertions.assertThat(response.getStatus()).isEqualTo(400);
  }
}
