// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zextras.carbonio.files.acceptance.seam.FilesTestApp;
import com.zextras.carbonio.files.acceptance.seam.impl.GuiceNettyFilesTestAppBuilder;
import com.zextras.carbonio.files.api.utilities.entities.SimplePopulatorTextFile;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.utilities.http.HttpRequest;
import com.zextras.carbonio.files.utilities.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Task 2.2 of the acceptance coverage-expansion plan: {@code POST /upload-version}, driven through
 * {@link FilesTestApp#upload}/{@code HttpRequest.ofUpload} and {@code
 * Mocks#setMaxNumberOfVersions}/{@code storagesBulkDeleteSucceeds}/{@code
 * storagesBulkDeleteReturnsNullResponse}.
 *
 * <p><b>Seam clarification (do not use the builder knob here):</b> {@code
 * BlobService#uploadFileVersion}'s 405 version-cap re-reads {@code
 * FilesConfig#getMaxNumberOfFileVersion()} on EVERY call, which is exactly what {@code
 * Mocks#setMaxNumberOfVersions} (a post-build {@code MockFilesConfig} override) drives — see that
 * method's own javadoc. The builder's {@code withMaxNumberOfVersions(int)} instead targets a
 * DIFFERENT, pre-build-only cap ({@code NodeDataFetcher}'s keep-cap for {@code keepVersions}/{@code
 * cloneVersion}, read once from Service-Discover at construction) that this class's mutation never
 * touches, so it is intentionally unused here.
 *
 * <p><b>FINDING (mirrors {@code CreateFolderApiIT}'s permission-gate finding):</b> {@code
 * uploadFileVersion}'s permission check ({@code
 * permissionsChecker.getPermissions(nodeId,...).has(READ_AND_WRITE)}) runs BEFORE {@code
 * nodeRepository.getNodeForUpdate(nodeId)}. Since {@code PermissionsChecker#getPermissions} maps
 * ANY non-existent node id to {@code ACL.NONE} (its {@code Optional<Node>} lookup is empty), a
 * genuinely non-existent {@code NodeId} fails the SAME early permission gate as an existing node
 * the requester cannot write to — both return the identical 404 via the identical early-return
 * branch. The deeper {@code getNodeForUpdate(...).orElseThrow(() -> new
 * NoSuchElementException("Node not found: " + nodeId))} line is therefore unreachable via any
 * legitimate HTTP request (the permission gate cannot pass for an id with no backing row) — dead
 * code from a black-box perspective. Both scenarios are asserted below with their real, identical
 * 404 shape.
 *
 * <p><b>SECOND FINDING (shared with {@code UploadFileApiIT} -- see that class's javadoc for full
 * detail):</b> {@code TestUtils#sendUpload}'s second {@code writeInbound} call throws {@code
 * ClosedChannelException} on the EMBEDDED transport whenever {@code BlobController} responds
 * synchronously while handling the request head, before any content is read -- which is exactly
 * when the size-over-limit check fires. Confirmed to pass cleanly under {@code
 * -Dfiles.test.transport=http}; the affected test is gated with {@code
 * @EnabledIfSystemProperty(... matches = "http")} (still runs and passes under the http
 * transport) rather than editing the shared seam without approval.
 */
class UploadFileVersionApiIT {

  static FilesTestApp app;
  static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private static final String EMBEDDED_TRANSPORT_SEAM_BUG =
      "FINDING: TestUtils#sendUpload's second writeInbound(LastHttpContent) call throws "
          + "ClosedChannelException on the EMBEDDED transport when BlobController already "
          + "responded+closed synchronously while handling the request head (before any content "
          + "is read) -- see UploadFileApiIT's javadoc. Passes cleanly under "
          + "-Dfiles.test.transport=http; disabled here rather than silently editing the shared "
          + "seam (TestUtils.java) without approval.";

  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OTHER_USER_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String REQUESTER_COOKIE = "ZM_AUTH_TOKEN=fake-token";

  @BeforeAll
  static void init() {
    app =
        GuiceNettyFilesTestAppBuilder.aFilesTestApp()
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
    app.mocks().setMaxNumberOfVersions(null);
    app.mocks().setMaxUploadableSizeMb(null);
  }

  @AfterAll
  static void cleanUpAll() {
    app.close();
  }

  private static String toBase64(String value) {
    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private HttpResponse uploadVersion(
      String nodeId, String filenameB64, boolean overwrite, byte[] body, String cookie) {
    List<Map.Entry<String, String>> headers =
        List.of(
            Map.entry("NodeId", nodeId),
            Map.entry("Filename", filenameB64),
            Map.entry("OverwriteVersion", String.valueOf(overwrite)));
    HttpRequest httpRequest = HttpRequest.ofUpload("POST", "/upload-version", cookie, headers, body);
    return app.upload(httpRequest);
  }

  @Test
  void givenANewVersionUploadShouldSucceedAndReturnTheIncrementedVersion() throws Exception {
    // Given — a single existing version (v1)
    String nodeId = "10000000-0000-0000-0000-000000000001";
    app.backdoor().populator().addNode(new SimplePopulatorTextFile(nodeId, REQUESTER_ID, "fake.txt"));
    app.mocks().storagesUploadSucceeds();

    // When
    HttpResponse httpResponse =
        uploadVersion(nodeId, toBase64("fake.txt"), false, "v2 content".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Map<String, Object> json = OBJECT_MAPPER.readValue(httpResponse.getBodyPayload(), Map.class);
    Assertions.assertThat(json).containsEntry("nodeId", nodeId);
    Assertions.assertThat(json).containsEntry("version", 2);
    Assertions.assertThat(app.backdoor().remainingVersionNumbers(nodeId)).containsExactly(1, 2);
  }

  @Test
  void givenOverwriteTrueUploadShouldReplaceTheCurrentVersionInPlace() throws Exception {
    // Given — two existing versions (v1, v2); current version is 2
    String nodeId = "10000000-0000-0000-0000-000000000001";
    app.backdoor()
        .populator()
        .addNode(new SimplePopulatorTextFile(nodeId, REQUESTER_ID, "fake.txt"))
        .addVersion(nodeId);
    app.mocks().storagesUploadSucceeds();

    // When
    HttpResponse httpResponse =
        uploadVersion(nodeId, toBase64("fake.txt"), true, "overwritten content".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);

    // Then — the response's version (2) IS > 1, so (unlike a fresh v1 upload) it IS present
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Map<String, Object> json = OBJECT_MAPPER.readValue(httpResponse.getBodyPayload(), Map.class);
    Assertions.assertThat(json).containsEntry("nodeId", nodeId);
    Assertions.assertThat(json).containsEntry("version", 2);
    // Same version count/numbers as before -- v2's row was replaced in place, not appended to
    Assertions.assertThat(app.backdoor().remainingVersionNumbers(nodeId)).containsExactly(1, 2);
    app.mocks().verifyStoragesUploaded(nodeId, 2);
  }

  @Test
  void givenNoWritePermissionUploadVersionShouldReturn404() {
    // Given — node owned by OTHER_USER_ID, shared READ_ONLY (no write) with the requester
    String nodeId = "10000000-0000-0000-0000-000000000001";
    app.backdoor()
        .populator()
        .addNode(new SimplePopulatorTextFile(nodeId, OTHER_USER_ID, "notMine.txt"))
        .addShare(nodeId, REQUESTER_ID, ACL.SharePermission.READ_ONLY);

    // When
    HttpResponse httpResponse =
        uploadVersion(nodeId, toBase64("notMine.txt"), false, "content".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(404);
    Assertions.assertThat(httpResponse.getBodyPayload()).isEqualTo("404 Not Found");
  }

  @Test
  void givenANonExistentNodeIdUploadVersionShouldReturnTheSame404AsNoPermission() {
    // Given — see class-level FINDING: a syntactically-valid but non-existent id fails the SAME
    // early permission gate as "no permission", not the deeper (dead) not-found branch.
    String nonExistentId = "10000000-0000-0000-0000-00000000ffff";

    // When
    HttpResponse httpResponse =
        uploadVersion(nonExistentId, toBase64("ghost.txt"), false, "content".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(404);
    Assertions.assertThat(httpResponse.getBodyPayload()).isEqualTo("404 Not Found");
  }

  @Test
  void givenTheVersionCapIsExceededUploadVersionShouldReturn405() {
    // Given — cap = 2, node already has 3 versions (3 > 2)
    String nodeId = "10000000-0000-0000-0000-000000000001";
    app.backdoor()
        .populator()
        .addNode(new SimplePopulatorTextFile(nodeId, REQUESTER_ID, "fake.txt"))
        .addVersion(nodeId)
        .addVersion(nodeId);
    app.mocks().setMaxNumberOfVersions(2);

    // When
    HttpResponse httpResponse =
        uploadVersion(nodeId, toBase64("fake.txt"), false, "content".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(405);
    Assertions.assertThat(httpResponse.getBodyPayload())
        .isEqualTo(
            String.format(
                "Node %s has reached max number of versions (2), cannot add more versions", nodeId));
  }

  @Test
  void givenTheVersionCountAtTheCapUploadVersionShouldSucceedAndEvictTheOldestVersion()
      throws Exception {
    // Given — cap = 2, node has exactly 2 versions (2 > 2 is false -> upload succeeds; 2 >= 2 is
    // true -> the oldest non-keptForever version is evicted after the new one lands)
    String nodeId = "10000000-0000-0000-0000-000000000001";
    app.backdoor()
        .populator()
        .addNode(new SimplePopulatorTextFile(nodeId, REQUESTER_ID, "fake.txt"))
        .addVersion(nodeId);
    app.mocks().setMaxNumberOfVersions(2);
    app.mocks().storagesUploadSucceeds();
    app.mocks().storagesBulkDeleteSucceeds(List.of());

    // When
    HttpResponse httpResponse =
        uploadVersion(nodeId, toBase64("fake.txt"), false, "v3 content".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Map<String, Object> json = OBJECT_MAPPER.readValue(httpResponse.getBodyPayload(), Map.class);
    Assertions.assertThat(json).containsEntry("version", 3);
    // v1 (the oldest) was evicted; v2 and the new v3 remain
    Assertions.assertThat(app.backdoor().remainingVersionNumbers(nodeId)).containsExactly(2, 3);
  }

  @Test
  void givenAMimeTypeMismatchUploadVersionShouldReturn400() {
    // Given — existing node is TEXT (fake.txt); new filename maps to IMAGE
    String nodeId = "10000000-0000-0000-0000-000000000001";
    app.backdoor().populator().addNode(new SimplePopulatorTextFile(nodeId, REQUESTER_ID, "fake.txt"));

    // When
    HttpResponse httpResponse =
        uploadVersion(nodeId, toBase64("photo.png"), false, "content".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);

    // Then — FileTypeMismatchException groups with the GENERIC-body BAD_REQUEST branch in
    // ExceptionsHandler (same group as BadRequestException/IllegalArgumentException), so the
    // actual descriptive message is discarded just like FileSizeException's.
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(400);
    Assertions.assertThat(httpResponse.getBodyPayload()).isEqualTo("400 Bad Request");
  }

  @Test
  @EnabledIfSystemProperty(named = "files.test.transport", matches = "http", disabledReason = EMBEDDED_TRANSPORT_SEAM_BUG)
  void givenABodyOverTheConfiguredSizeCapUploadVersionShouldReturn413() {
    // Given — a 0MB cap + a tiny body; see UploadFileApiIT's analogous test for why the body is
    // kept tiny rather than multi-MB (avoids a genuine client/server TCP race on both transports).
    String nodeId = "10000000-0000-0000-0000-000000000001";
    app.backdoor().populator().addNode(new SimplePopulatorTextFile(nodeId, REQUESTER_ID, "fake.txt"));
    app.mocks().setMaxUploadableSizeMb(0);
    byte[] oversizedBody = "over the 0MB cap".getBytes(StandardCharsets.UTF_8);

    // When
    HttpResponse httpResponse =
        uploadVersion(nodeId, toBase64("fake.txt"), false, oversizedBody, REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(413);
    Assertions.assertThat(httpResponse.getBodyPayload()).isEqualTo("413 Request Entity Too Large");

    // reset — this override is independent of the @AfterEach's setMaxNumberOfVersions(null) reset
    app.mocks().setMaxUploadableSizeMb(null);
  }

  @Test
  void givenStoragesBulkDeleteReturnsANullResponseTheEvictedVersionIsStillDeleted()
      throws Exception {
    // Given — same cap/eviction setup as the successful-eviction test, but the bulk-delete
    // endpoint returns a body the SDK deserialises as ids=null, which throws an NPE that
    // production explicitly catches and treats as "all deletes succeeded" (documents the SDK-bug
    // current behaviour, per the plan's finding — NOT fixed here).
    String nodeId = "10000000-0000-0000-0000-000000000001";
    app.backdoor()
        .populator()
        .addNode(new SimplePopulatorTextFile(nodeId, REQUESTER_ID, "fake.txt"))
        .addVersion(nodeId);
    app.mocks().setMaxNumberOfVersions(2);
    app.mocks().storagesUploadSucceeds();
    app.mocks().storagesBulkDeleteReturnsNullResponse();

    // When
    HttpResponse httpResponse =
        uploadVersion(nodeId, toBase64("fake.txt"), false, "v3 content".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);

    // Then — upload succeeds, and despite the SDK-null bug the oldest version is STILL deleted
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Map<String, Object> json = OBJECT_MAPPER.readValue(httpResponse.getBodyPayload(), Map.class);
    Assertions.assertThat(json).containsEntry("version", 3);
    Assertions.assertThat(app.backdoor().remainingVersionNumbers(nodeId)).containsExactly(2, 3);
  }
}
