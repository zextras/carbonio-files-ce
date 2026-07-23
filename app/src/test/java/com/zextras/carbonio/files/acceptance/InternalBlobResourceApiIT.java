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
 * Trusted, UNAUTHENTICATED {@code /internal/accounts/{userId}/...} blob surface: {@code
 * POST .../upload}, {@code POST .../upload-version} and {@code GET .../download/{nodeId}[/{version}]}.
 * None of these routes has an auth-handler in its pipeline (mesh mTLS is the trust boundary) — the
 * {@code userId} PATH segment is the ONLY thing that determines whose ACLs are checked/who owns a
 * new node, exactly mirroring the trusted-caller contract of {@code FilesGrpcService}.
 *
 * <p>Replaces the retired header-based {@code POST /internal/upload} (see the former {@code
 * InternalUploadApiIT}): same no-auth/no-size-cap/no-notification behavior, reshaped so the acting
 * user id is a path segment instead of an {@code AccountId} header, and extended to cover the
 * upload-version and download counterparts added alongside it.
 */
@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class InternalBlobResourceApiIT {

  static FilesTestApp app;
  static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OTHER_USER_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String REQUESTER_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String OTHER_USER_COOKIE = "ZM_AUTH_TOKEN=fake-token-b";

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
    app.mocks().setMaxDownloadableSizeMb(null);
  }

  @AfterAll
  static void cleanUpAll() {
    app.close();
  }

  private static String toBase64(String value) {
    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  // --------------------------------------------------------------------------------------- upload

  /** Deliberately takes NO cookie parameter: {@code /internal/**} has no auth-handler. */
  private HttpResponse internalUpload(
      String userId, String parentId, String filenameB64, byte[] body) {
    List<Map.Entry<String, String>> headers = new ArrayList<>();
    headers.add(Map.entry("Filename", filenameB64));
    if (parentId != null) {
      headers.add(Map.entry("ParentId", parentId));
    }
    HttpRequest httpRequest =
        HttpRequest.ofUpload("POST", "/internal/accounts/" + userId + "/upload", null, headers, body);
    return app.upload(httpRequest);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> getNode(String nodeId, String cookie) {
    // NOTE: the GraphQL Node interface splits the stored filename into a base `name` (extension
    // stripped) and a separate `extension` field -- see UploadFileApiIT's helper for detail.
    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("getNode")
            .withString("node_id", nodeId)
            .withWantedResultFormat("{ id name owner { id } ... on File { extension } }")
            .build();
    HttpResponse httpResponse = app.send(HttpRequest.of("POST", "/graphql/", cookie, bodyPayload));
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    return TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "getNode");
  }

  @Test
  void givenNoAuthenticationInternalUploadShouldSucceedAndThePathUserIdShouldDriveOwnership()
      throws Exception {
    // Given — deliberately NO cookie at all: this route has no auth-handler in its pipeline.
    app.mocks().storagesUploadSucceeds();

    // When
    HttpResponse httpResponse =
        internalUpload(REQUESTER_ID, null, toBase64("internal.txt"), "content".getBytes(StandardCharsets.UTF_8));

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Map<String, Object> json = OBJECT_MAPPER.readValue(httpResponse.getBodyPayload(), Map.class);
    String nodeId = (String) json.get("nodeId");
    Assertions.assertThat(nodeId).isNotBlank();
    Assertions.assertThat(app.backdoor().nodeExists(nodeId)).isTrue();

    // The path userId alone determined the node's owner/creator, with no authenticated session in
    // play at all.
    Map<String, Object> node = getNode(nodeId, REQUESTER_COOKIE);
    Assertions.assertThat(node).containsEntry("name", "internal").containsEntry("extension", "txt");
    Assertions.assertThat(((Map<String, Object>) node.get("owner"))).containsEntry("id", REQUESTER_ID);
  }

  @Test
  void givenABodyOverTheConfiguredSizeCapInternalUploadShouldStillSucceed() throws Exception {
    // Given — a strict 1MB cap is configured, but /internal/accounts/{userId}/upload never checks it
    app.mocks().setMaxUploadableSizeMb(1);
    app.mocks().storagesUploadSucceeds();
    byte[] oversizedBody = new byte[2 * 1024 * 1024]; // 2MB > the configured 1MB cap

    // When
    HttpResponse httpResponse =
        internalUpload(REQUESTER_ID, null, toBase64("big-internal.bin"), oversizedBody);

    // Then — no 413: the size cap is bypassed entirely for the internal route
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Map<String, Object> json = OBJECT_MAPPER.readValue(httpResponse.getBodyPayload(), Map.class);
    Assertions.assertThat(json).containsKey("nodeId");
  }

  @Test
  @SuppressWarnings("unchecked")
  void givenASharedDestinationFolderInternalUploadShouldFireNoAddedNodeNotification() throws Exception {
    // Given — folder owned by REQUESTER_ID, shared with OTHER_USER_ID (who would normally be
    // notified of a new node landing in a folder shared with them).
    String folderId = "10000000-0000-0000-0000-000000000001";
    app.backdoor().populator().addNode(new SimplePopulatorFolder(folderId, REQUESTER_ID, "sharedFolder"));

    String createSharePayload =
        GraphqlCommandBuilder.aMutationBuilder("createShare")
            .withString("node_id", folderId)
            .withString("share_target_id", OTHER_USER_ID)
            .withEnum("permission", ACL.SharePermission.READ_AND_SHARE)
            .withWantedResultFormat("{ created_at }")
            .build();
    HttpResponse createShareResponse =
        app.send(HttpRequest.of("POST", "/graphql/", REQUESTER_COOKIE, createSharePayload));
    Assertions.assertThat(createShareResponse.getStatus()).isEqualTo(200);

    app.mocks().storagesUploadSucceeds();

    // When — internal upload into the shared folder, owned by the folder owner (path userId)
    HttpResponse httpResponse =
        internalUpload(REQUESTER_ID, folderId, toBase64("internal-in-shared.txt"), "content".getBytes(StandardCharsets.UTF_8));
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    // Then — OTHER_USER_ID only sees the NewShare notification from createShare; NO AddedNode
    // notification was created for the internal upload (requesterEntity is Optional.empty(), which
    // gates BlobService#uploadFile's notification branch off regardless of the share).
    String getNotificationsPayload =
        GraphqlCommandBuilder.aQueryBuilder("getNotifications")
            .withBoolean("update_last_seen", true)
            .withWantedResultFormat(
                "{ notifications { ... on AddedNode { created_at }, ... on NewShare { created_at } } }")
            .build();
    HttpResponse notificationsResponse =
        app.send(HttpRequest.of("POST", "/graphql/", OTHER_USER_COOKIE, getNotificationsPayload));
    Assertions.assertThat(notificationsResponse.getStatus()).isEqualTo(200);

    Map<String, Object> page =
        TestUtils.jsonResponseToMap(notificationsResponse.getBodyPayload(), "getNotifications");
    List<Map<String, Object>> notifications = (List<Map<String, Object>>) page.get("notifications");
    Assertions.assertThat(notifications).hasSize(1);
  }

  // ------------------------------------------------------------------------------ upload-version

  private HttpResponse internalUploadVersion(
      String userId, String nodeId, String filenameB64, boolean overwrite, byte[] body) {
    List<Map.Entry<String, String>> headers = new ArrayList<>();
    headers.add(Map.entry("NodeId", nodeId));
    headers.add(Map.entry("Filename", filenameB64));
    headers.add(Map.entry("OverwriteVersion", String.valueOf(overwrite)));
    HttpRequest httpRequest =
        HttpRequest.ofUpload(
            "POST", "/internal/accounts/" + userId + "/upload-version", null, headers, body);
    return app.upload(httpRequest);
  }

  @Test
  void internalUploadVersionShouldCreateASecondVersionForThePathUserId() throws Exception {
    // Given — v1 uploaded through the same trusted surface
    app.mocks().storagesUploadSucceeds();
    byte[] v1 = "version one".getBytes(StandardCharsets.UTF_8);
    byte[] v2 = "version two is longer".getBytes(StandardCharsets.UTF_8);
    HttpResponse uploadResponse = internalUpload(REQUESTER_ID, null, toBase64("doc.txt"), v1);
    Assertions.assertThat(uploadResponse.getStatus()).isEqualTo(200);
    String nodeId =
        (String) OBJECT_MAPPER.readValue(uploadResponse.getBodyPayload(), Map.class).get("nodeId");

    // When
    HttpResponse versionResponse =
        internalUploadVersion(REQUESTER_ID, nodeId, toBase64("doc.txt"), false, v2);

    // Then
    Assertions.assertThat(versionResponse.getStatus()).isEqualTo(200);
    Map<String, Object> json = OBJECT_MAPPER.readValue(versionResponse.getBodyPayload(), Map.class);
    Assertions.assertThat(json).containsEntry("nodeId", nodeId);
    Assertions.assertThat(((Number) json.get("version")).intValue()).isEqualTo(2);
  }

  @Test
  void internalUploadVersionWithoutPermissionOnTheNodeShouldReturn404() throws Exception {
    // Given — node owned by OTHER_USER_ID, requester (path userId) has no share on it
    app.mocks().storagesUploadSucceeds();
    HttpResponse uploadResponse =
        internalUpload(OTHER_USER_ID, null, toBase64("doc.txt"), "v1".getBytes(StandardCharsets.UTF_8));
    Assertions.assertThat(uploadResponse.getStatus()).isEqualTo(200);
    String nodeId =
        (String) OBJECT_MAPPER.readValue(uploadResponse.getBodyPayload(), Map.class).get("nodeId");

    // When — a DIFFERENT path userId attempts the new version
    HttpResponse versionResponse =
        internalUploadVersion(
            REQUESTER_ID, nodeId, toBase64("doc.txt"), false, "v2".getBytes(StandardCharsets.UTF_8));

    // Then
    Assertions.assertThat(versionResponse.getStatus()).isEqualTo(404);
  }

  // ------------------------------------------------------------------------------------- download

  private HttpResponse internalDownload(String userId, String nodeId, Integer version) {
    String endpoint =
        version == null
            ? "/internal/accounts/" + userId + "/download/" + nodeId
            : "/internal/accounts/" + userId + "/download/" + nodeId + "/" + version;
    return app.send(HttpRequest.of("GET", endpoint, null, null));
  }

  @Test
  void internalDownloadShouldRoundTripBytesForThePathUserId() throws Exception {
    // Given — a node uploaded through the same trusted surface
    app.mocks().storagesUploadSucceeds();
    byte[] content = "hello internal streaming world".getBytes(StandardCharsets.UTF_8);
    HttpResponse uploadResponse = internalUpload(REQUESTER_ID, null, toBase64("hello.txt"), content);
    Assertions.assertThat(uploadResponse.getStatus()).isEqualTo(200);
    String nodeId =
        (String) OBJECT_MAPPER.readValue(uploadResponse.getBodyPayload(), Map.class).get("nodeId");

    // When — no cookie at all: this route has no auth-handler in its pipeline either
    HttpResponse downloadResponse = internalDownload(REQUESTER_ID, nodeId, null);

    // Then
    Assertions.assertThat(downloadResponse.getStatus()).isEqualTo(200);
    Assertions.assertThat(downloadResponse.getBodyPayload())
        .isEqualTo(new String(content, StandardCharsets.UTF_8));
  }

  @Test
  void internalDownloadShouldServeAnExplicitOlderVersion() throws Exception {
    // Given — v1 then v2 uploaded through the trusted surface
    app.mocks().storagesUploadSucceeds();
    byte[] v1 = "version one".getBytes(StandardCharsets.UTF_8);
    byte[] v2 = "version two is longer".getBytes(StandardCharsets.UTF_8);
    HttpResponse uploadResponse = internalUpload(REQUESTER_ID, null, toBase64("doc.txt"), v1);
    String nodeId =
        (String) OBJECT_MAPPER.readValue(uploadResponse.getBodyPayload(), Map.class).get("nodeId");
    HttpResponse versionResponse =
        internalUploadVersion(REQUESTER_ID, nodeId, toBase64("doc.txt"), false, v2);
    Assertions.assertThat(versionResponse.getStatus()).isEqualTo(200);

    // When — explicit v1, even though v2 is now current
    HttpResponse firstVersionDownload = internalDownload(REQUESTER_ID, nodeId, 1);

    // Then
    Assertions.assertThat(firstVersionDownload.getStatus()).isEqualTo(200);
    Assertions.assertThat(firstVersionDownload.getBodyPayload())
        .isEqualTo(new String(v1, StandardCharsets.UTF_8));
  }

  @Test
  void internalDownloadOfANonPermittedNodeShouldReturn404() throws Exception {
    // Given — node owned by OTHER_USER_ID, never shared with REQUESTER_ID
    app.mocks().storagesUploadSucceeds();
    HttpResponse uploadResponse =
        internalUpload(OTHER_USER_ID, null, toBase64("notMine.txt"), "content".getBytes(StandardCharsets.UTF_8));
    String nodeId =
        (String) OBJECT_MAPPER.readValue(uploadResponse.getBodyPayload(), Map.class).get("nodeId");

    // When — a DIFFERENT path userId attempts the download
    HttpResponse downloadResponse = internalDownload(REQUESTER_ID, nodeId, null);

    // Then — legacy parity: permission-denied collapses into the same 404 as "not found"
    Assertions.assertThat(downloadResponse.getStatus()).isEqualTo(404);
  }

  @Test
  void internalDownloadOfANonExistentNodeShouldReturn404() {
    String nonExistentId = "10000000-0000-0000-0000-00000000ffff";
    HttpResponse downloadResponse = internalDownload(REQUESTER_ID, nonExistentId, null);
    Assertions.assertThat(downloadResponse.getStatus()).isEqualTo(404);
  }
}
