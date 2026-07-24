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
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code com.zextras.carbonio.files.acceptance.DownloadMultipleApiIT} rewritten as an
 * out-of-process {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}: {@code POST
 * /download-multiple} (JSON array of node ids, form-encoded), the streamed-ZIP happy paths and
 * request-shape validation. Fixture nodes/blobs are seeded through the real public API ({@code
 * seedFolder}/{@code seedFile}/{@code seedShare}), so a downloaded blob's bytes flow through the
 * REAL {@code FilestoreProducer}/{@code StoragesClient} into {@code MockStoragesService} — no
 * explicit {@code storagesServesBlob} seeding is needed, unlike the old seam.
 *
 * <p>ZIP-content assertions (entry names, duplicate-name disambiguation, folder recursion) are
 * NOT this class's concern: {@link MultiDownloadZipApiIT} owns that coverage. This class keeps the
 * original's 10 request-shape/happy-path methods unchanged in intent.
 */
class DownloadMultipleApiIT extends AbstractFilesIT {

  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OTHER_USER_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String REQUESTER_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String OTHER_USER_COOKIE = "ZM_AUTH_TOKEN=fake-token-b";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", REQUESTER_ID);
    FilesStackTestResource.getUserManagementService().registerToken("fake-token-b", OTHER_USER_ID);
  }

  @Test
  void givenMultipleFilesInSameFolderTheDownloadMultipleShouldReturnZipWith200() {
    // Given
    String folderId = seedFolder("test-folder", LOCAL_ROOT, REQUESTER_COOKIE);
    String fileId1 = seedFile("file1.txt", folderId, "content-1".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    String fileId2 = seedFile("file2.pdf", folderId, "content-2".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    String fileId3 = seedFile("file3.jpg", folderId, "content-3".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);

    // When
    Response response = downloadMultiple(List.of(fileId1, fileId2, fileId3), REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(response.getHeader("Content-Type")).contains("application/zip");
    Assertions.assertThat(response.getHeader("Content-Disposition"))
        .contains("attachment")
        .contains("Files.zip");

    FilesStackTestResource.getStoragesService().verifyDownloaded(fileId1, 1);
  }

  // -------------------------------------------------------- Content-Length vs chunked (decision B)

  /**
   * Locks decision B: a ZIP/multi-download response has no known length up front, so {@code
   * TransferStreaming#streamZip} enables chunked mode explicitly and never sets {@code
   * Content-Length} — the mirror image of {@link AuthenticatedDownloadApiIT}'s single-download
   * fixed-length assertion.
   */
  @Test
  void givenMultipleFilesTheDownloadMultipleResponseShouldBeChunkedNotFixedLength() {
    // Given
    String folderId = seedFolder("chunked-test-folder", LOCAL_ROOT, REQUESTER_COOKIE);
    String fileId1 = seedFile("file1.txt", folderId, "a".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    String fileId2 = seedFile("file2.txt", folderId, "bb".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);

    // When
    Response response = downloadMultiple(List.of(fileId1, fileId2), REQUESTER_COOKIE);

    // Then
    response.then().statusCode(200);
    Assertions.assertThat(response.getHeader("Content-Length"))
        .as("a streamed ZIP has no fixed Content-Length")
        .isNull();
    Assertions.assertThat(response.getHeader("Transfer-Encoding"))
        .as("a streamed ZIP must be chunked")
        .isEqualToIgnoringCase("chunked");
  }

  @Test
  void givenMixedNodesInSameFolderTheDownloadMultipleShouldReturnZipWith200() {
    // Given
    String parentFolderId = seedFolder("parent-folder", LOCAL_ROOT, REQUESTER_COOKIE);
    String subFolderId = seedFolder("sub-folder", parentFolderId, REQUESTER_COOKIE);
    String fileId1 = seedFile("file1.txt", parentFolderId, "content-1".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    seedFile("file2.txt", subFolderId, "content-2".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);

    // When
    Response response = downloadMultiple(List.of(fileId1, subFolderId), REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
  }

  @Test
  void givenLocalRootAsNodeIdTheDownloadMultipleShouldReturnZipWith200() {
    // Given
    seedFile("file1.txt", LOCAL_ROOT, "content-1".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    seedFile("file2.txt", LOCAL_ROOT, "content-2".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    seedFolder("folder", LOCAL_ROOT, REQUESTER_COOKIE);

    // When
    Response response = downloadMultiple(List.of(LOCAL_ROOT), REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(response.getHeader("Content-Type")).contains("application/zip");
  }

  @Test
  void givenLocalRootWithOtherNodesTheDownloadMultipleShouldReturn400() {
    // Given
    String fileId = seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);

    // When
    Response response = downloadMultiple(List.of(LOCAL_ROOT, fileId), REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(400);
  }

  @Test
  void givenEmptyNodeListTheDownloadMultipleShouldReturn400() {
    // Given / When
    Response response = downloadMultiple(List.of(), REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(400);
  }

  @Test
  void givenMissingRequestBodyTheDownloadMultipleShouldReturn400() {
    // When — no form body at all
    Response response = downloadMultipleRaw(null, REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(400);
  }

  @Test
  void givenInvalidJsonFormatTheDownloadMultipleShouldReturn400() {
    // When
    Response response = downloadMultipleRaw("nodeIds=invalid-json", REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(400);
  }

  @Test
  void givenMissingNodeIdsParameterTheDownloadMultipleShouldReturn400() {
    // Given
    String requestBody =
        "wrongParam=" + URLEncoder.encode("[\"00000000-0000-0000-0000-000000000001\"]", StandardCharsets.UTF_8);

    // When
    Response response = downloadMultipleRaw(requestBody, REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(400);
  }

  @Test
  void givenSharedNodeTheDownloadMultipleShouldReturnZipWith200() {
    // Given — a folder owned by another user but shared with our user, containing a file that is
    // ALSO individually shared (mirrors the original: both the folder and the file carry their own
    // share row).
    String folderId = seedFolder("shared-folder", LOCAL_ROOT, OTHER_USER_COOKIE);
    seedShare(folderId, REQUESTER_ID, ACL.SharePermission.READ_ONLY, OTHER_USER_COOKIE);
    String fileId = seedFile("shared-file.txt", folderId, "content".getBytes(StandardCharsets.UTF_8), OTHER_USER_COOKIE);
    seedShare(fileId, REQUESTER_ID, ACL.SharePermission.READ_ONLY, OTHER_USER_COOKIE);

    // When
    Response response = downloadMultiple(List.of(fileId), REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
  }
}
