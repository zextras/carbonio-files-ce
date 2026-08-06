// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code com.zextras.carbonio.files.acceptance.AuthenticatedDownloadApiIT} rewritten as an
 * out-of-process {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}: {@code GET
 * /download/{id}[/{version}]} and {@code GET /download/{id}/check}.
 *
 * <p><b>Body assertions are now unconditional (no longer "when observable").</b> The original's
 * class-level FINDING documented that the seam's EMBEDDED transport could never fully drain this
 * route's multi-frame streamed body, so body-match assertions were conditionally skipped there and
 * only actually exercised on the opt-in real-HTTP transport lane. {@code @QuarkusIntegrationTest}
 * drives RestAssured against the launched app's REAL socket unconditionally, so that gap no longer
 * exists here: every body assertion below always runs.
 *
 * <p><b>Sizing is no longer a hand-computed convention.</b> The original seeded every node's DB
 * size as {@code (nodeId + version).length()} to match {@code StoragesMockHelper}'s fixed {@code
 * nodeId+version} fallback bytes (so the DB-recorded size and the mocked body length never
 * disagreed). Real API seeding removes the need for that convention entirely: {@code
 * seedFile}/{@code seedVersion} upload ACTUAL content bytes through the real {@code BlobService},
 * which itself records the true byte length — so the DB size and the served body are equal BY
 * CONSTRUCTION, for any content.
 *
 * <p><b>Config-split (D3):</b> the original held 9 methods. ONE ({@code
 * givenTheNodeSizeOverTheConfiguredCapDownloadShouldReturn413}) needs an ACTUAL {@code
 * application-config.max-downloadable-size-in-mb} cap configured; it moved to the sibling {@link
 * AuthenticatedDownloadSizeCapIT} ({@code @WithTestResource(DownloadCapResource.class)}). This
 * class keeps the remaining 8 methods on the shared default (uncapped) stack. Mapping: 8 (here) + 1
 * ({@code AuthenticatedDownloadSizeCapIT}) = 9 (unchanged from the original).
 */
