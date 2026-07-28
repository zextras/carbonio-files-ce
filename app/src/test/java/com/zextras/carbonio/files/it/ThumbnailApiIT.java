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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@code com.zextras.carbonio.files.acceptance.ThumbnailApiIT} rewritten as an out-of-process
 * {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}: {@code GET
 * /preview/.../thumbnail} ({@code PreviewService}'s {@code getThumbnailOfXxx} methods — same
 * {@code PreviewClient}-only dependency as {@link PreviewApiIT}, no {@code Filestore}
 * involvement). All 7 methods and their assertions are preserved verbatim; only the seeding (real
 * {@code POST /upload}/{@code /upload-version} capturing the server-generated node id) and the
 * preview stub/verify ({@link AbstractFilesIT#previewServes}/{@link
 * AbstractFilesIT#verifyPreviewServed}) changed. {@code clearFileVersionCache()} is DROPPED per
 * the plan (fresh-UUID API seeding means cache keys never collide across tests).
 */
class ThumbnailApiIT extends AbstractFilesIT {

  private static final String OWNER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OWNER_COOKIE = "ZM_AUTH_TOKEN=fake-token";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", OWNER_ID);
  }

  /** Restores the preview/mailbox WireMock to its baseline stubs after every test in this class. */
  @AfterEach
  void resetPreviewStubsAfterEach() {
    FilesStackTestResource.resetPreviewMailboxStubs();
  }

  @Test
  void givenAnExistingDocumentTheGetThumbnailApiShouldGetAndReturnTheThumbnailWithLangTag() {
    // Given
    String nodeId =
        seedFile("FILE.XLS", LOCAL_ROOT, "0".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);

    String callThumbnailExpectationId =
        previewServes(
            "/preview/document/" + nodeId + "/1/5x5/thumbnail/",
            OWNER_ID,
            "0".getBytes(),
            "image/jpeg");

    // When
    Response response =
        previewGet("/preview/document/" + nodeId + "/5x5/thumbnail", OWNER_COOKIE, null);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(response.getContentType()).contains("image/jpeg");

    verifyPreviewServed(callThumbnailExpectationId);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "?version=2"})
  void givenTwoVersionsOfAnExistingDocumentTheGetThumbnailApiShouldReturnTheJpegOfTheLatestVersion(
      String versionQueryParam) {
    // Given
    String nodeId =
        seedFile("pres.odp", LOCAL_ROOT, "0".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    seedVersion(nodeId, "0".getBytes(StandardCharsets.UTF_8), "pres.odp", OWNER_COOKIE);

    String callThumbnailExpectationId =
        previewServes(
            "/preview/document/" + nodeId + "/2/5x5/thumbnail/",
            OWNER_ID,
            "0".getBytes(),
            "image/jpeg");

    // When
    Response response =
        previewGet(
            "/preview/document/" + nodeId + "/5x5/thumbnail" + versionQueryParam,
            OWNER_COOKIE,
            null);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    verifyPreviewServed(callThumbnailExpectationId);
  }

  @Test
  void givenTwoVersionsOfAnExistingDocumentTheGetThumbnailApiShouldReturnTheJpegOfTheFirstVersion() {
    // Given
    String nodeId =
        seedFile("pres.odp", LOCAL_ROOT, "0".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    seedVersion(nodeId, "0".getBytes(StandardCharsets.UTF_8), "pres.odp", OWNER_COOKIE);

    String callThumbnailExpectationId =
        previewServes(
            "/preview/document/" + nodeId + "/1/5x5/thumbnail/",
            OWNER_ID,
            "0".getBytes(),
            "image/jpeg");

    // When
    Response response =
        previewGet("/preview/document/" + nodeId + "/5x5/thumbnail?version=1", OWNER_COOKIE, null);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    verifyPreviewServed(callThumbnailExpectationId);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "?version=3"})
  void givenThreeVersionsOfAnExistingPdfTheGetPreviewApiShouldReturnTheJpegOfTheLatestVersion(
      String versionQueryParam) {
    // Given
    String nodeId =
        seedFile("buy_me.pdf", LOCAL_ROOT, "0".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    seedVersion(nodeId, "0".getBytes(StandardCharsets.UTF_8), "buy_me.pdf", OWNER_COOKIE);
    seedVersion(nodeId, "0".getBytes(StandardCharsets.UTF_8), "buy_me.pdf", OWNER_COOKIE);

    String callThumbnailExpectationId =
        previewServes(
            "/preview/pdf/" + nodeId + "/3/5x5/thumbnail/",
            OWNER_ID,
            "0".getBytes(),
            "image/jpeg");

    // When
    Response response =
        previewGet(
            "/preview/pdf/" + nodeId + "/5x5/thumbnail/" + versionQueryParam, OWNER_COOKIE, null);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    verifyPreviewServed(callThumbnailExpectationId);
  }

  @Test
  void givenThreeVersionsOfAnExistingPdfTheGetThumbnailApiShouldReturnTheJpegOfTheSecondVersion() {
    // Given
    String nodeId =
        seedFile("buy_me.pdf", LOCAL_ROOT, "0".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    seedVersion(nodeId, "0".getBytes(StandardCharsets.UTF_8), "buy_me.pdf", OWNER_COOKIE);
    seedVersion(nodeId, "0".getBytes(StandardCharsets.UTF_8), "buy_me.pdf", OWNER_COOKIE);

    String callThumbnailExpectationId =
        previewServes(
            "/preview/pdf/" + nodeId + "/2/5x5/thumbnail/",
            OWNER_ID,
            "0".getBytes(),
            "image/jpeg");

    // When
    Response response =
        previewGet("/preview/pdf/" + nodeId + "/5x5/thumbnail?version=2", OWNER_COOKIE, null);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    verifyPreviewServed(callThumbnailExpectationId);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "?version=2"})
  void givenTwoVersionsOfAnExistingPngImageTheGetThumbnailApiShouldReturnTheJpegOfTheLatestVersion(
      String versionQueryParam) {
    // Given
    String nodeId =
        seedFile(
            "don't open.png", LOCAL_ROOT, "0".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    seedVersion(nodeId, "0".getBytes(StandardCharsets.UTF_8), "don't open.png", OWNER_COOKIE);

    String callThumbnailExpectationId =
        previewServes(
            "/preview/image/" + nodeId + "/2/5x5/thumbnail/",
            OWNER_ID,
            "0".getBytes(),
            "image/jpeg");

    // When
    Response response =
        previewGet(
            "/preview/image/" + nodeId + "/5x5/thumbnail" + versionQueryParam, OWNER_COOKIE, null);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    verifyPreviewServed(callThumbnailExpectationId);
  }

  @Test
  void givenTwoVersionsOfAnExistingJpegImageTheGetThumbnailApiShouldReturnTheJpegOfTheFirstVersion() {
    // Given — real image/jpeg mimeType via the ".jpg" extension (see PreviewApiIT's analogous
    // scenario for why: API-seeding cannot reproduce the seam's inconsistent png-filename/
    // jpeg-mimeType fixture, so the INTENT — "an image node stored as image/jpeg" — is preserved
    // via a real .jpg filename instead).
    String nodeId =
        seedFile("photo.jpg", LOCAL_ROOT, "0".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    seedVersion(nodeId, "0".getBytes(StandardCharsets.UTF_8), "photo.jpg", OWNER_COOKIE);

    String callThumbnailExpectationId =
        previewServes(
            "/preview/image/" + nodeId + "/1/5x5/thumbnail/",
            OWNER_ID,
            "0".getBytes(),
            "image/jpeg");

    // When
    Response response =
        previewGet(
            "/preview/image/" + nodeId + "/5x5/thumbnail/?version=1", OWNER_COOKIE, null);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    verifyPreviewServed(callThumbnailExpectationId);
  }
}
