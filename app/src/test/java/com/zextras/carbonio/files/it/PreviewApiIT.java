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
 * {@code com.zextras.carbonio.files.acceptance.PreviewApiIT} rewritten as an out-of-process
 * {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}: {@code GET /preview/...} ({@code
 * PreviewService}, which talks ONLY to {@code PreviewClient} + {@code NodeRepository} — no {@code
 * Filestore} involvement, so the seeded file's byte content is irrelevant; only its filename (which
 * drives {@code MimeTypeUtils#detectMimeTypeFromFilename} -> {@code NodeType.getNodeType}, exactly
 * as the seam's {@code PopulatorNode} fixtures hard-coded) and version count matter). All 7 methods
 * and their assertions are preserved verbatim; only the seeding (real {@code POST /upload}/{@code
 * /upload-version} via {@link #seedFile}/{@link #seedVersion}, capturing the server-generated node
 * id instead of the fixed {@code 00000000-...-000000000000} literal) and the preview stub/verify
 * ({@link AbstractFilesIT#previewServes}/{@link AbstractFilesIT#verifyPreviewServed}, replacing
 * {@code Mocks#previewServes}/{@code verifyPreviewServed}) changed. {@code clearFileVersionCache()}
 * is DROPPED per the plan: fresh-UUID API seeding means cache keys never collide across tests, so
 * there is nothing to flush between them.
 */
class PreviewApiIT extends AbstractFilesIT {

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
  void givenACyrillicFilenameThePdfPreviewShouldSendAnAsciiRfc8187ContentDisposition() {
    // Regression: the preview Content-Disposition (the node's full name) must be RFC 8187 filename*
    // (%20 not +). The attachment disposition itself is pre-existing and unchanged.
    String nodeId =
        seedFile("Документ.pdf", LOCAL_ROOT, "0".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    String expectationId =
        previewServes(
            "/preview/pdf/" + nodeId + "/1/", OWNER_ID, "0".getBytes(), "application/pdf");

    Response response = previewGet("/preview/pdf/" + nodeId, OWNER_COOKIE, null);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(response.getHeader("Content-Disposition"))
        .isEqualTo(
            "attachment; filename*=UTF-8''%D0%94%D0%BE%D0%BA%D1%83%D0%BC%D0%B5%D0%BD%D1%82.pdf")
        .doesNotContain("+");
    verifyPreviewServed(expectationId);
  }

  @Test
  void givenAnExistingDocumentTheGetPreviewApiShouldGetAndReturnThePreviewWithLangTag() {
    // Given
    String nodeId =
        seedFile("FILE.XLS", LOCAL_ROOT, "0".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);

    String callPreviewExpectationId =
        previewServes(
            "/preview/document/" + nodeId + "/1/", OWNER_ID, "0".getBytes(), "application/pdf");

    // When
    Response response = previewGet("/preview/document/" + nodeId, OWNER_COOKIE, null);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(response.getContentType()).contains("application/pdf");

    verifyPreviewServed(callPreviewExpectationId);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "?version=2"})
  void givenTwoVersionsOfAnExistingDocumentTheGetPreviewApiShouldReturnThePdfOfTheLatestVersion(
      String versionQueryParam) {
    // Given
    String nodeId =
        seedFile("pres.odp", LOCAL_ROOT, "0".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    seedVersion(nodeId, "0".getBytes(StandardCharsets.UTF_8), "pres.odp", OWNER_COOKIE);

    String callPreviewExpectationId =
        previewServes(
            "/preview/document/" + nodeId + "/2/", OWNER_ID, "0".getBytes(), "application/pdf");

    // When
    Response response =
        previewGet("/preview/document/" + nodeId + versionQueryParam, OWNER_COOKIE, null);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    verifyPreviewServed(callPreviewExpectationId);
  }

  @Test
  void givenTwoVersionsOfAnExistingDocumentTheGetPreviewApiShouldReturnThePdfOfTheFirstVersion() {
    // Given
    String nodeId =
        seedFile("pres.odp", LOCAL_ROOT, "0".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    seedVersion(nodeId, "0".getBytes(StandardCharsets.UTF_8), "pres.odp", OWNER_COOKIE);

    String callPreviewExpectationId =
        previewServes(
            "/preview/document/" + nodeId + "/1/", OWNER_ID, "0".getBytes(), "application/pdf");

    // When
    Response response =
        previewGet("/preview/document/" + nodeId + "?version=1", OWNER_COOKIE, null);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    verifyPreviewServed(callPreviewExpectationId);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "?version=3"})
  void givenThreeVersionsOfAnExistingPdfTheGetPreviewApiShouldReturnThePdfOfTheLatestVersion(
      String versionQueryParam) {
    // Given
    String nodeId =
        seedFile("buy_me.pdf", LOCAL_ROOT, "0".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    seedVersion(nodeId, "0".getBytes(StandardCharsets.UTF_8), "buy_me.pdf", OWNER_COOKIE);
    seedVersion(nodeId, "0".getBytes(StandardCharsets.UTF_8), "buy_me.pdf", OWNER_COOKIE);

    String callPreviewExpectationId =
        previewServes(
            "/preview/pdf/" + nodeId + "/3/", OWNER_ID, "0".getBytes(), "application/pdf");

    // When
    Response response =
        previewGet("/preview/pdf/" + nodeId + "/" + versionQueryParam, OWNER_COOKIE, null);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    verifyPreviewServed(callPreviewExpectationId);
  }

  @Test
  void givenThreeVersionsOfAnExistingPdfTheGetPreviewApiShouldReturnThePdfOfTheSecondVersion() {
    // Given
    String nodeId =
        seedFile("buy_me.pdf", LOCAL_ROOT, "0".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    seedVersion(nodeId, "0".getBytes(StandardCharsets.UTF_8), "buy_me.pdf", OWNER_COOKIE);
    seedVersion(nodeId, "0".getBytes(StandardCharsets.UTF_8), "buy_me.pdf", OWNER_COOKIE);

    String callPreviewExpectationId =
        previewServes(
            "/preview/pdf/" + nodeId + "/2/", OWNER_ID, "0".getBytes(), "application/pdf");

    // When
    Response response = previewGet("/preview/pdf/" + nodeId + "?version=2", OWNER_COOKIE, null);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    verifyPreviewServed(callPreviewExpectationId);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "?version=2"})
  void givenTwoVersionsOfAnExistingPngImageTheGetPreviewApiShouldReturnThePngOfTheLatestVersion(
      String versionQueryParam) {
    // Given
    String nodeId =
        seedFile("don't open.png", LOCAL_ROOT, "0".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    seedVersion(nodeId, "0".getBytes(StandardCharsets.UTF_8), "don't open.png", OWNER_COOKIE);

    String callPreviewExpectationId =
        previewServes(
            "/preview/image/" + nodeId + "/2/0x0/", OWNER_ID, "0".getBytes(), "image/png");

    // When
    Response response =
        previewGet("/preview/image/" + nodeId + "/0x0" + versionQueryParam, OWNER_COOKIE, null);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    verifyPreviewServed(callPreviewExpectationId);
  }

  @Test
  void
      givenTwoVersionsOfAnExistingJpegImageTheGetPreviewApiShouldReturnTheJpegOfTheSecondVersion() {
    // Given — real image/jpeg mimeType via the ".jpg" extension (the seam's PopulatorNode fixture
    // forced an inconsistent png-filename/jpeg-mimeType pair that API-seeding cannot reproduce;
    // the INTENT here — "an image node whose stored mimeType is image/jpeg" — is preserved exactly
    // by naming the file with a real .jpg extension instead).
    String nodeId =
        seedFile("photo.jpg", LOCAL_ROOT, "0".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    seedVersion(nodeId, "0".getBytes(StandardCharsets.UTF_8), "photo.jpg", OWNER_COOKIE);

    String callPreviewExpectationId =
        previewServes(
            "/preview/image/" + nodeId + "/2/0x0/", OWNER_ID, "0".getBytes(), "image/jpeg");

    // When
    Response response =
        previewGet("/preview/image/" + nodeId + "/0x0?version=2", OWNER_COOKIE, null);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    verifyPreviewServed(callPreviewExpectationId);
  }
}
