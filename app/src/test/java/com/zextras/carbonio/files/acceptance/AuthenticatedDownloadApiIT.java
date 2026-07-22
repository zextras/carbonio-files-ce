// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.acceptance;

import com.zextras.carbonio.files.FilesStackTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

import com.zextras.carbonio.files.acceptance.seam.FilesTestApp;
import com.zextras.carbonio.files.acceptance.seam.impl.QuarkusFilesTestAppBuilder;
import com.zextras.carbonio.files.api.utilities.entities.PopulatorNode;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.utilities.http.HttpRequest;
import com.zextras.carbonio.files.utilities.http.HttpResponse;
import io.restassured.response.Response;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Task 2.4 of the acceptance coverage-expansion plan: {@code GET /download/{id}[/{version}]} and
 * {@code GET /download/{id}/check}, driven through {@code Mocks#storagesServesBlob}/{@code
 * setMaxDownloadableSizeMb} and seeded via {@code PopulatorNode}.
 *
 * <p><b>Body/Content-Length note:</b> {@code StoragesMockHelper#getBlob(nodeId, version)} always
 * responds with the literal bytes {@code nodeId + version} regardless of the node's DB-recorded
 * size, while {@code BlobService#downloadFile} sets the response's {@code Content-Length} from
 * the DB-recorded {@code FileVersion#getSize()} (never from the actual mocked byte count). To
 * keep the two consistent (a genuine mismatch risks the real-HTTP transport truncating/erroring
 * on a body that disagrees with its own advertised {@code Content-Length}), every node here is
 * seeded with a size EQUAL to {@code (nodeId + version).length()} so the two agree by
 * construction; both versions used below (1 and 2) are single digits, so this size is identical
 * for both and {@code addVersion} (which copies the node's current size unchanged) needs no
 * adjustment.
 *
 * <p><b>FINDING (mirrors the permission-gate-before-not-found pattern seen across the suite):</b>
 * {@code BlobService#checkDownloadFileById} returns {@code Optional.empty()} both when the node
 * does not exist at all (its {@code Optional<Node>} lookup inside {@code PermissionsChecker} is
 * empty, mapping to {@code ACL.NONE}) and when it exists but the requester lacks {@code
 * READ_ONLY}. {@code BlobController#download}/{@code #checkDownload} both funnel that single
 * {@code Optional.empty()} into the identical {@code NoSuchElementException} -> 404 shape, so a
 * genuinely non-existent id and a permission-denied existing id are indistinguishable over HTTP.
 * Both scenarios are asserted below with their real, identical 404 shape.
 *
 * <p><b>FINDING (genuine transport divergence, not fixed here):</b> {@code BlobController#download}
 * writes its response in (at least) two separate steps — the headers-only {@code
 * DefaultHttpResponse} via {@code context.write(...)}, then the body via {@code
 * NettyBufferWriter}'s own separate {@code context.writeAndFlush(ByteBuf)} calls per chunk — never
 * as a single {@code DefaultFullHttpResponse}. {@code TestUtils#sendRequest} (embedded transport)
 * only drains ONE outbound Netty message via {@code EmbeddedChannel#readOutbound()}, so on the
 * embedded transport {@code httpResponse.getBodyPayload()} for this route is ALWAYS {@code null}
 * (status and headers ARE captured correctly — only the streamed body is missed). This is a
 * PRE-EXISTING seam gap, not introduced here: no existing acceptance test (including {@code
 * DownloadMultipleApiIT}, {@code PreviewApiIT}, {@code ThumbnailApiIT}, all of which stream a body
 * the same way) asserts body bytes for this exact reason. On the real-HTTP transport ({@code
 * -Dfiles.test.transport=http}), {@code RealHttpFilesTestApp} uses {@code java.net.http.HttpClient}
 * with {@code BodyHandlers.ofString(...)}, which correctly drains the full body over the socket
 * regardless of framing — so the identical test body DOES observe the real bytes there. Per the
 * "don't silently edit the shared seam" guardrail, {@code TestUtils}/{@code GuiceNettyFilesTestApp}
 * are NOT modified to add multi-frame draining; instead, the body-match assertions below are
 * conditional on the body being observable (deterministically skipped on embedded, deterministically
 * checked on real-HTTP), and every test additionally verifies the download via {@code
 * Mocks#verifyStoragesDownloaded}, which is transport-invariant proof that storages was actually
 * asked for the correct node/version.
 */
@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class AuthenticatedDownloadApiIT {

  static FilesTestApp app;

  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OTHER_USER_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String REQUESTER_COOKIE = "ZM_AUTH_TOKEN=fake-token";

  @BeforeAll
  static void init() {
    app =
        QuarkusFilesTestAppBuilder.aFilesTestApp()
            .withDatabase()
            .withServiceDiscover()
            .withUserManagement(
                Map.of(
                    "fake-token", REQUESTER_ID,
                    "fake-token-b", OTHER_USER_ID))
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

  private static String blobBytesFor(String nodeId, int version) {
    return nodeId + version;
  }

  private static long sizeFor(String nodeId, int version) {
    return blobBytesFor(nodeId, version).getBytes(StandardCharsets.UTF_8).length;
  }

  private HttpResponse download(String nodeId, Integer version, String cookie) {
    String endpoint = version == null ? "/download/" + nodeId : "/download/" + nodeId + "/" + version;
    return app.send(HttpRequest.of("GET", endpoint, cookie, null));
  }

  private HttpResponse checkDownload(String nodeId, String cookie) {
    return app.send(HttpRequest.of("GET", "/download/" + nodeId + "/check", cookie, null));
  }

  private String headerValue(HttpResponse response, String name) {
    return response.getHeaders().stream()
        .filter(header -> header.getKey().equalsIgnoreCase(name))
        .map(Map.Entry::getValue)
        .findFirst()
        .orElse(null);
  }

  /**
   * Asserts the streamed download body matches, but only when it was actually observable — see
   * the class-level transport-divergence FINDING: the embedded transport deterministically never
   * captures this route's body ({@code null}), the real-HTTP transport deterministically does.
   */
  private void assertBodyMatchesWhenObservable(HttpResponse response, String expected) {
    if (response.getBodyPayload() != null) {
      Assertions.assertThat(response.getBodyPayload()).isEqualTo(expected);
    }
  }

  @Test
  void givenAnExistingFileTheLatestDownloadShouldReturn200WithHeadersAndMatchingBody()
      throws Exception {
    // Given — a single version (v1)
    String nodeId = "10000000-0000-0000-0000-000000000001";
    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                nodeId,
                REQUESTER_ID,
                REQUESTER_ID,
                "LOCAL_ROOT",
                "fake.txt",
                "",
                NodeType.TEXT,
                "LOCAL_ROOT",
                sizeFor(nodeId, 1),
                "text/plain"));
    app.mocks().storagesServesBlob(nodeId, 1);

    // When
    HttpResponse httpResponse = download(nodeId, null, REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Assertions.assertThat(headerValue(httpResponse, "content-disposition"))
        .isEqualTo(
            "attachment; filename*=UTF-8''" + URLEncoder.encode("fake.txt", StandardCharsets.UTF_8));
    Assertions.assertThat(headerValue(httpResponse, "content-length"))
        .isEqualTo(String.valueOf(sizeFor(nodeId, 1)));
    assertBodyMatchesWhenObservable(httpResponse, blobBytesFor(nodeId, 1));

    app.mocks().verifyStoragesDownloaded(nodeId, 1);
  }

  @Test
  void givenAVersionSuffixUrlTheDownloadShouldServeThatSpecificVersion() throws Exception {
    // Given — two versions (v1, v2); the node's currently-current version is 2, but the URL
    // explicitly asks for v1
    String nodeId = "10000000-0000-0000-0000-000000000002";
    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                nodeId,
                REQUESTER_ID,
                REQUESTER_ID,
                "LOCAL_ROOT",
                "fake.txt",
                "",
                NodeType.TEXT,
                "LOCAL_ROOT",
                sizeFor(nodeId, 1),
                "text/plain"))
        .addVersion(nodeId);
    app.mocks().storagesServesBlob(nodeId, 1);
    app.mocks().storagesServesBlob(nodeId, 2);

    // When — explicitly request the OLD version, not the current one
    HttpResponse httpResponse = download(nodeId, 1, REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    assertBodyMatchesWhenObservable(httpResponse, blobBytesFor(nodeId, 1));
    app.mocks().verifyStoragesDownloaded(nodeId, 1);
  }

  // -------------------------------------------------------- Content-Length vs chunked (decision B)

  /**
   * Locks decision B: the single-file download is sent fixed-length (a {@code Content-Length}
   * computed from the DB-recorded size, set before the first byte — see {@code TransferStreaming
   * #streamBlob}), never as {@code Transfer-Encoding: chunked}. Driven directly with RestAssured
   * (rather than {@code app.send}) so the exact wire header set can be asserted precisely; the node
   * is seeded with the same {@code PopulatorNode} helper the rest of this class uses.
   */
  @Test
  void givenAnExistingFileTheDownloadResponseShouldBeFixedLengthNotChunked() {
    String nodeId = "10000000-0000-0000-0000-000000000007";
    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                nodeId,
                REQUESTER_ID,
                REQUESTER_ID,
                "LOCAL_ROOT",
                "fake.txt",
                "",
                NodeType.TEXT,
                "LOCAL_ROOT",
                sizeFor(nodeId, 1),
                "text/plain"));
    app.mocks().storagesServesBlob(nodeId, 1);

    Response response =
        io.restassured.RestAssured.given()
            .cookie("ZM_AUTH_TOKEN", "fake-token")
            .when()
            .get("/download/" + nodeId);

    response.then().statusCode(200);
    Assertions.assertThat(response.getHeader("Content-Length"))
        .as("single download must advertise a fixed Content-Length")
        .isEqualTo(String.valueOf(sizeFor(nodeId, 1)));
    Assertions.assertThat(response.getHeader("Transfer-Encoding"))
        .as("single download must NOT be chunked")
        .isNull();
  }

  @Test
  void givenNoPermissionOnTheNodeDownloadShouldReturn404() {
    // Given — node owned by OTHER_USER_ID, never shared with the requester
    String nodeId = "10000000-0000-0000-0000-000000000003";
    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                nodeId,
                OTHER_USER_ID,
                OTHER_USER_ID,
                "LOCAL_ROOT",
                "notMine.txt",
                "",
                NodeType.TEXT,
                "LOCAL_ROOT",
                sizeFor(nodeId, 1),
                "text/plain"));

    // When
    HttpResponse httpResponse = download(nodeId, null, REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(404);
    Assertions.assertThat(httpResponse.getBodyPayload()).isEqualTo("404 Not Found");
    app.mocks().verifyStoragesNeverDownloaded();
  }

  @Test
  void givenANonExistentNodeDownloadShouldReturnTheSame404AsNoPermission() {
    // See class-level FINDING: the permission gate maps a non-existent id to the SAME
    // Optional.empty() -> 404 shape as an existing-but-forbidden node.
    String nonExistentId = "10000000-0000-0000-0000-00000000ffff";

    // When
    HttpResponse httpResponse = download(nonExistentId, null, REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(404);
    Assertions.assertThat(httpResponse.getBodyPayload()).isEqualTo("404 Not Found");
    app.mocks().verifyStoragesNeverDownloaded();
  }

  @Test
  void givenTheNodeSizeOverTheConfiguredCapDownloadShouldReturn413() {
    // Given — a 0MB cap; any node with a non-zero size exceeds it
    String nodeId = "10000000-0000-0000-0000-000000000004";
    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                nodeId,
                REQUESTER_ID,
                REQUESTER_ID,
                "LOCAL_ROOT",
                "big.bin",
                "",
                NodeType.TEXT,
                "LOCAL_ROOT",
                sizeFor(nodeId, 1),
                "text/plain"));
    app.mocks().setMaxDownloadableSizeMb(0);

    // When
    HttpResponse httpResponse = download(nodeId, null, REQUESTER_COOKIE);

    // Then — FileSizeException groups with the generic-body 413 branch in ExceptionsHandler (the
    // descriptive message is discarded, same quirk as the upload-side size cap)
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(413);
    Assertions.assertThat(httpResponse.getBodyPayload()).isEqualTo("413 Request Entity Too Large");
    app.mocks().verifyStoragesNeverDownloaded();
  }

  @Test
  void givenADownloadableNodeCheckDownloadShouldReturn204() {
    // Given
    String nodeId = "10000000-0000-0000-0000-000000000005";
    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                nodeId,
                REQUESTER_ID,
                REQUESTER_ID,
                "LOCAL_ROOT",
                "fake.txt",
                "",
                NodeType.TEXT,
                "LOCAL_ROOT",
                sizeFor(nodeId, 1),
                "text/plain"));

    // When
    HttpResponse httpResponse = checkDownload(nodeId, REQUESTER_COOKIE);

    // Then — /check never touches storages at all (no fileStore call in checkDownloadFileById)
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(204);
    app.mocks().verifyStoragesNeverDownloaded();
  }

  @Test
  void givenNoPermissionCheckDownloadShouldReturn404() {
    // Given — node owned by OTHER_USER_ID, never shared with the requester
    String nodeId = "10000000-0000-0000-0000-000000000006";
    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                nodeId,
                OTHER_USER_ID,
                OTHER_USER_ID,
                "LOCAL_ROOT",
                "notMine.txt",
                "",
                NodeType.TEXT,
                "LOCAL_ROOT",
                sizeFor(nodeId, 1),
                "text/plain"));

    // When
    HttpResponse httpResponse = checkDownload(nodeId, REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(404);
    Assertions.assertThat(httpResponse.getBodyPayload()).isEqualTo("404 Not Found");
  }

  @Test
  void givenANonExistentNodeCheckDownloadShouldReturn404() {
    // Given — same permission-gate merge as the plain download route
    String nonExistentId = "10000000-0000-0000-0000-00000000ffff";

    // When
    HttpResponse httpResponse = checkDownload(nonExistentId, REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(404);
    Assertions.assertThat(httpResponse.getBodyPayload()).isEqualTo("404 Not Found");
  }
}
