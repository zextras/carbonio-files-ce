// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code com.zextras.carbonio.files.acceptance.ThumbnailEdgeApiIT} rewritten as an out-of-process
 * {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}. Edge-branch coverage for {@code
 * PreviewService}/{@code PreviewResource}'s three {@code thumbnailXxx} methods (image/pdf/
 * document) that {@link ThumbnailApiIT} does not exercise (that class covers only happy-path
 * retrieval and version resolution). Each of the three methods shares the same two-branch shape
 * as their {@code previewXxx} siblings:
 *
 * <ul>
 *   <li>{@code tryCheckNode.isSuccess()} — {@link PreviewEdgeApiIT} covers the not-found/
 *       permission-denied direction only via the {@code previewXxx} methods (shared bytecode
 *       validation logic, but a DIFFERENT call site than the {@code thumbnailXxx} methods); the
 *       {@code thumbnailXxx} call sites themselves were never exercised with a failing {@code
 *       checkNodePermissionAndExistence}.
 *   <li>{@code isPreviewChanged(...)} — {@link PreviewEdgeApiIT} covers the ETag/304 direction for
 *       {@code previewPdf}/{@code previewDocument}/{@code previewImage}, but never for any of the
 *       three {@code thumbnailXxx} methods.
 * </ul>
 *
 * <p>All 6 methods and their assertions are preserved verbatim; only the seeding (real {@code
 * POST /upload} capturing the server-generated node id) and the preview stub/verify ({@link
 * AbstractFilesIT#previewServes}/{@link AbstractFilesIT#verifyPreviewServed}) changed.
 */
class ThumbnailEdgeApiIT extends AbstractFilesIT {

  private static final String OWNER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OTHER_USER_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String OWNER_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String OTHER_USER_COOKIE = "ZM_AUTH_TOKEN=fake-token-b";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", OWNER_ID);
    FilesStackTestResource.getUserManagementService().registerToken("fake-token-b", OTHER_USER_ID);
  }

  /** Restores the preview/mailbox WireMock to its baseline stubs after every test in this class. */
  @AfterEach
  void resetPreviewStubsAfterEach() {
    FilesStackTestResource.resetPreviewMailboxStubs();
  }

  // --- tryCheckNode.isSuccess() == false (permission-denied), one per thumbnail family ----------

  @Test
  void givenARequesterWithoutPermissionTheImageThumbnailApiShouldReturnA404StatusCode() {
    // No share created for OTHER_USER_ID.
    String nodeId =
        seedFile(
            "private.png", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);

    Response response =
        previewGet("/preview/image/" + nodeId + "/5x5/thumbnail", OTHER_USER_COOKIE, null);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(404);
  }

  @Test
  void givenARequesterWithoutPermissionThePdfThumbnailApiShouldReturnA404StatusCode() {
    String nodeId =
        seedFile(
            "private.pdf", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);

    Response response =
        previewGet("/preview/pdf/" + nodeId + "/5x5/thumbnail", OTHER_USER_COOKIE, null);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(404);
  }

  @Test
  void givenANonExistentNodeTheDocumentThumbnailApiShouldReturnA404StatusCode() {
    Response response =
        previewGet(
            "/preview/document/00000000-0000-0000-0000-00000000ff01/5x5/thumbnail",
            OWNER_COOKIE,
            null);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(404);
  }

  // --- isPreviewChanged() == false (ETag match -> 304), one per thumbnail family -----------------

  @Test
  void givenAMatchingIfNoneMatchHeaderTheRepeatedImageThumbnailRequestShouldReturnA304StatusCode() {
    String nodeId =
        seedFile(
            "cacheable-thumb.png",
            LOCAL_ROOT,
            "content".getBytes(StandardCharsets.UTF_8),
            OWNER_COOKIE);

    String expectationId =
        previewServes(
            "/preview/image/" + nodeId + "/1/5x5/thumbnail/",
            OWNER_ID,
            "content".getBytes(),
            "image/jpeg");

    Response firstResponse =
        previewGet("/preview/image/" + nodeId + "/5x5/thumbnail", OWNER_COOKIE, null);

    Assertions.assertThat(firstResponse.getStatusCode()).isEqualTo(200);
    String etag = firstResponse.getHeader("etag");
    Assertions.assertThat(etag).isNotNull();

    Response secondResponse =
        previewGet("/preview/image/" + nodeId + "/5x5/thumbnail", OWNER_COOKIE, etag);

    Assertions.assertThat(secondResponse.getStatusCode()).isEqualTo(304);

    verifyPreviewServed(expectationId);
  }

  @Test
  void givenAMatchingIfNoneMatchHeaderTheRepeatedPdfThumbnailRequestShouldReturnA304StatusCode() {
    String nodeId =
        seedFile(
            "cacheable-thumb.pdf",
            LOCAL_ROOT,
            "content".getBytes(StandardCharsets.UTF_8),
            OWNER_COOKIE);

    String expectationId =
        previewServes(
            "/preview/pdf/" + nodeId + "/1/5x5/thumbnail/",
            OWNER_ID,
            "content".getBytes(),
            "image/jpeg");

    Response firstResponse =
        previewGet("/preview/pdf/" + nodeId + "/5x5/thumbnail", OWNER_COOKIE, null);

    Assertions.assertThat(firstResponse.getStatusCode()).isEqualTo(200);
    String etag = firstResponse.getHeader("etag");
    Assertions.assertThat(etag).isNotNull();

    Response secondResponse =
        previewGet("/preview/pdf/" + nodeId + "/5x5/thumbnail", OWNER_COOKIE, etag);

    Assertions.assertThat(secondResponse.getStatusCode()).isEqualTo(304);

    verifyPreviewServed(expectationId);
  }

  @Test
  void givenAMatchingIfNoneMatchHeaderTheRepeatedDocumentThumbnailRequestShouldReturnA304StatusCode() {
    String nodeId =
        seedFile(
            "cacheable-thumb.xls", LOCAL_ROOT, "0".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);

    String expectationId =
        previewServes(
            "/preview/document/" + nodeId + "/1/5x5/thumbnail/",
            OWNER_ID,
            "0".getBytes(),
            "image/jpeg");

    Response firstResponse =
        previewGet("/preview/document/" + nodeId + "/5x5/thumbnail", OWNER_COOKIE, null);

    Assertions.assertThat(firstResponse.getStatusCode()).isEqualTo(200);
    String etag = firstResponse.getHeader("etag");
    Assertions.assertThat(etag).isNotNull();

    Response secondResponse =
        previewGet("/preview/document/" + nodeId + "/5x5/thumbnail", OWNER_COOKIE, etag);

    Assertions.assertThat(secondResponse.getStatusCode()).isEqualTo(304);

    verifyPreviewServed(expectationId);
  }
}
