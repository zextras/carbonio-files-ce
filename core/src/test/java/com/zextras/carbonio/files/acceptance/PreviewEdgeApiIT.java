// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.acceptance;

import com.zextras.carbonio.files.acceptance.seam.FilesTestApp;
import com.zextras.carbonio.files.acceptance.seam.impl.GuiceNettyFilesTestAppBuilder;
import com.zextras.carbonio.files.api.utilities.entities.PopulatorNode;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Edge-branch coverage for {@code PreviewController} that {@code PreviewApiIT}/{@code
 * ThumbnailApiIT} do not exercise (those two cover only the happy paths: successful preview/
 * thumbnail retrieval and version resolution). This class targets:
 *
 * <ul>
 *   <li>MIME-type mismatch per family (image/pdf/document) -&gt; 400 ({@code BadRequestException}).
 *   <li>The generic {@code /preview/<unmatched>} fall-through -&gt; 400.
 *   <li>The {@code IllegalArgumentException} catch branch (unparsable {@code ?version=} value)
 *       -&gt; 400.
 *   <li>ETag / {@code If-None-Match} caching: first request -&gt; 200 + ETag; matching If-None-Match
 *       -&gt; 304; stale/mismatched If-None-Match -&gt; 200 again (same ETag).
 *   <li>Permission-denied and not-found on the shared {@code checkNodePermissionAndExistence} path
 *       (exercised once, via the pdf family, since the branch is shared bytecode).
 * </ul>
 *
 * <p><b>Gap-closing pass additions</b> (the wave-2D report flagged the preview-service-failure
 * fallback as missing a seam capability; {@code Mocks#previewFails(String)} now closes it — see
 * the {@code *ThePreviewServiceFailsTheApiShouldReturnA404StatusCode} tests below). Also closes:
 * the {@code channelRead0} "matched path, wrong HTTP verb" fallback (6 identical {@code
 * matcher.find() && method.equals(GET)} conditions, one per preview/thumbnail route, all
 * previously untested since every existing test uses GET); the image-family ETag/304 case
 * (previously only exercised for pdf/document); and {@code checkNodePermissionAndExistence}'s
 * "permission granted but the requested version doesn't exist" branch (previously every
 * not-found/permission-denied test hit the OTHER short-circuit of that {@code &&}, never this one).
 *
 * <p><b>Still NOT covered here (a genuine seam gap, out of THIS pass's authorized scope):</b> the
 * document-preview ETag's locale component (same node, different {@code Accept-Language} must NOT
 * 304) — production does not read an {@code Accept-Language} header at all; the requester's
 * {@code Locale} comes from the user-management profile ({@code UserRepositoryRest#parseLocale}),
 * and {@code MockUserManagementService} hardcodes every registered user's locale to {@code "en"}
 * with no seam knob to vary it.
 */
class PreviewEdgeApiIT {

  static FilesTestApp app;

  private static final String OWNER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OTHER_USER_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String OWNER_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String OTHER_USER_COOKIE = "ZM_AUTH_TOKEN=fake-token-other";

  @BeforeAll
  static void init() {
    app =
        GuiceNettyFilesTestAppBuilder.aFilesTestApp()
            .withDatabase()
            .withServiceDiscover()
            .withPreview()
            .withUserManagement(
                Map.of(
                    "fake-token", OWNER_ID,
                    "fake-token-other", OTHER_USER_ID))
            .build();
  }

  @AfterEach
  void cleanUp() {
    app.backdoor().resetDatabase();
    app.backdoor().clearFileVersionCache();
    app.mocks().reset();
  }

  @AfterAll
  static void cleanUpAll() {
    app.close();
  }

  private static String extractHeader(HttpResponse response, String name) {
    return response.getHeaders().stream()
        .filter(header -> header.getKey().equalsIgnoreCase(name))
        .map(Map.Entry::getValue)
        .findFirst()
        .orElse(null);
  }

  // --- MIME-type mismatch, one per family --------------------------------------------------

  @Test
  void givenANonImageNodeThePreviewImageApiShouldReturnA400StatusCode() {
    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                "00000000-0000-0000-0000-000000000201",
                OWNER_ID,
                OWNER_ID,
                "LOCAL_ROOT",
                "not-an-image.txt",
                "",
                NodeType.TEXT,
                "LOCAL_ROOT",
                10L,
                "text/plain"));

    final HttpRequest httpRequest =
        HttpRequest.of(
            "GET",
            "/preview/image/00000000-0000-0000-0000-000000000201/100x100",
            OWNER_COOKIE,
            null);

    final HttpResponse httpResponse = app.send(httpRequest);

    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(400);
  }

  @Test
  void givenANonPdfNodeThePreviewPdfApiShouldReturnA400StatusCode() {
    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                "00000000-0000-0000-0000-000000000202",
                OWNER_ID,
                OWNER_ID,
                "LOCAL_ROOT",
                "not-a-pdf.txt",
                "",
                NodeType.TEXT,
                "LOCAL_ROOT",
                10L,
                "text/plain"));

    final HttpRequest httpRequest =
        HttpRequest.of(
            "GET", "/preview/pdf/00000000-0000-0000-0000-000000000202/", OWNER_COOKIE, null);

    final HttpResponse httpResponse = app.send(httpRequest);

    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(400);
  }

  @Test
  void givenANodeOutsideTheDocumentAllowListThePreviewDocumentApiShouldReturnA400StatusCode() {
    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                "00000000-0000-0000-0000-000000000203",
                OWNER_ID,
                OWNER_ID,
                "LOCAL_ROOT",
                "not-a-document.png",
                "",
                NodeType.IMAGE,
                "LOCAL_ROOT",
                10L,
                "image/png"));

    final HttpRequest httpRequest =
        HttpRequest.of(
            "GET", "/preview/document/00000000-0000-0000-0000-000000000203", OWNER_COOKIE, null);

    final HttpResponse httpResponse = app.send(httpRequest);

    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(400);
  }

  // --- Generic unmatched fall-through --------------------------------------------------------

  @Test
  void givenAnUnmatchedPreviewSubPathTheApiShouldReturnA400StatusCode() {
    final HttpRequest httpRequest =
        HttpRequest.of("GET", "/preview/some-unsupported-kind/whatever", OWNER_COOKIE, null);

    final HttpResponse httpResponse = app.send(httpRequest);

    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(400);
  }

  // --- IllegalArgumentException catch (unparsable query parameter) --------------------------

  @Test
  void givenANonNumericVersionQueryParameterThePreviewApiShouldReturnA400StatusCode() {
    // No node needs to exist: parseQueryParameters() runs, and fails, before any DB lookup.
    final HttpRequest httpRequest =
        HttpRequest.of(
            "GET",
            "/preview/pdf/00000000-0000-0000-0000-000000000299/?version=not-a-number",
            OWNER_COOKIE,
            null);

    final HttpResponse httpResponse = app.send(httpRequest);

    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(400);
  }

  // --- Permission-denied / not-found on the shared checkNodePermissionAndExistence path -------

  @Test
  void givenANonExistentNodeThePreviewApiShouldReturnA404StatusCode() {
    final HttpRequest httpRequest =
        HttpRequest.of(
            "GET", "/preview/pdf/00000000-0000-0000-0000-000000000298/", OWNER_COOKIE, null);

    final HttpResponse httpResponse = app.send(httpRequest);

    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(404);
  }

  @Test
  void givenARequesterWithoutPermissionThePreviewApiShouldReturnA404StatusCode() {
    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                "00000000-0000-0000-0000-000000000204",
                OWNER_ID,
                OWNER_ID,
                "LOCAL_ROOT",
                "private.pdf",
                "",
                NodeType.APPLICATION,
                "LOCAL_ROOT",
                10L,
                "application/pdf"));
    // No share created for OTHER_USER_ID: it has no permission on this node.

    final HttpRequest httpRequest =
        HttpRequest.of(
            "GET",
            "/preview/pdf/00000000-0000-0000-0000-000000000204/",
            OTHER_USER_COOKIE,
            null);

    final HttpResponse httpResponse = app.send(httpRequest);

    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(404);
  }

  // --- ETag / If-None-Match caching -----------------------------------------------------------

  @Test
  void givenAMatchingIfNoneMatchHeaderTheRepeatedPreviewRequestShouldReturnA304StatusCode() {
    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                "00000000-0000-0000-0000-000000000205",
                OWNER_ID,
                OWNER_ID,
                "LOCAL_ROOT",
                "cacheable.pdf",
                "",
                NodeType.APPLICATION,
                "LOCAL_ROOT",
                10L,
                "application/pdf"));

    String expectationId =
        app.mocks()
            .previewServes(
                "/preview/pdf/00000000-0000-0000-0000-000000000205/1/",
                OWNER_ID,
                "content".getBytes(),
                "application/pdf");

    final HttpRequest firstRequest =
        HttpRequest.of(
            "GET", "/preview/pdf/00000000-0000-0000-0000-000000000205/", OWNER_COOKIE, null);
    final HttpResponse firstResponse = app.send(firstRequest);

    Assertions.assertThat(firstResponse.getStatus()).isEqualTo(200);
    String etag = extractHeader(firstResponse, "etag");
    Assertions.assertThat(etag).isNotNull();

    final HttpRequest secondRequest =
        HttpRequest.of(
            "GET",
            "/preview/pdf/00000000-0000-0000-0000-000000000205/",
            OWNER_COOKIE,
            List.of(Map.entry("If-None-Match", etag)),
            null);
    final HttpResponse secondResponse = app.send(secondRequest);

    Assertions.assertThat(secondResponse.getStatus()).isEqualTo(304);

    app.mocks().verifyPreviewServed(expectationId);
  }

  @Test
  void givenAStaleIfNoneMatchHeaderTheRepeatedPreviewRequestShouldReturnA200StatusCode() {
    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                "00000000-0000-0000-0000-000000000206",
                OWNER_ID,
                OWNER_ID,
                "LOCAL_ROOT",
                "cacheable-stale.pdf",
                "",
                NodeType.APPLICATION,
                "LOCAL_ROOT",
                10L,
                "application/pdf"));

    String expectationId =
        app.mocks()
            .previewServes(
                "/preview/pdf/00000000-0000-0000-0000-000000000206/1/",
                OWNER_ID,
                "content".getBytes(),
                "application/pdf");

    final HttpRequest firstRequest =
        HttpRequest.of(
            "GET", "/preview/pdf/00000000-0000-0000-0000-000000000206/", OWNER_COOKIE, null);
    final HttpResponse firstResponse = app.send(firstRequest);

    Assertions.assertThat(firstResponse.getStatus()).isEqualTo(200);
    String etag = extractHeader(firstResponse, "etag");

    final HttpRequest secondRequest =
        HttpRequest.of(
            "GET",
            "/preview/pdf/00000000-0000-0000-0000-000000000206/",
            OWNER_COOKIE,
            List.of(Map.entry("If-None-Match", "clearly-stale-etag-value")),
            null);
    final HttpResponse secondResponse = app.send(secondRequest);

    Assertions.assertThat(secondResponse.getStatus()).isEqualTo(200);
    Assertions.assertThat(extractHeader(secondResponse, "etag")).isEqualTo(etag);

    app.mocks().verifyPreviewServed(expectationId);
  }

  @Test
  void givenAMatchingIfNoneMatchHeaderTheRepeatedDocumentPreviewRequestShouldReturnA304StatusCode() {
    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                "00000000-0000-0000-0000-000000000207",
                OWNER_ID,
                OWNER_ID,
                "LOCAL_ROOT",
                "cacheable.xls",
                "",
                NodeType.SPREADSHEET,
                "LOCAL_ROOT",
                7L,
                "application/vnd.ms-excel"));

    String expectationId =
        app.mocks()
            .previewServes(
                "/preview/document/00000000-0000-0000-0000-000000000207/1/",
                OWNER_ID,
                "0".getBytes(),
                "application/pdf");

    final HttpRequest firstRequest =
        HttpRequest.of(
            "GET", "/preview/document/00000000-0000-0000-0000-000000000207", OWNER_COOKIE, null);
    final HttpResponse firstResponse = app.send(firstRequest);

    Assertions.assertThat(firstResponse.getStatus()).isEqualTo(200);
    String etag = extractHeader(firstResponse, "etag");
    Assertions.assertThat(etag).isNotNull();

    final HttpRequest secondRequest =
        HttpRequest.of(
            "GET",
            "/preview/document/00000000-0000-0000-0000-000000000207",
            OWNER_COOKIE,
            List.of(Map.entry("If-None-Match", etag)),
            null);
    final HttpResponse secondResponse = app.send(secondRequest);

    Assertions.assertThat(secondResponse.getStatus()).isEqualTo(304);

    app.mocks().verifyPreviewServed(expectationId);
  }

  // --- Image-family ETag / If-None-Match caching (previously only exercised for pdf/document) --

  @Test
  void givenAMatchingIfNoneMatchHeaderTheRepeatedImagePreviewRequestShouldReturnA304StatusCode() {
    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                "00000000-0000-0000-0000-000000000208",
                OWNER_ID,
                OWNER_ID,
                "LOCAL_ROOT",
                "cacheable.png",
                "",
                NodeType.IMAGE,
                "LOCAL_ROOT",
                10L,
                "image/png"));

    String expectationId =
        app.mocks()
            .previewServes(
                "/preview/image/00000000-0000-0000-0000-000000000208/1/0x0/",
                OWNER_ID,
                "content".getBytes(),
                "image/png");

    final HttpRequest firstRequest =
        HttpRequest.of(
            "GET",
            "/preview/image/00000000-0000-0000-0000-000000000208/0x0",
            OWNER_COOKIE,
            null);
    final HttpResponse firstResponse = app.send(firstRequest);

    Assertions.assertThat(firstResponse.getStatus()).isEqualTo(200);
    String etag = extractHeader(firstResponse, "etag");
    Assertions.assertThat(etag).isNotNull();

    final HttpRequest secondRequest =
        HttpRequest.of(
            "GET",
            "/preview/image/00000000-0000-0000-0000-000000000208/0x0",
            OWNER_COOKIE,
            List.of(Map.entry("If-None-Match", etag)),
            null);
    final HttpResponse secondResponse = app.send(secondRequest);

    Assertions.assertThat(secondResponse.getStatus()).isEqualTo(304);

    app.mocks().verifyPreviewServed(expectationId);
  }

  // --- Preview-service failure fallback (PreviewController#failureResponse via onFailure) -------
  // Closes the wave-2D-reported gap: Mocks#previewFails(String) stubs the Preview mock itself to
  // fail (distinct from the permission/mime-type failures above, which never reach the Preview
  // service at all). Production maps this (the SDK's PreviewException is not a
  // BadRequestException) to a NoSuchElementException -> HTTP 404.

  @Test
  void givenThePreviewServiceFailsTheImagePreviewApiShouldReturnA404StatusCode() {
    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                "00000000-0000-0000-0000-000000000209",
                OWNER_ID,
                OWNER_ID,
                "LOCAL_ROOT",
                "broken.png",
                "",
                NodeType.IMAGE,
                "LOCAL_ROOT",
                10L,
                "image/png"));

    app.mocks().previewFails("/preview/image/00000000-0000-0000-0000-000000000209/1/0x0/");

    final HttpRequest httpRequest =
        HttpRequest.of(
            "GET",
            "/preview/image/00000000-0000-0000-0000-000000000209/0x0",
            OWNER_COOKIE,
            null);
    final HttpResponse httpResponse = app.send(httpRequest);

    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(404);
  }

  @Test
  void givenThePreviewServiceFailsTheImageThumbnailApiShouldReturnA404StatusCode() {
    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                "00000000-0000-0000-0000-000000000210",
                OWNER_ID,
                OWNER_ID,
                "LOCAL_ROOT",
                "broken-thumb.png",
                "",
                NodeType.IMAGE,
                "LOCAL_ROOT",
                10L,
                "image/png"));

    app.mocks()
        .previewFails("/preview/image/00000000-0000-0000-0000-000000000210/1/5x5/thumbnail/");

    final HttpRequest httpRequest =
        HttpRequest.of(
            "GET",
            "/preview/image/00000000-0000-0000-0000-000000000210/5x5/thumbnail",
            OWNER_COOKIE,
            null);
    final HttpResponse httpResponse = app.send(httpRequest);

    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(404);
  }

  @Test
  void givenThePreviewServiceFailsThePdfPreviewApiShouldReturnA404StatusCode() {
    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                "00000000-0000-0000-0000-000000000211",
                OWNER_ID,
                OWNER_ID,
                "LOCAL_ROOT",
                "broken.pdf",
                "",
                NodeType.APPLICATION,
                "LOCAL_ROOT",
                10L,
                "application/pdf"));

    app.mocks().previewFails("/preview/pdf/00000000-0000-0000-0000-000000000211/1/");

    final HttpRequest httpRequest =
        HttpRequest.of(
            "GET", "/preview/pdf/00000000-0000-0000-0000-000000000211/", OWNER_COOKIE, null);
    final HttpResponse httpResponse = app.send(httpRequest);

    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(404);
  }

  @Test
  void givenThePreviewServiceFailsThePdfThumbnailApiShouldReturnA404StatusCode() {
    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                "00000000-0000-0000-0000-000000000212",
                OWNER_ID,
                OWNER_ID,
                "LOCAL_ROOT",
                "broken-thumb.pdf",
                "",
                NodeType.APPLICATION,
                "LOCAL_ROOT",
                10L,
                "application/pdf"));

    app.mocks()
        .previewFails("/preview/pdf/00000000-0000-0000-0000-000000000212/1/5x5/thumbnail/");

    final HttpRequest httpRequest =
        HttpRequest.of(
            "GET",
            "/preview/pdf/00000000-0000-0000-0000-000000000212/5x5/thumbnail",
            OWNER_COOKIE,
            null);
    final HttpResponse httpResponse = app.send(httpRequest);

    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(404);
  }

  @Test
  void givenThePreviewServiceFailsTheDocumentPreviewApiShouldReturnA404StatusCode() {
    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                "00000000-0000-0000-0000-000000000213",
                OWNER_ID,
                OWNER_ID,
                "LOCAL_ROOT",
                "broken.xls",
                "",
                NodeType.SPREADSHEET,
                "LOCAL_ROOT",
                10L,
                "application/vnd.ms-excel"));

    app.mocks().previewFails("/preview/document/00000000-0000-0000-0000-000000000213/1/");

    final HttpRequest httpRequest =
        HttpRequest.of(
            "GET", "/preview/document/00000000-0000-0000-0000-000000000213", OWNER_COOKIE, null);
    final HttpResponse httpResponse = app.send(httpRequest);

    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(404);
  }

  @Test
  void givenThePreviewServiceFailsTheDocumentThumbnailApiShouldReturnA404StatusCode() {
    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                "00000000-0000-0000-0000-000000000214",
                OWNER_ID,
                OWNER_ID,
                "LOCAL_ROOT",
                "broken-thumb.xls",
                "",
                NodeType.SPREADSHEET,
                "LOCAL_ROOT",
                10L,
                "application/vnd.ms-excel"));

    app.mocks()
        .previewFails("/preview/document/00000000-0000-0000-0000-000000000214/1/5x5/thumbnail/");

    final HttpRequest httpRequest =
        HttpRequest.of(
            "GET",
            "/preview/document/00000000-0000-0000-0000-000000000214/5x5/thumbnail",
            OWNER_COOKIE,
            null);
    final HttpResponse httpResponse = app.send(httpRequest);

    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(404);
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
    final HttpRequest httpRequest = HttpRequest.of("POST", uri, OWNER_COOKIE, null);

    final HttpResponse httpResponse = app.send(httpRequest);

    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(400);
  }

  // --- checkNodePermissionAndExistence: permission granted but the requested version is absent --
  // Distinct from the not-found/permission-denied tests above (which short-circuit the "&&" on
  // permissionsChecker...has(READ_ONLY), never reaching optFileVersion.isPresent() at all): here
  // permission IS granted, but the specific requested version number doesn't exist.

  @Test
  void givenAPermittedNodeWithANonExistentVersionThePreviewApiShouldReturnA404StatusCode() {
    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                "00000000-0000-0000-0000-000000000215",
                OWNER_ID,
                OWNER_ID,
                "LOCAL_ROOT",
                "onlyOneVersion.pdf",
                "",
                NodeType.APPLICATION,
                "LOCAL_ROOT",
                10L,
                "application/pdf"));

    final HttpRequest httpRequest =
        HttpRequest.of(
            "GET",
            "/preview/pdf/00000000-0000-0000-0000-000000000215/?version=999",
            OWNER_COOKIE,
            null);

    final HttpResponse httpResponse = app.send(httpRequest);

    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(404);
  }
}
