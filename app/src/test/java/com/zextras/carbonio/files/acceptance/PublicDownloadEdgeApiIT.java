// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.acceptance;

import com.zextras.carbonio.files.FilesStackTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zextras.carbonio.files.acceptance.seam.FilesTestApp;
import com.zextras.carbonio.files.acceptance.seam.impl.QuarkusFilesTestAppBuilder;
import com.zextras.carbonio.files.api.utilities.entities.PopulatorNode;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.utilities.http.HttpRequest;
import com.zextras.carbonio.files.utilities.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Edge-branch coverage for {@code PublicBlobController} that {@code PublicDownloadApiIT}/{@code
 * PublicDownloadMultipleApiIT}/{@code DownloadByPublicLinkApiIT} do not exercise. Those three
 * cover: {@code downloadByNodeId} (plain, non-check, node-id download), {@code
 * downloadPublicMultiple} (form-encoded, non-check, zip download) and {@code downloadByPublicLink}
 * (including its 307 redirect for an access-code-protected link). This class targets the two
 * "check" variants (which those files do not cover at all) plus one routing-level regex quirk:
 *
 * <ul>
 *   <li>{@code checkDownloadPublicFile} ({@code GET /public/download/{id}/check?node_link_id=...})
 *       -&gt; 204 on success, 404 on any not-accessible condition.
 *   <li>{@code checkDownloadPublicMultiple} ({@code POST /public/download-multiple/check}) -&gt;
 *       204/404/400. Note the body-encoding asymmetry versus the non-check {@code
 *       downloadPublicMultiple}: the non-check endpoint reads a form-urlencoded body, but the
 *       check endpoint reads a raw JSON body (see {@code
 *       PublicBlobController#checkDownloadPublicMultiple}).
 *   <li>Query-string parameter ORDER on {@code DOWNLOAD_PUBLIC_FILE_CHECK}: the endpoint's regex
 *       hard-codes {@code node_link_id} immediately after {@code ?}, with {@code access_code} (if
 *       present) only allowed to follow it. Reversing the order makes the regex fail to match at
 *       the {@code HttpRoutingHandler} level (before any handler runs at all) — this is a routing
 *       404, not a {@code PublicBlobController}/{@code ExceptionsHandler} 404, and is a distinct,
 *       currently-undocumented behaviour.
 * </ul>
 *
 * <p>The 307 redirect on {@code /link/{id}} for an access-code-protected link is already covered
 * by {@code DownloadByPublicLinkApiIT#givenAUserWithOrWithoutCookieAnExistingFileAndAnExistingPublicLinkAssociatedWithAccessCodeTheDownloadByPublicLinkShouldRedirect}
 * — not duplicated here.
 */
@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
public class PublicDownloadEdgeApiIT {

  static FilesTestApp app;
  static ObjectMapper objectMapper = new ObjectMapper();

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

  // --- checkDownloadPublicFile (GET /public/download/{id}/check?node_link_id=...) ------------

  @Test
  void givenAnAccessibleNodeTheCheckDownloadPublicFileApiShouldReturnA204StatusCode() {
    String userId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    String fileId = "00000000-0000-0000-0000-000000000301";
    String linkPublicId = "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234ab";

    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                fileId,
                userId,
                userId,
                "LOCAL_ROOT",
                "file.txt",
                "",
                NodeType.TEXT,
                "LOCAL_ROOT",
                10L,
                "text/plain"))
        .addLink(
            "94103c01-e701-4f3d-9dc9-54b79064ad76",
            fileId,
            linkPublicId,
            Optional.empty(),
            Optional.empty(),
            Optional.empty());

    final String url = "/public/download/" + fileId + "/check?node_link_id=" + linkPublicId;
    final HttpResponse httpResponse = app.send(HttpRequest.of("GET", url, null, null));

    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(204);
  }

  @Test
  void givenANotExistingLinkTheCheckDownloadPublicFileApiShouldReturnA404StatusCode() {
    final String url =
        "/public/download/00000000-0000-0000-0000-000000000302/check?node_link_id=nonexistentlink0000000000000000000000000000000ab";
    final HttpResponse httpResponse = app.send(HttpRequest.of("GET", url, null, null));

    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(404);
  }

  @Test
  void givenAProtectedLinkWithoutTheAccessCodeTheCheckDownloadPublicFileApiShouldReturnA404StatusCode() {
    String userId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    String fileId = "00000000-0000-0000-0000-000000000303";
    String linkPublicId = "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234ab";

    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                fileId,
                userId,
                userId,
                "LOCAL_ROOT",
                "protected.txt",
                "",
                NodeType.TEXT,
                "LOCAL_ROOT",
                10L,
                "text/plain"))
        .addLink(
            "94103c01-e701-4f3d-9dc9-54b79064ad77",
            fileId,
            linkPublicId,
            Optional.empty(),
            Optional.empty(),
            Optional.of("secretcode123"));

    final String url = "/public/download/" + fileId + "/check?node_link_id=" + linkPublicId;
    final HttpResponse httpResponse = app.send(HttpRequest.of("GET", url, null, null));

    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(404);
  }

  // --- checkDownloadPublicMultiple (POST /public/download-multiple/check, JSON body) ----------

  @Test
  void givenAccessibleNodesTheCheckDownloadPublicMultipleApiShouldReturnA204StatusCode()
      throws Exception {
    String userId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    String folderId = "11111111-1111-1111-1111-111111110401";
    String fileId = "00000000-0000-0000-0000-000000000401";
    String linkPublicId = "abcd1234-abcd-1234-abcd-1234abcd1401";

    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                folderId,
                userId,
                userId,
                "LOCAL_ROOT",
                "check-folder",
                "",
                NodeType.FOLDER,
                "LOCAL_ROOT",
                0L,
                null))
        .addNode(
            new PopulatorNode(
                fileId,
                userId,
                userId,
                folderId,
                "file.txt",
                "",
                NodeType.TEXT,
                "LOCAL_ROOT," + folderId,
                10L,
                "text/plain"))
        .addLink(
            "94103c01-e701-4f3d-9dc9-54b79064ad78",
            folderId,
            linkPublicId,
            Optional.empty(),
            Optional.empty(),
            Optional.empty());

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("nodeIds", List.of(fileId));
    body.put("nodeLinkId", linkPublicId);
    String requestBody = objectMapper.writeValueAsString(body);

    List<Map.Entry<String, String>> headers =
        List.of(Map.entry("Content-Type", "application/json"));

    final HttpRequest httpRequest =
        HttpRequest.of("POST", "/public/download-multiple/check", null, headers, requestBody);
    final HttpResponse httpResponse = app.sendForm(httpRequest);

    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(204);
  }

  @Test
  void givenANotExistingLinkTheCheckDownloadPublicMultipleApiShouldReturnA404StatusCode()
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("nodeIds", List.of("00000000-0000-0000-0000-000000000402"));
    body.put("nodeLinkId", "nonexistentlink0000000000000000000000000000000ab");
    String requestBody = objectMapper.writeValueAsString(body);

    List<Map.Entry<String, String>> headers =
        List.of(Map.entry("Content-Type", "application/json"));

    final HttpRequest httpRequest =
        HttpRequest.of("POST", "/public/download-multiple/check", null, headers, requestBody);
    final HttpResponse httpResponse = app.sendForm(httpRequest);

    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(404);
  }

  @Test
  void givenAMalformedJsonBodyTheCheckDownloadPublicMultipleApiShouldReturnA500StatusCode() {
    // Divergence from the naive expectation of 400 (documented as a finding, NOT fixed — Phase 1
    // forbids src/main changes): PublicBlobController#checkDownloadPublicMultiple wraps the
    // JsonProcessingException in `new IllegalArgumentException("Invalid JSON body", e)` and fires
    // THAT. But ExceptionsHandler unconditionally unwraps exactly one getCause() level
    // (`cause = cause.getCause() == null ? cause : cause.getCause()`), so the exception it
    // actually switches on is the ORIGINAL JsonProcessingException, not the IllegalArgumentException
    // — which matches ExceptionsHandler's `JsonProcessingException -> 500` branch, not its
    // `IllegalArgumentException -> 400` branch. The same wrapping pattern in the sibling
    // downloadPublicMultiple (non-check) method has the identical bug, just untested elsewhere.
    List<Map.Entry<String, String>> headers =
        List.of(Map.entry("Content-Type", "application/json"));

    final HttpRequest httpRequest =
        HttpRequest.of(
            "POST", "/public/download-multiple/check", null, headers, "{not-valid-json");
    final HttpResponse httpResponse = app.sendForm(httpRequest);

    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(500);
  }

  // --- Query-string parameter ORDER edge on DOWNLOAD_PUBLIC_FILE_CHECK ------------------------

  @Test
  void givenNodeLinkIdBeforeAccessCodeTheCheckDownloadPublicFileApiShouldReturnA204StatusCode() {
    String userId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    String fileId = "00000000-0000-0000-0000-000000000501";
    String linkPublicId = "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234ab";
    String accessCode = "secretcode123";

    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                fileId,
                userId,
                userId,
                "LOCAL_ROOT",
                "ordered.txt",
                "",
                NodeType.TEXT,
                "LOCAL_ROOT",
                10L,
                "text/plain"))
        .addLink(
            "94103c01-e701-4f3d-9dc9-54b79064ad79",
            fileId,
            linkPublicId,
            Optional.empty(),
            Optional.empty(),
            Optional.of(accessCode));

    final String correctOrderUrl =
        "/public/download/"
            + fileId
            + "/check?node_link_id="
            + linkPublicId
            + "&access_code="
            + accessCode;
    final HttpResponse httpResponse = app.send(HttpRequest.of("GET", correctOrderUrl, null, null));

    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(204);
  }

  @Test
  void givenAccessCodeBeforeNodeLinkIdTheCheckDownloadPublicFileApiShouldReturnA404StatusCode() {
    String userId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    String fileId = "00000000-0000-0000-0000-000000000502";
    String linkPublicId = "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234ab";
    String accessCode = "secretcode123";

    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                fileId,
                userId,
                userId,
                "LOCAL_ROOT",
                "misordered.txt",
                "",
                NodeType.TEXT,
                "LOCAL_ROOT",
                10L,
                "text/plain"))
        .addLink(
            "94103c01-e701-4f3d-9dc9-54b79064ad80",
            fileId,
            linkPublicId,
            Optional.empty(),
            Optional.empty(),
            Optional.of(accessCode));

    // Same node/link/access-code as the previous test, only the query-parameter ORDER differs:
    // access_code first, node_link_id second. DOWNLOAD_PUBLIC_FILE_CHECK's regex hard-codes
    // node_link_id immediately after '?', so this URI matches NO route in HttpRoutingHandler and
    // falls straight to its raw NOT_FOUND fallback -- PublicBlobController is never invoked.
    final String wrongOrderUrl =
        "/public/download/"
            + fileId
            + "/check?access_code="
            + accessCode
            + "&node_link_id="
            + linkPublicId;
    final HttpResponse httpResponse = app.send(HttpRequest.of("GET", wrongOrderUrl, null, null));

    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(404);
  }
}
