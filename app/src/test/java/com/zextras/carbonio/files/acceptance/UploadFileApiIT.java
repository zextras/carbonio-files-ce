// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.acceptance;

import com.zextras.carbonio.files.FilesStackTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.acceptance.seam.FilesTestApp;
import com.zextras.carbonio.files.acceptance.seam.impl.QuarkusFilesTestAppBuilder;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.api.utilities.entities.SimplePopulatorFolder;
import com.zextras.carbonio.files.api.utilities.entities.SimplePopulatorTextFile;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.utilities.http.HttpRequest;
import com.zextras.carbonio.files.utilities.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Task 2.1 of the acceptance coverage-expansion plan: {@code POST /upload}, the plain new-node
 * upload route. Driven exclusively through {@link FilesTestApp#upload}/{@code HttpRequest.ofUpload}
 * (the Wave-0 binary-upload seam) and {@code Mocks#storagesUploadSucceeds}/{@code
 * storagesUploadFails}/{@code storagesVerifyMissing}/{@code setMaxUploadableSizeMb}.
 *
 * <p><b>FINDING — one scenario is untestable with the current seam:</b> the plan's "missing
 * {@code Content-Length} header -> 500 (NumberFormatException)" row cannot be produced through
 * {@code FilesTestApp#upload} on EITHER transport. {@code TestUtils#sendUpload} always calls
 * {@code httpHeaders.set(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(body.length))} — this
 * REPLACES any caller-supplied value, so a request can never arrive at {@code BlobController}
 * without (or with a wrong) {@code Content-Length}. On the real-HTTP transport it is even less
 * reachable: {@code java.net.http.HttpClient} treats {@code Content-Length} as a JDK-restricted
 * header it computes itself from the {@code BodyPublisher} and will not let a caller override or
 * omit (see {@code RealHttpFilesTestApp#upload}'s own comment to this effect). Both behaviours are
 * DELIBERATE Wave-0 design choices (see {@code HttpRequest#ofUpload}'s javadoc: "the Content-Length
 * sent on the wire is always computed from this array's length ... never taken from headers"), not
 * oversights — reproducing a genuinely missing/malformed header would require editing the shared
 * seam (`TestUtils.sendUpload`/`RealHttpFilesTestApp.upload`), which is out of scope for this task
 * per the "don't silently edit shared seam" guardrail. This scenario is therefore DELIBERATELY
 * OMITTED here rather than faked with a test that would silently degrade to the happy path.
 *
 * <p><b>SECOND FINDING — RESOLVED:</b> {@code TestUtils#sendUpload} used to throw a raw {@code
 * java.nio.channels.ClosedChannelException} on the EMBEDDED transport whenever {@code
 * BlobController} responded and closed the channel synchronously while handling the request HEAD
 * (before any {@code HttpContent} is read — the size-over-limit check and the {@code Filename}
 * validation both run this early), because {@code sendUpload} unconditionally issued a second
 * {@code EmbeddedChannel#writeInbound} call (the {@code LastHttpContent}) even when the first call
 * had already closed the channel. {@code TestUtils#sendUpload} now guards that second write with
 * {@code nettyChannel.isOpen()}, so the response the handler already produced during head
 * processing is read out normally on both transports. The scenarios below now run (and pass) on
 * both the embedded and {@code -Dfiles.test.transport=http} transports.
 */
@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class UploadFileApiIT {

  static FilesTestApp app;
  static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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
    app.mocks().setMaxUploadableSizeMb(null);
  }

  @AfterAll
  static void cleanUpAll() {
    app.close();
  }

  private static String toBase64(String value) {
    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private HttpResponse upload(String parentId, String filenameB64, byte[] body, String cookie) {
    List<Map.Entry<String, String>> headers = new ArrayList<>();
    headers.add(Map.entry("Filename", filenameB64));
    if (parentId != null) {
      headers.add(Map.entry("ParentId", parentId));
    }
    HttpRequest httpRequest = HttpRequest.ofUpload("POST", "/upload", cookie, headers, body);
    return app.upload(httpRequest);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> getNode(String nodeId, String cookie) {
    // NOTE: the GraphQL Node interface splits the stored filename into a base `name` (extension
    // stripped, see Node#getName()) and a separate `extension` field -- there is no combined
    // "full name" field on File/Folder. Both must be queried and reassembled to check a filename.
    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("getNode")
            .withString("node_id", nodeId)
            .withWantedResultFormat("{ id name owner { id } ... on File { extension } }")
            .build();
    HttpResponse httpResponse = app.send(HttpRequest.of("POST", "/graphql/", cookie, bodyPayload));
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    return TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "getNode");
  }

  @SuppressWarnings("unchecked")
  private List<String> childNodeIds(String folderId) {
    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("findNodes")
            .withString("folder_id", folderId)
            .withEnumLiteral("sort", "NAME_ASC")
            .withInteger("limit", 50)
            .withWantedResultFormat("{ nodes { id }, page_token }")
            .build();
    HttpResponse httpResponse =
        app.send(HttpRequest.of("POST", "/graphql/", REQUESTER_COOKIE, bodyPayload));
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Map<String, Object> page = TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "findNodes");
    List<Map<String, Object>> nodes = (List<Map<String, Object>>) page.get("nodes");
    return nodes.stream().map(node -> (String) node.get("id")).toList();
  }

  /**
   * Reads the current value of the Prometheus {@code files_upload_total{...,uri="/upload",...}}
   * counter off {@code GET /metrics} (an unauthenticated route, see {@code MetricsApiIT}). Used to
   * assert a delta rather than an absolute value so the check is order-independent across tests in
   * this class.
   */
  private double uploadCounterValue() {
    HttpResponse httpResponse = app.send(HttpRequest.of("GET", "/metrics", null, null));
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    for (String line : httpResponse.getBodyPayload().split("\n")) {
      if (line.startsWith("files_upload_total") && line.contains("uri=\"/upload\"")) {
        String[] tokens = line.trim().split("\\s+");
        return Double.parseDouble(tokens[tokens.length - 1]);
      }
    }
    return 0.0;
  }

  @Test
  void givenAValidUploadIntoLocalRootItShouldCreateTheNodeAndOmitVersionFromTheResponse()
      throws Exception {
    // Given
    app.mocks().storagesUploadSucceeds();
    double counterBefore = uploadCounterValue();

    // When
    HttpResponse httpResponse =
        upload(null, toBase64("hello.txt"), "hello world".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Map<String, Object> json = OBJECT_MAPPER.readValue(httpResponse.getBodyPayload(), Map.class);
    Assertions.assertThat(json).containsKey("nodeId");
    String nodeId = (String) json.get("nodeId");
    Assertions.assertThat(nodeId).isNotBlank();
    // KNOWN QUIRK (Wave 0): UploadVersionResponse#setVersion(int) only stores values > 1, so a
    // fresh upload's version (1) is never set -> the field is entirely absent from the JSON.
    Assertions.assertThat(json).doesNotContainKey("version");

    Assertions.assertThat(app.backdoor().nodeExists(nodeId)).isTrue();

    Map<String, Object> node = getNode(nodeId, REQUESTER_COOKIE);
    Assertions.assertThat(node).containsEntry("name", "hello").containsEntry("extension", "txt");
    Assertions.assertThat(((Map<String, Object>) node.get("owner"))).containsEntry("id", REQUESTER_ID);

    Assertions.assertThat(uploadCounterValue()).isEqualTo(counterBefore + 1.0);
  }

  @Test
  void givenAnUploadIntoASharedFolderItShouldInheritTheFolderOwner() throws Exception {
    // Given — folder owned by OTHER_USER_ID, shared with the requester with write access
    String folderId = "10000000-0000-0000-0000-000000000001";
    app.backdoor()
        .populator()
        .addNode(new SimplePopulatorFolder(folderId, OTHER_USER_ID, "sharedParent"))
        .addShare(folderId, REQUESTER_ID, ACL.SharePermission.READ_AND_WRITE);
    app.mocks().storagesUploadSucceeds();

    // When
    HttpResponse httpResponse =
        upload(folderId, toBase64("shared.txt"), "content".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Map<String, Object> json = OBJECT_MAPPER.readValue(httpResponse.getBodyPayload(), Map.class);
    String nodeId = (String) json.get("nodeId");

    Map<String, Object> node = getNode(nodeId, REQUESTER_COOKIE);
    Assertions.assertThat(((Map<String, Object>) node.get("owner"))).containsEntry("id", OTHER_USER_ID);
  }

  @Test
  void givenNoWritePermissionOnTheParentUploadShouldReturn404AndCreateNoOrphanNode() {
    // Given — folder owned by OTHER_USER_ID, never shared with the requester
    String folderId = "10000000-0000-0000-0000-000000000001";
    app.backdoor().populator().addNode(new SimplePopulatorFolder(folderId, OTHER_USER_ID, "privateParent"));
    List<String> childrenBefore = childNodeIds(folderId);

    // When
    HttpResponse httpResponse =
        upload(folderId, toBase64("nope.txt"), "content".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);

    // Then — BlobService#uploadFile returns Optional.empty() -> NoSuchElementException -> 404
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(404);
    Assertions.assertThat(httpResponse.getBodyPayload()).isEqualTo("404 Not Found");
    Assertions.assertThat(childNodeIds(folderId)).isEqualTo(childrenBefore);
  }

  @Test
  void givenABodyOverTheConfiguredSizeCapUploadShouldReturn413() {
    // Given — a 0MB cap + a tiny (few-byte) body: BlobController rejects based on Content-Length
    // alone, before reading any body bytes, so the server may respond+close the connection while
    // the client is still transmitting; keeping the body tiny (instead of multi-MB) keeps the
    // client's write effectively atomic and avoids a genuine client/server TCP race on both
    // transports (see this class's SECOND FINDING javadoc for the now-fixed embedded-transport
    // seam bug this used to trigger).
    app.mocks().setMaxUploadableSizeMb(0);
    byte[] oversizedBody = "over the 0MB cap".getBytes(StandardCharsets.UTF_8);

    // When
    HttpResponse httpResponse = upload(null, toBase64("big.bin"), oversizedBody, REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(413);
    Assertions.assertThat(httpResponse.getBodyPayload()).isEqualTo("413 Request Entity Too Large");
  }

  @Test
  void givenANonBase64FilenameUploadShouldReturn400() {
    // When — "!" is not part of the base64 alphabet, so Base64.isBase64(...) is false
    HttpResponse httpResponse =
        upload(null, "not-!-valid-!-base64", "content".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(400);
    Assertions.assertThat(httpResponse.getBodyPayload()).isEqualTo("400 Bad Request");
  }

  @Test
  void givenAnEmptyFilenameUploadShouldReturn400() {
    // When — base64 of the empty string decodes to an empty (blank-after-trim) filename
    HttpResponse httpResponse = upload(null, toBase64(""), "content".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(400);
    Assertions.assertThat(httpResponse.getBodyPayload()).isEqualTo("400 Bad Request");
  }

  @Test
  void givenAFilenameLongerThan1024CharactersUploadShouldReturn400() {
    // Given
    String tooLongName = "a".repeat(1021) + ".txt"; // 1025 chars decoded

    // When
    HttpResponse httpResponse =
        upload(null, toBase64(tooLongName), "content".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(400);
    Assertions.assertThat(httpResponse.getBodyPayload()).isEqualTo("400 Bad Request");
  }

  @Test
  void givenStoragesUploadFailsUploadShouldReturn500AndCreateNoOrphanNode() {
    // Given
    app.mocks().storagesUploadFails();
    List<String> childrenBefore = childNodeIds("LOCAL_ROOT");

    // When
    HttpResponse httpResponse =
        upload(null, toBase64("willfail.txt"), "content".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);

    // Then — DependencyException falls into ExceptionsHandler's generic else-branch -> 500;
    // the node row created before the storages call is deleted (BlobService#uploadFile's
    // Try#getOrElseThrow rollback).
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(500);
    Assertions.assertThat(httpResponse.getBodyPayload()).isEqualTo("500 Internal Server Error");
    Assertions.assertThat(childNodeIds("LOCAL_ROOT")).isEqualTo(childrenBefore);
  }

  @Test
  void givenStoragesVerifyExistsFailsUploadShouldReturn500AndCreateNoOrphanNode() {
    // Given
    app.mocks().storagesVerifyMissing();
    List<String> childrenBefore = childNodeIds("LOCAL_ROOT");

    // When
    HttpResponse httpResponse =
        upload(null, toBase64("willfail2.txt"), "content".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(500);
    Assertions.assertThat(httpResponse.getBodyPayload()).isEqualTo("500 Internal Server Error");
    Assertions.assertThat(childNodeIds("LOCAL_ROOT")).isEqualTo(childrenBefore);
  }

  @Test
  void givenANameCollisionUploadShouldDedupTheName() throws Exception {
    // Given
    String existingId = "10000000-0000-0000-0000-000000000002";
    app.backdoor().populator().addNode(new SimplePopulatorTextFile(existingId, REQUESTER_ID, "sameName.txt"));
    app.mocks().storagesUploadSucceeds();

    // When
    HttpResponse httpResponse =
        upload(null, toBase64("sameName.txt"), "content".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Map<String, Object> json = OBJECT_MAPPER.readValue(httpResponse.getBodyPayload(), Map.class);
    String nodeId = (String) json.get("nodeId");

    Map<String, Object> node = getNode(nodeId, REQUESTER_COOKIE);
    Assertions.assertThat(node).containsEntry("name", "sameName (1)").containsEntry("extension", "txt");
  }
}
