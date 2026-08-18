// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.response.Response;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code com.zextras.carbonio.files.acceptance.MultiDownloadZipApiIT} rewritten as an
 * out-of-process {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}: MEASURE-GUIDED
 * coverage of {@code rest.services.BlobService} ({@code checkDownloadMultipleInternal}, {@code
 * buildZipPlan}/{@code addFolderToPlan}/{@code addFileToPlan}, {@code getUniqueNameForZip}) and
 * {@code rest.controllers.BlobController}'s {@code /download-multiple[/check]} routes.
 *
 * <p><b>ZIP contents are asserted by real ZIP parsing, not lossy substring matching.</b> The
 * original's class-level FINDING documented that the seam's embedded transport never fully drained
 * this route's multi-frame streamed body, forcing {@code assertBodyContainsWhenObservable} to
 * substring-match a partially/lossily-decoded body string, conditionally skipped when unobservable.
 * {@code @QuarkusIntegrationTest} drives RestAssured against the launched app's real socket, which
 * fully drains the (genuine, real {@code java.util.zip.ZipOutputStream}-built) archive every time —
 * so {@link #zipEntryNames} parses it properly and every assertion below always runs, asserting
 * EXACT entry names rather than substrings.
 *
 * <p><b>Duplicate-name seeding needs two different PARENT folders, not one (a deliberate, necessary
 * adaptation, not a scenario change).</b> The original seeded same-named siblings directly via
 * {@code DatabasePopulator}, bypassing {@code BlobService#uploadFile}'s real name-deduplication
 * ({@code RenameNodeUtils#searchAlternativeName}, which uses the EXACT SAME " (N)" convention as
 * this class's ZIP-level {@code getUniqueNameForZip}). Seeding two same-named files/folders under
 * the SAME real parent via {@code seedFile}/{@code seedFolder} would therefore have the
 * upload/folder-creation path itself rename the second one BEFORE it ever reaches the ZIP builder,
 * making the ZIP-level dedup logic unreachable. Seeding them under two DIFFERENT parents avoids the
 * upload-time rename (each name is unique within its own parent) while still requesting both nodes
 * DIRECTLY (top-level, not via their folder) in {@code /download-multiple} — {@code
 * addFileToPlan}/{@code addFolderToPlan} pass an EMPTY {@code parentPath} for every top-level
 * requested node regardless of its real DB parent (ported verbatim from the original's own
 * comment), so both entries still collide at the SAME top-level ZIP path and the SAME {@code
 * getUniqueNameForZip} disambiguation fires — the exact behaviour under test is preserved, just
 * demonstrated with the two nodes' real DB parents differing (which the original's shared-parent
 * seeding could never actually prove either way).
 *
 * <p><b>Config-split (D3):</b> the original held 24 methods. THREE need an ACTUAL {@code
 * application-config.max-downloadable-size-in-mb} cap configured (a boot-time snapshot, per {@code
 * FilesConfig}, that a runtime WireMock stub cannot re-drive out-of-process): the two cap-EXCEEDED
 * scenarios (cap=0) moved to {@link MultiDownloadZipSizeCapIT}; the one
 * cap-PRESENT-but-NOT-exceeded scenario (cap=100 — genuinely different from the default/uncapped
 * stack's {@code Optional.empty()} short-circuit) moved to {@link
 * MultiDownloadZipGenerousSizeCapIT}. This class keeps the remaining 21 methods on the default
 * (uncapped) stack. Mapping: 21 (here) + 2 ({@code MultiDownloadZipSizeCapIT}) + 1 ({@code
 * MultiDownloadZipGenerousSizeCapIT}) = 24 (unchanged from the original).
 */
class MultiDownloadZipApiIT extends AbstractFilesIT {

  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OTHER_USER_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String REQUESTER_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String OTHER_USER_COOKIE = "ZM_AUTH_TOKEN=fake-token-b";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", REQUESTER_ID);
    FilesStackTestResource.getUserManagementService().registerToken("fake-token-b", OTHER_USER_ID);
  }

  static String checkBodyOf(List<String> nodeIds) {
    return "{\"nodeIds\":["
        + nodeIds.stream().map(id -> "\"" + id + "\"").collect(Collectors.joining(","))
        + "]}";
  }

  // =========================================================================================
  // Section A: /download-multiple/check (BlobController#checkDownloadMultiple)
  // =========================================================================================

  @Test
  void givenValidNodeIdsCheckDownloadMultipleShouldReturn204AndNeverTouchStorages() {
    String fileId =
        seedFile(
            "file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    // seedFile's upload itself performs one storages verify-blob-exists GET /download; reset so
    // "never touch storages" below asserts only /check's own behaviour.
    FilesStackTestResource.getStoragesService().reset();

    Response response = checkDownloadMultiple(checkBodyOf(List.of(fileId)), REQUESTER_COOKIE);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(204);
    // /download-multiple/check only validates access + size; it never fetches a blob.
    FilesStackTestResource.getStoragesService().verifyNeverDownloaded();
  }

  @Test
  void givenMissingBodyCheckDownloadMultipleShouldReturn400() {
    Response response = checkDownloadMultiple(null, REQUESTER_COOKIE);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(400);
  }

  /**
   * FINDING (real production behaviour, ported verbatim): {@code
   * BlobController#checkDownloadMultiple}'s JSON-parse-failure branch wraps the caught {@code
   * JsonProcessingException} as the CAUSE of the {@code IllegalArgumentException} it fires (unlike
   * every other {@code IllegalArgumentException} in this method, and unlike its sibling {@code
   * downloadMultiple}'s equivalent form-parsing catch, neither of which set a cause). {@code
   * ExceptionsHandler#exceptionCaught} unconditionally unwraps to {@code cause.getCause()} before
   * classifying the status code, so it reclassifies based on the INNER {@code
   * JsonProcessingException} — which maps to 500 — instead of the outer, intended {@code
   * IllegalArgumentException} -> 400. Net effect: an invalid JSON body on {@code
   * /download-multiple/check} returns 500 Internal Server Error, not the 400 the message text
   * implies. Not fixed here (src/main is out of scope) — asserted as the real behaviour.
   */
  @Test
  void givenInvalidJsonBodyCheckDownloadMultipleActuallyReturns500NotTheIntended400() {
    Response response = checkDownloadMultiple("this-is-not-json", REQUESTER_COOKIE);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(500);
  }

  @Test
  void givenJsonBodyMissingNodeIdsFieldCheckDownloadMultipleShouldReturn400() {
    Response response = checkDownloadMultiple("{\"foo\":\"bar\"}", REQUESTER_COOKIE);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(400);
  }

  @Test
  void givenEmptyNodeIdsArrayCheckDownloadMultipleShouldReturn400() {
    Response response = checkDownloadMultiple("{\"nodeIds\":[]}", REQUESTER_COOKIE);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(400);
  }

  @Test
  void givenLocalRootWithOtherNodeCheckDownloadMultipleShouldReturn400() {
    String fileId =
        seedFile(
            "file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);

    Response response =
        checkDownloadMultiple(checkBodyOf(List.of(LOCAL_ROOT, fileId)), REQUESTER_COOKIE);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(400);
  }

  /**
   * FINDING (ported verbatim): {@code BlobService#checkDownloadMultipleInternal} silently {@code
   * continue}s past node ids that do not exist (or are inaccessible) rather than failing the whole
   * request — the method ALWAYS returns {@code Optional.of(nodes)} (possibly empty) once past the
   * up-front validation for an authenticated caller. Consequently {@code
   * BlobController#checkDownloadMultiple}'s {@code .orElseThrow(...)} — whose message promises
   * "some nodes do not exist or user lacks permission" — is DEAD CODE for the authenticated
   * endpoint: requesting ONLY non-existent ids still returns 204, not 404.
   */
  @Test
  void givenOnlyNonExistentNodeIdsCheckDownloadMultipleShouldReturn204NotFound() {
    String nonExistentId = "00000000-0000-0000-0000-0000dead0001";

    Response response =
        checkDownloadMultiple(checkBodyOf(List.of(nonExistentId)), REQUESTER_COOKIE);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(204);
  }

  @Test
  void
      givenMixOfValidAndNonExistentNodeIdsCheckDownloadMultipleShouldReturn204SkippingTheInvalidOne() {
    String fileId =
        seedFile(
            "file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    String nonExistentId = "00000000-0000-0000-0000-0000dead0002";

    Response response =
        checkDownloadMultiple(checkBodyOf(List.of(fileId, nonExistentId)), REQUESTER_COOKIE);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(204);
  }

  @Test
  void
      givenNodeWithoutPermissionMixedWithOwnedNodeCheckDownloadMultipleShouldReturn204SkippingTheDeniedOne() {
    String ownedFileId =
        seedFile(
            "mine.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    // Owned by another user, never shared with the requester -> PermissionsChecker yields NONE.
    String deniedFileId =
        seedFile(
            "notMine.txt",
            LOCAL_ROOT,
            "content".getBytes(StandardCharsets.UTF_8),
            OTHER_USER_COOKIE);

    Response response =
        checkDownloadMultiple(checkBodyOf(List.of(ownedFileId, deniedFileId)), REQUESTER_COOKIE);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(204);
  }

  // =========================================================================================
  // Section B: /download-multiple — ZIP-building internals (buildZipPlan / addFolderToPlan /
  // addFileToPlan / getUniqueNameForZip)
  // =========================================================================================

  @Test
  void givenDuplicateFileNamesTheDownloadMultipleShouldDisambiguateWithExtensionPreserved()
      throws Exception {
    // Both files requested DIRECTLY (not via their containing folder) -> addFileToPlan passes an
    // empty parentPath for each top-level requested node regardless of the node's real DB parent
    // -> the ZIP mirrors the REQUEST shape (flat here), not the nodes' absolute paths. Seeded under
    // TWO DIFFERENT parents so the real upload-time name-dedup never fires (see class javadoc).
    String parentA = seedFolder("parentA", LOCAL_ROOT, REQUESTER_COOKIE);
    String parentB = seedFolder("parentB", LOCAL_ROOT, REQUESTER_COOKIE);
    String fileId1 =
        seedFile("dup.txt", parentA, "one".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    String fileId2 =
        seedFile("dup.txt", parentB, "two".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);

    Response response = downloadMultiple(List.of(fileId1, fileId2), REQUESTER_COOKIE);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    FilesStackTestResource.getStoragesService().verifyDownloaded(fileId1, 1);
    FilesStackTestResource.getStoragesService().verifyDownloaded(fileId2, 1);
    Set<String> entries = zipEntryNames(response.getBody().asByteArray());
    Assertions.assertThat(entries).contains("dup.txt", "dup (1).txt");
  }

  @Test
  void givenDuplicateFileNamesWithoutExtensionTheDownloadMultipleShouldDisambiguate()
      throws Exception {
    String parentA = seedFolder("parentA", LOCAL_ROOT, REQUESTER_COOKIE);
    String parentB = seedFolder("parentB", LOCAL_ROOT, REQUESTER_COOKIE);
    String fileId1 =
        seedFile("dupfile", parentA, "one".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    String fileId2 =
        seedFile("dupfile", parentB, "two".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);

    Response response = downloadMultiple(List.of(fileId1, fileId2), REQUESTER_COOKIE);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    FilesStackTestResource.getStoragesService().verifyDownloaded(fileId1, 1);
    FilesStackTestResource.getStoragesService().verifyDownloaded(fileId2, 1);
    Set<String> entries = zipEntryNames(response.getBody().asByteArray());
    Assertions.assertThat(entries).contains("dupfile", "dupfile (1)");
  }

  @Test
  void givenDuplicateFileNamesEndingWithDotTheDownloadMultipleShouldDisambiguate()
      throws Exception {
    String parentA = seedFolder("parentA", LOCAL_ROOT, REQUESTER_COOKIE);
    String parentB = seedFolder("parentB", LOCAL_ROOT, REQUESTER_COOKIE);
    String fileId1 =
        seedFile("dup.", parentA, "one".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    String fileId2 =
        seedFile("dup.", parentB, "two".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);

    Response response = downloadMultiple(List.of(fileId1, fileId2), REQUESTER_COOKIE);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    FilesStackTestResource.getStoragesService().verifyDownloaded(fileId1, 1);
    FilesStackTestResource.getStoragesService().verifyDownloaded(fileId2, 1);
    // lastDotIndex == originalName.length()-1 -> the "< length-1" half of the && is false, so no
    // extension is split off: the counter is appended after the trailing dot itself.
    Set<String> entries = zipEntryNames(response.getBody().asByteArray());
    Assertions.assertThat(entries).contains("dup.", "dup. (1)");
  }

  @Test
  void givenTripleDuplicateFileNamesTheDownloadMultipleShouldIncrementCounterPastOne()
      throws Exception {
    String parentA = seedFolder("parentA", LOCAL_ROOT, REQUESTER_COOKIE);
    String parentB = seedFolder("parentB", LOCAL_ROOT, REQUESTER_COOKIE);
    String parentC = seedFolder("parentC", LOCAL_ROOT, REQUESTER_COOKIE);
    String fileId1 =
        seedFile("same.txt", parentA, "one".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    String fileId2 =
        seedFile("same.txt", parentB, "two".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    String fileId3 =
        seedFile("same.txt", parentC, "three".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);

    Response response = downloadMultiple(List.of(fileId1, fileId2, fileId3), REQUESTER_COOKIE);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    FilesStackTestResource.getStoragesService().verifyDownloaded(fileId1, 1);
    FilesStackTestResource.getStoragesService().verifyDownloaded(fileId2, 1);
    FilesStackTestResource.getStoragesService().verifyDownloaded(fileId3, 1);
    // The 3rd collision must retry past "(1)" (already used) to "(2)" -> exercises the do-while
    // loop actually looping more than once.
    Set<String> entries = zipEntryNames(response.getBody().asByteArray());
    Assertions.assertThat(entries).contains("same.txt", "same (1).txt", "same (2).txt");
  }

  @Test
  void givenDuplicateFolderNamesTheDownloadMultipleShouldDisambiguateFolderEntries()
      throws Exception {
    String wrapperA = seedFolder("wrapperA", LOCAL_ROOT, REQUESTER_COOKIE);
    String wrapperB = seedFolder("wrapperB", LOCAL_ROOT, REQUESTER_COOKIE);
    String folderId1 = seedFolder("docs", wrapperA, REQUESTER_COOKIE);
    String folderId2 = seedFolder("docs", wrapperB, REQUESTER_COOKIE);

    Response response = downloadMultiple(List.of(folderId1, folderId2), REQUESTER_COOKIE);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Set<String> entries = zipEntryNames(response.getBody().asByteArray());
    Assertions.assertThat(entries).contains("docs/", "docs (1)/");
  }

  @Test
  void givenDeeplyNestedFolderTheDownloadMultipleShouldRecurseThroughAllLevels() throws Exception {
    String folderA = seedFolder("A", LOCAL_ROOT, REQUESTER_COOKIE);
    String folderB = seedFolder("B", folderA, REQUESTER_COOKIE);
    String folderC = seedFolder("C", folderB, REQUESTER_COOKIE);
    String fileId =
        seedFile(
            "leaf.txt", folderC, "leaf-content".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);

    Response response = downloadMultiple(List.of(folderA), REQUESTER_COOKIE);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    FilesStackTestResource.getStoragesService().verifyDownloaded(fileId, 1);
    Set<String> entries = zipEntryNames(response.getBody().asByteArray());
    Assertions.assertThat(entries).contains("A/B/C/leaf.txt");
  }

  /**
   * {@code buildZipPlan}'s single-node branch names the ZIP after {@code nodes.get(0).getName()} —
   * NOT {@code getFullName()}. {@code Node#getName()} strips the extension for non-folder types, so
   * a single "report.pdf" download is named "report.zip", not "report.pdf.zip" — a real, slightly
   * surprising quirk, asserted as observed (ported verbatim).
   */
  @Test
  void givenSingleFileRequestTheDownloadMultipleShouldNameZipAfterTheFile() {
    String fileId =
        seedFile(
            "report.pdf", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);

    Response response = downloadMultiple(List.of(fileId), REQUESTER_COOKIE);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(response.getHeader("Content-Disposition")).contains("report.zip");
    FilesStackTestResource.getStoragesService().verifyDownloaded(fileId, 1);
  }

  @Test
  void givenEmptyFolderAlongsideAFileTheDownloadMultipleShouldIncludeBothEntries()
      throws Exception {
    String emptyFolderId = seedFolder("empty", LOCAL_ROOT, REQUESTER_COOKIE);
    String fileId =
        seedFile(
            "alone.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);

    Response response = downloadMultiple(List.of(emptyFolderId, fileId), REQUESTER_COOKIE);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    FilesStackTestResource.getStoragesService().verifyDownloaded(fileId, 1);
    Set<String> entries = zipEntryNames(response.getBody().asByteArray());
    Assertions.assertThat(entries).contains("empty/", "alone.txt");
  }

  @Test
  void givenNonExistentNodeIdMixedWithValidFileTheDownloadMultipleShouldSilentlySkipIt()
      throws Exception {
    String fileId =
        seedFile(
            "present.txt",
            LOCAL_ROOT,
            "content".getBytes(StandardCharsets.UTF_8),
            REQUESTER_COOKIE);
    String nonExistentId = "00000000-0000-0000-0000-0000dead0050";

    Response response = downloadMultiple(List.of(fileId, nonExistentId), REQUESTER_COOKIE);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    FilesStackTestResource.getStoragesService().verifyDownloaded(fileId, 1);
    Set<String> entries = zipEntryNames(response.getBody().asByteArray());
    Assertions.assertThat(entries).contains("present.txt");
  }

  /**
   * Authenticated-path exercise of {@code addFolderToPlan}'s per-node {@code accessChecker.apply}
   * during RECURSION (not just the top-level request list): the folder itself is shared READ_ONLY
   * with the requester (so the top-level {@code checkDownloadMultipleInternal} gate passes for the
   * folder), but its child file carries NO share of its own. {@code
   * PermissionsChecker#getPermissions} is a per-node lookup (owner-or-direct-share, NOT
   * ancestor-cascading), so the child fails the same {@code accessChecker} the folder itself used
   * to pass, and {@code addFolderToPlan} silently excludes it while still zipping the (empty, from
   * the requester's view) folder.
   *
   * <p><b>Pre-state needs a JDBC escape hatch (D1 rule 4): this exact scenario is NOT
   * API-creatable.</b> Both real cascades — {@code ShareDataFetcher#cascadeUpsertShare} (fired by
   * {@code createShare}/{@code seedShare}, recurses onto ALL existing children) and {@code
   * BlobService#uploadFile}'s own "propagate the parent folder's shares onto a newly uploaded file"
   * branch — mean that sharing the folder before OR after uploading the child both leave the child
   * with its OWN share row, regardless of order. The original acceptance test could only reach
   * "shared parent, unshared child" because {@code DatabasePopulator#addShare} wrote a single share
   * row via direct repository access, bypassing both cascades entirely — a backdoor with no
   * real-API equivalent. Seeded here via the real API (so the cascade fires as it always does),
   * then the child's auto-cascaded share row is stripped via raw JDBC to reach the intended,
   * API-observable-but-not-API-creatable pre-state.
   */
  @Test
  void givenSharedFolderWithUnsharedChildTheDownloadMultipleShouldExcludeTheChildFromTheZip()
      throws Exception {
    String folderId = seedFolder("shared-folder", LOCAL_ROOT, OTHER_USER_COOKIE);
    seedShare(folderId, REQUESTER_ID, ACL.SharePermission.READ_ONLY, OTHER_USER_COOKIE);
    String childFileId =
        seedFile(
            "notShared.txt",
            folderId,
            "secret".getBytes(StandardCharsets.UTF_8),
            OTHER_USER_COOKIE);
    // Strip the child's auto-cascaded share row (see javadoc above) to reach the intended
    // pre-state.
    try (java.sql.Connection connection = jdbcConnection();
        java.sql.PreparedStatement statement =
            connection.prepareStatement(
                "DELETE FROM share WHERE node_id = ? AND target_uuid = ?")) {
      statement.setString(1, childFileId);
      statement.setString(2, REQUESTER_ID);
      statement.executeUpdate();
    }
    // Seeding under OTHER_USER_COOKIE already triggered one incidental storages verify-download;
    // reset so the assertion below covers only the requester's ZIP-build attempt.
    FilesStackTestResource.getStoragesService().reset();

    Response response = downloadMultiple(List.of(folderId), REQUESTER_COOKIE);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    // The only downloadable content in this test is the excluded child; if it were wrongly
    // included, storages would have been hit.
    FilesStackTestResource.getStoragesService().verifyNeverDownloaded();
    Set<String> entries = zipEntryNames(response.getBody().asByteArray());
    Assertions.assertThat(entries).contains("shared-folder/");
    Assertions.assertThat(entries).noneMatch(name -> name.contains("notShared.txt"));
  }

  /**
   * FINDING (ported verbatim): unlike the single-file download route (where a storages
   * connection-drop is caught synchronously, BEFORE any response is written), the multi-download
   * ZIP route has ALREADY committed its 200-with-chunked-headers response ({@code
   * BlobController#downloadMultiple}, synchronously, before the async ZIP-building task even
   * starts). When a per-file storages fetch then fails mid-build, the failure is caught, wrapped,
   * logged, and the pipe is closed in the {@code finally} — but there is no way left to change the
   * response status. The client sees a normal 200 with a truncated/incomplete ZIP body, not an
   * error. Genuine robustness gap, not fixed here (src/main out of scope) — only exercised and
   * documented; the body is deliberately NOT zip-parsed here (it is genuinely truncated/invalid).
   */
  @Test
  void givenStoragesConnectionDropsMidZipTheDownloadMultipleStillReturns200WithATruncatedBody() {
    String fileId =
        seedFile(
            "flaky.bin", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    FilesStackTestResource.getStoragesService().setDownloadFails(true);

    Response response = downloadMultiple(List.of(fileId), REQUESTER_COOKIE);

    // Headers are already committed by the time the async failure happens.
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
  }

  @Test
  void givenNodeIdsJsonNullFormEncodedTheDownloadMultipleShouldReturn400() {
    // The literal JSON token "null" parses via ObjectMapper#readValue to an actual Java null,
    // reaching BlobController#downloadMultiple's own "nodeIds == null" check (distinct from the
    // "nodeIds.isEmpty()" case already covered by DownloadMultipleApiIT).
    String requestBody = "nodeIds=" + URLEncoder.encode("null", StandardCharsets.UTF_8);

    Response response = downloadMultipleRaw(requestBody, REQUESTER_COOKIE);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(400);
  }
}
