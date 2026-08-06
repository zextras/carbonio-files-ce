// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@code com.zextras.carbonio.files.acceptance.PreviewEdgeApiIT} rewritten as an out-of-process
 * {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}. Edge-branch coverage for {@code
 * PreviewService}/{@code PreviewResource} that {@link PreviewApiIT}/{@link ThumbnailApiIT} do not
 * exercise (those two cover only the happy paths). This class targets:
 *
 * <ul>
 *   <li>MIME-type mismatch per family (image/pdf/document) -&gt; 400 ({@code BadRequestException}).
 *   <li>The generic {@code /preview/<unmatched>} fall-through -&gt; 400.
 *   <li>The {@code IllegalArgumentException} catch branch (unparsable {@code ?version=} value)
 *       -&gt; 400.
 *   <li>ETag / {@code If-None-Match} caching: first request -&gt; 200 + ETag; matching
 *       If-None-Match -&gt; 304; stale/mismatched If-None-Match -&gt; 200 again (same ETag).
 *   <li>Permission-denied and not-found on the shared {@code checkNodePermissionAndExistence} path
 *       (exercised once, via the pdf family, since the branch is shared bytecode).
 * </ul>
 *
 * <p>All 19 methods and their assertions are preserved verbatim; only the seeding (real {@code POST
 * /upload} capturing the server-generated node id, replacing the seam's {@code PopulatorNode}
 * fixture) and the preview stub/verify/fail ({@link AbstractFilesIT#previewServes}/{@link
 * AbstractFilesIT#verifyPreviewServed}/{@link AbstractFilesIT#previewFails}) changed. The 6
 * "matched path, wrong HTTP verb" and 2 non-existent-node scenarios need no seeded node at all (the
 * guard trips before any DB lookup), so they keep fixed placeholder ids exactly like the seam
 * version. {@code clearFileVersionCache()} is DROPPED per the plan (fresh-UUID API seeding means
 * cache keys never collide across tests).
 *
 * <p><b>Still NOT covered here (a genuine gap, carried over from the seam):</b> the
 * document-preview ETag's locale component (same node, different {@code Accept-Language} must NOT
 * 304) — production does not read an {@code Accept-Language} header at all; the requester's {@code
 * Locale} comes from the user-management profile, and {@code MockUserManagementService} hardcodes
 * every registered user's locale to {@code "en"} with no knob to vary it.
 */
class PreviewEdgeApiIT extends AbstractFilesIT {

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

  // --- MIME-type mismatch, one per family --------------------------------------------------

  @Test
  void givenANonImageNodeThePreviewImageApiShouldReturnA400StatusCode() {
    String nodeId =
        seedFile(
            "not-an-image.txt",
            LOCAL_ROOT,
            "content".getBytes(StandardCharsets.UTF_8),
            OWNER_COOKIE);

    Response response = previewGet("/preview/image/" + nodeId + "/100x100", OWNER_COOKIE, null);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(400);
  }

