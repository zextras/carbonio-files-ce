// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.acceptance.seam.FilesTestApp;
import com.zextras.carbonio.files.acceptance.seam.impl.GuiceNettyFilesTestAppBuilder;
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
 * Task 2.3 of the acceptance coverage-expansion plan: {@code POST /internal/upload}. Per {@code
 * HttpRoutingHandler#channelRead0} ("No auth for internal calls") this route has NO {@code
 * auth-handler} in its pipeline at all — the {@code AccountId} header is the ONLY thing that
 * determines ownership, there is no size cap ({@code BlobController#uploadFileInternal} never
 * calls {@code isRequestSizeOverLimit}, unlike {@code uploadFile}/{@code uploadFileVersion}), and
 * no notification is ever fired ({@code doUploadFile} passes {@code Optional.empty()} as the
 * requester entity, which gates {@code BlobService.uploadFile}'s notification branch off
 * regardless of who else is shared on the destination folder).
 */
class InternalUploadApiIT {

  static FilesTestApp app;
  static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OTHER_USER_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String REQUESTER_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String OTHER_USER_COOKIE = "ZM_AUTH_TOKEN=fake-token-b";

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
    app.mocks().setMaxUploadableSizeMb(null);
  }

  @AfterAll
  static void cleanUpAll() {
    app.close();
  }

  private static String toBase64(String value) {
    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  /** Deliberately takes NO cookie parameter: {@code /internal/upload} has no auth-handler. */
  private HttpResponse internalUpload(String accountId, String parentId, String filenameB64, byte[] body) {
    List<Map.Entry<String, String>> headers = new ArrayList<>();
    headers.add(Map.entry("Filename", filenameB64));
    headers.add(Map.entry("AccountId", accountId));
    if (parentId != null) {
      headers.add(Map.entry("ParentId", parentId));
    }
    HttpRequest httpRequest =
        HttpRequest.ofUpload("POST", "/internal/upload", null, headers, body);
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
  void givenNoAuthenticationInternalUploadShouldSucceedAndTheAccountIdHeaderShouldDriveOwnership()
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

    // The AccountId header alone determined the node's owner/creator, with no authenticated
    // session in play at all.
    Map<String, Object> node = getNode(nodeId, REQUESTER_COOKIE);
    Assertions.assertThat(node).containsEntry("name", "internal").containsEntry("extension", "txt");
    Assertions.assertThat(((Map<String, Object>) node.get("owner"))).containsEntry("id", REQUESTER_ID);
  }

  @Test
  void givenABodyOverTheConfiguredSizeCapInternalUploadShouldStillSucceed() throws Exception {
    // Given — a strict 1MB cap is configured, but /internal/upload never checks it
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

    // When — internal upload into the shared folder, owned by the folder owner (AccountId)
    HttpResponse httpResponse =
        internalUpload(REQUESTER_ID, folderId, toBase64("internal-in-shared.txt"), "content".getBytes(StandardCharsets.UTF_8));
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    // Then — OTHER_USER_ID only sees the NewShare notification from createShare; NO AddedNode
    // notification was created for the internal upload (requesterEntity is Optional.empty(),
    // which gates BlobService#uploadFile's notification branch off regardless of the share).
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
}