class AuthenticatedDownloadApiIT extends AbstractFilesIT {

  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OTHER_USER_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String REQUESTER_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String OTHER_USER_COOKIE = "ZM_AUTH_TOKEN=fake-token-b";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", REQUESTER_ID);
    FilesStackTestResource.getUserManagementService().registerToken("fake-token-b", OTHER_USER_ID);
  }

  private static Response checkDownload(String nodeId, String cookie) {
    var request = RestAssured.given();
    if (cookie != null) {
      request = request.header("Cookie", cookie);
    }
    return request.get("/download/" + nodeId + "/check");
  }

  @Test
  void givenAnExistingFileTheLatestDownloadShouldReturn200WithHeadersAndMatchingBody() {
    // Given — a single version (v1)
    byte[] content = "hello streaming world".getBytes(StandardCharsets.UTF_8);
    String nodeId = seedFile("fake.txt", LOCAL_ROOT, content, REQUESTER_COOKIE);

    // When
    Response response = download(nodeId, REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(response.getHeader("Content-Disposition"))
        .isEqualTo(
            "attachment; filename*=UTF-8''"
                + URLEncoder.encode("fake.txt", StandardCharsets.UTF_8));
    Assertions.assertThat(response.getHeader("Content-Length"))
        .isEqualTo(String.valueOf(content.length));
    Assertions.assertThat(response.getBody().asByteArray()).isEqualTo(content);

    FilesStackTestResource.getStoragesService().verifyDownloaded(nodeId, 1);
  }

  @Test
  void givenAVersionSuffixUrlTheDownloadShouldServeThatSpecificVersion() {
    // Given — two versions (v1, v2); the node's currently-current version is 2, but the URL
    // explicitly asks for v1
    byte[] v1 = "version one content".getBytes(StandardCharsets.UTF_8);
    byte[] v2 = "version two content, a bit longer".getBytes(StandardCharsets.UTF_8);
    String nodeId = seedFile("fake.txt", LOCAL_ROOT, v1, REQUESTER_COOKIE);
    seedVersion(nodeId, v2, "fake.txt", REQUESTER_COOKIE);

    // When — explicitly request the OLD version, not the current one
    Response response = download(nodeId, 1, REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(response.getBody().asByteArray()).isEqualTo(v1);
    FilesStackTestResource.getStoragesService().verifyDownloaded(nodeId, 1);
  }

  // -------------------------------------------------------- Content-Length vs chunked (decision B)

  /**
   * Locks decision B: the single-file download is sent fixed-length (a {@code Content-Length}
   * computed from the DB-recorded size, set before the first byte — see {@code TransferStreaming
   * #streamBlob}), never as {@code Transfer-Encoding: chunked}.
   */
  @Test
  void givenAnExistingFileTheDownloadResponseShouldBeFixedLengthNotChunked() {
    byte[] content = "fixed length content".getBytes(StandardCharsets.UTF_8);
    String nodeId = seedFile("fake.txt", LOCAL_ROOT, content, REQUESTER_COOKIE);

    Response response = download(nodeId, REQUESTER_COOKIE);

    response.then().statusCode(200);
    Assertions.assertThat(response.getHeader("Content-Length"))
        .as("single download must advertise a fixed Content-Length")
        .isEqualTo(String.valueOf(content.length));
    Assertions.assertThat(response.getHeader("Transfer-Encoding"))
        .as("single download must NOT be chunked")
        .isNull();
  }

  @Test
  void givenNoPermissionOnTheNodeDownloadShouldReturn404() {
    // Given — node owned by OTHER_USER_ID, never shared with the requester
    String nodeId =
        seedFile(
            "notMine.txt",
            LOCAL_ROOT,
            "secret".getBytes(StandardCharsets.UTF_8),
            OTHER_USER_COOKIE);
    // seedFile's real upload path itself performs ONE storages verify-blob-exists GET /download
    // (BlobService#uploadFile -> verifyBlobExists) — an incidental seed-time side effect, not part
    // of the scenario under test. Reset the fake's download log so "never downloaded" below asserts
    // only the ACTION under test (the permission-denied download attempt), matching the original
    // seam's intent (DatabasePopulator's direct repo write never touched storages at seed time).
    FilesStackTestResource.getStoragesService().reset();

    // When
    Response response = download(nodeId, REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(404);
    Assertions.assertThat(response.getBody().asString()).isEqualTo("404 Not Found");
    FilesStackTestResource.getStoragesService().verifyNeverDownloaded();
  }

  @Test
  void givenANonExistentNodeDownloadShouldReturnTheSame404AsNoPermission() {
    // See class-level FINDING (ported from the original): the permission gate maps a non-existent
    // id to the SAME Optional.empty() -> 404 shape as an existing-but-forbidden node.
    String nonExistentId = "10000000-0000-0000-0000-00000000ffff";

    // When
    Response response = download(nonExistentId, REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(404);
    Assertions.assertThat(response.getBody().asString()).isEqualTo("404 Not Found");
    FilesStackTestResource.getStoragesService().verifyNeverDownloaded();
  }

  @Test
  void givenADownloadableNodeCheckDownloadShouldReturn204() {
    // Given
    String nodeId =
        seedFile(
            "fake.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    // See givenNoPermissionOnTheNodeDownloadShouldReturn404's comment: seedFile's upload itself
    // triggers one storages verify-blob-exists GET /download; reset so the assertion below covers
    // only /check's own behaviour.
    FilesStackTestResource.getStoragesService().reset();

    // When
    Response response = checkDownload(nodeId, REQUESTER_COOKIE);

    // Then — /check never touches storages at all (no fileStore call in checkDownloadFileById)
    Assertions.assertThat(response.getStatusCode()).isEqualTo(204);
    FilesStackTestResource.getStoragesService().verifyNeverDownloaded();
  }

  @Test
  void givenNoPermissionCheckDownloadShouldReturn404() {
    // Given — node owned by OTHER_USER_ID, never shared with the requester
    String nodeId =
        seedFile(
            "notMine.txt",
            LOCAL_ROOT,
            "secret".getBytes(StandardCharsets.UTF_8),
            OTHER_USER_COOKIE);

    // When
    Response response = checkDownload(nodeId, REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(404);
    Assertions.assertThat(response.getBody().asString()).isEqualTo("404 Not Found");
  }

  @Test
  void givenANonExistentNodeCheckDownloadShouldReturn404() {
    // Given — same permission-gate merge as the plain download route
    String nonExistentId = "10000000-0000-0000-0000-00000000ffff";

    // When
    Response response = checkDownload(nonExistentId, REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(404);
    Assertions.assertThat(response.getBody().asString()).isEqualTo("404 Not Found");
  }
}