  @Test
  void givenANonPdfNodeThePreviewPdfApiShouldReturnA400StatusCode() {
    String nodeId =
        seedFile(
            "not-a-pdf.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);

    Response response = previewGet("/preview/pdf/" + nodeId + "/", OWNER_COOKIE, null);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(400);
  }

  @Test
  void givenANodeOutsideTheDocumentAllowListThePreviewDocumentApiShouldReturnA400StatusCode() {
    String nodeId =
        seedFile(
            "not-a-document.png",
            LOCAL_ROOT,
            "content".getBytes(StandardCharsets.UTF_8),
            OWNER_COOKIE);

    Response response = previewGet("/preview/document/" + nodeId, OWNER_COOKIE, null);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(400);
  }

  // --- Generic unmatched fall-through --------------------------------------------------------

  @Test
  void givenAnUnmatchedPreviewSubPathTheApiShouldReturnA400StatusCode() {
    Response response = previewGet("/preview/some-unsupported-kind/whatever", OWNER_COOKIE, null);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(400);
  }

  /**
   * F3 (Quarkus-rewrite hardening restoration): legacy placed {@code auth-handler} before {@code
   * preview-handler} for the WHOLE {@code /preview/**} family (see {@code
   * core/.../HttpRoutingHandler#channelRead0}, lines ~179-186), so even a request that ultimately
   * falls through to the generic 400 was authenticated FIRST. {@code
   * PreviewResource#unmatchedPreviewPath} is the only method in the class that never called {@code
   * authenticator.requireUser}, so an unauthenticated request reached the generic 400 without ever
   * being challenged. Deliberately NO Cookie header here.
   */
  @Test
  void givenAnUnauthenticatedUnmatchedPreviewSubPathTheApiShouldReturnA401StatusCode() {
    Response response = previewGet("/preview/some-unsupported-kind/whatever", null, null);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(401);
  }

  // --- Malformed area segment (F4) -------------------------------------------------------------

  /**
   * F4 (Quarkus-rewrite hardening restoration): legacy constrained the {@code area} path segment to
   * {@code ([\d]*x[\d]*)} for every image/pdf/document (thumbnail) route that carries one (see
   * {@code core/.../Constants.java}, lines ~1090-1108: {@code PREVIEW_IMAGE}/{@code
   * THUMBNAIL_IMAGE}/{@code THUMBNAIL_PDF}/{@code THUMBNAIL_DOCUMENT}). The port's
   * {@code @Path("/image/{nodeId}/{area}")} (and the 3 sibling thumbnail templates) left {@code
   * area} unconstrained, so a malformed value ran the FULL permission check and a preview call
   * before ever collapsing to 404 -- restoring the pattern constraint makes JAX-RS itself reject
   * the template match, falling through to the generic 400 fallback BEFORE any permission check or
   * downstream call (so, unlike the not-found/permission-denied scenarios above, no node needs to
   * exist at all).
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "/preview/image/00000000-0000-0000-0000-000000000299/not-an-area",
        "/preview/image/00000000-0000-0000-0000-000000000299/not-an-area/thumbnail",
        "/preview/pdf/00000000-0000-0000-0000-000000000299/not-an-area/thumbnail",
        "/preview/document/00000000-0000-0000-0000-000000000299/not-an-area/thumbnail"
      })
  void givenAMalformedAreaSegmentThePreviewApiShouldReturnA400StatusCode(String uri) {
    Response response = previewGet(uri, OWNER_COOKIE, null);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(400);
  }

  // --- IllegalArgumentException catch (unparsable query parameter) --------------------------

  @Test
  void givenANonNumericVersionQueryParameterThePreviewApiShouldReturnA400StatusCode() {
    // No node needs to exist: parseQueryParameters() runs, and fails, before any DB lookup.
    Response response =
        previewGet(
            "/preview/pdf/00000000-0000-0000-0000-000000000299/?version=not-a-number",
            OWNER_COOKIE,
            null);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(400);
  }

  // --- Permission-denied / not-found on the shared checkNodePermissionAndExistence path -------

  @Test
  void givenANonExistentNodeThePreviewApiShouldReturnA404StatusCode() {
    Response response =
        previewGet("/preview/pdf/00000000-0000-0000-0000-000000000298/", OWNER_COOKIE, null);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(404);
  }

  @Test
  void givenARequesterWithoutPermissionThePreviewApiShouldReturnA404StatusCode() {
    // No share created for OTHER_USER_ID: it has no permission on this node.
    String nodeId =
        seedFile(
            "private.pdf", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);

    Response response = previewGet("/preview/pdf/" + nodeId + "/", OTHER_USER_COOKIE, null);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(404);
  }

  // --- ETag / If-None-Match caching -----------------------------------------------------------

  @Test
  void givenAMatchingIfNoneMatchHeaderTheRepeatedPreviewRequestShouldReturnA304StatusCode() {
    String nodeId =
        seedFile(
            "cacheable.pdf", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);

    String expectationId =
        previewServes(
            "/preview/pdf/" + nodeId + "/1/", OWNER_ID, "content".getBytes(), "application/pdf");

    Response firstResponse = previewGet("/preview/pdf/" + nodeId + "/", OWNER_COOKIE, null);

    Assertions.assertThat(firstResponse.getStatusCode()).isEqualTo(200);
    String etag = firstResponse.getHeader("etag");
    Assertions.assertThat(etag).isNotNull();

    Response secondResponse = previewGet("/preview/pdf/" + nodeId + "/", OWNER_COOKIE, etag);

    Assertions.assertThat(secondResponse.getStatusCode()).isEqualTo(304);

    verifyPreviewServed(expectationId);
  }

  @Test
  void givenAStaleIfNoneMatchHeaderTheRepeatedPreviewRequestShouldReturnA200StatusCode() {
    String nodeId =
        seedFile(
            "cacheable-stale.pdf",
            LOCAL_ROOT,
            "content".getBytes(StandardCharsets.UTF_8),
            OWNER_COOKIE);

    String expectationId =
        previewServes(
            "/preview/pdf/" + nodeId + "/1/", OWNER_ID, "content".getBytes(), "application/pdf");

    Response firstResponse = previewGet("/preview/pdf/" + nodeId + "/", OWNER_COOKIE, null);

    Assertions.assertThat(firstResponse.getStatusCode()).isEqualTo(200);
    String etag = firstResponse.getHeader("etag");

    Response secondResponse =
        previewGet("/preview/pdf/" + nodeId + "/", OWNER_COOKIE, "clearly-stale-etag-value");

    Assertions.assertThat(secondResponse.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(secondResponse.getHeader("etag")).isEqualTo(etag);

    verifyPreviewServed(expectationId);
  }

  @Test
  void
      givenAMatchingIfNoneMatchHeaderTheRepeatedDocumentPreviewRequestShouldReturnA304StatusCode() {
    String nodeId =
        seedFile("cacheable.xls", LOCAL_ROOT, "0".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);

    String expectationId =
        previewServes(
            "/preview/document/" + nodeId + "/1/", OWNER_ID, "0".getBytes(), "application/pdf");

    Response firstResponse = previewGet("/preview/document/" + nodeId, OWNER_COOKIE, null);

    Assertions.assertThat(firstResponse.getStatusCode()).isEqualTo(200);
    String etag = firstResponse.getHeader("etag");
    Assertions.assertThat(etag).isNotNull();

    Response secondResponse = previewGet("/preview/document/" + nodeId, OWNER_COOKIE, etag);

    Assertions.assertThat(secondResponse.getStatusCode()).isEqualTo(304);

    verifyPreviewServed(expectationId);
  }

  // --- Image-family ETag / If-None-Match caching (previously only exercised for pdf/document) --

  @Test
  void givenAMatchingIfNoneMatchHeaderTheRepeatedImagePreviewRequestShouldReturnA304StatusCode() {
    String nodeId =
        seedFile(
            "cacheable.png", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);

    String expectationId =
        previewServes(
            "/preview/image/" + nodeId + "/1/0x0/", OWNER_ID, "content".getBytes(), "image/png");

    Response firstResponse = previewGet("/preview/image/" + nodeId + "/0x0", OWNER_COOKIE, null);

    Assertions.assertThat(firstResponse.getStatusCode()).isEqualTo(200);
    String etag = firstResponse.getHeader("etag");
    Assertions.assertThat(etag).isNotNull();

    Response secondResponse = previewGet("/preview/image/" + nodeId + "/0x0", OWNER_COOKIE, etag);

    Assertions.assertThat(secondResponse.getStatusCode()).isEqualTo(304);

    verifyPreviewServed(expectationId);
  }

  // --- Preview-service failure fallback (PreviewController#failureResponse via onFailure) -------
  // Mocks#previewFails(String) stubs the Preview mock itself to fail (distinct from the
  // permission/mime-type failures above, which never reach the Preview service at all).
  // Production maps this to a NoSuchElementException -> HTTP 404.

  @Test
  void givenThePreviewServiceFailsTheImagePreviewApiShouldReturnA404StatusCode() {
    String nodeId =
        seedFile(
            "broken.png", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);

    previewFails("/preview/image/" + nodeId + "/1/0x0/");

    Response response = previewGet("/preview/image/" + nodeId + "/0x0", OWNER_COOKIE, null);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(404);
  }

  @Test
  void givenThePreviewServiceFailsTheImageThumbnailApiShouldReturnA404StatusCode() {
    String nodeId =
        seedFile(
            "broken-thumb.png",
            LOCAL_ROOT,
            "content".getBytes(StandardCharsets.UTF_8),
            OWNER_COOKIE);

    previewFails("/preview/image/" + nodeId + "/1/5x5/thumbnail/");

    Response response =
        previewGet("/preview/image/" + nodeId + "/5x5/thumbnail", OWNER_COOKIE, null);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(404);
  }

  @Test
  void givenThePreviewServiceFailsThePdfPreviewApiShouldReturnA404StatusCode() {
    String nodeId =
        seedFile(
            "broken.pdf", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);

    previewFails("/preview/pdf/" + nodeId + "/1/");

    Response response = previewGet("/preview/pdf/" + nodeId + "/", OWNER_COOKIE, null);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(404);
  }

  @Test
  void givenThePreviewServiceFailsThePdfThumbnailApiShouldReturnA404StatusCode() {
    String nodeId =
        seedFile(
            "broken-thumb.pdf",
            LOCAL_ROOT,
            "content".getBytes(StandardCharsets.UTF_8),
            OWNER_COOKIE);

    previewFails("/preview/pdf/" + nodeId + "/1/5x5/thumbnail/");

    Response response = previewGet("/preview/pdf/" + nodeId + "/5x5/thumbnail", OWNER_COOKIE, null);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(404);
  }

  @Test
  void givenThePreviewServiceFailsTheDocumentPreviewApiShouldReturnA404StatusCode() {
    String nodeId =
        seedFile("broken.xls", LOCAL_ROOT, "0".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);

    previewFails("/preview/document/" + nodeId + "/1/");

    Response response = previewGet("/preview/document/" + nodeId, OWNER_COOKIE, null);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(404);
  }

  @Test
  void givenThePreviewServiceFailsTheDocumentThumbnailApiShouldReturnA404StatusCode() {
    String nodeId =
        seedFile(
            "broken-thumb.xls", LOCAL_ROOT, "0".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);

    previewFails("/preview/document/" + nodeId + "/1/5x5/thumbnail/");

    Response response =
        previewGet("/preview/document/" + nodeId + "/5x5/thumbnail", OWNER_COOKIE, null);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(404);
  }

  // --- channelRead0 "matched path, wrong HTTP verb" fallback -----------------------------------
  // Each of the 6 preview/thumbnail routes shares the identical `matcher.find() &&
  // method.equals(GET)` guard; every existing test in the suite only ever sends GET, so the
  // "matched but wrong verb" direction was never taken for ANY of the 6. No node needs to exist:
  // the method check happens before any DB lookup, falling through to the generic 400.

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/preview/image/00000000-0000-0000-0000-000000000299/100x100",
        "/preview/image/00000000-0000-0000-0000-000000000299/100x100/thumbnail",
        "/preview/pdf/00000000-0000-0000-0000-000000000299/",
        "/preview/pdf/00000000-0000-0000-0000-000000000299/100x100/thumbnail",
        "/preview/document/00000000-0000-0000-0000-000000000299",
        "/preview/document/00000000-0000-0000-0000-000000000299/100x100/thumbnail"
      })
  void givenAMatchingPreviewPathWithAWrongHttpVerbTheApiShouldReturnA400StatusCode(String uri) {
    Response response = RestAssured.given().header("Cookie", OWNER_COOKIE).post(uri);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(400);
  }

  // --- checkNodePermissionAndExistence: permission granted but the requested version is absent --
  // Distinct from the not-found/permission-denied tests above (which short-circuit the "&&" on
  // permissionsChecker...has(READ_ONLY), never reaching optFileVersion.isPresent() at all): here
  // permission IS granted, but the specific requested version number doesn't exist.

  @Test
  void givenAPermittedNodeWithANonExistentVersionThePreviewApiShouldReturnA404StatusCode() {
    String nodeId =
        seedFile(
            "onlyOneVersion.pdf",
            LOCAL_ROOT,
            "content".getBytes(StandardCharsets.UTF_8),
            OWNER_COOKIE);

    Response response = previewGet("/preview/pdf/" + nodeId + "/?version=999", OWNER_COOKIE, null);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(404);
  }
}
