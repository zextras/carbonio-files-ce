// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code com.zextras.carbonio.files.acceptance.InternalBlobResourceApiIT} rewritten as an
 * out-of-process {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}: the trusted,
 * UNAUTHENTICATED {@code /internal/accounts/{userId}/...} blob surface ({@code upload}, {@code
 * upload-version}, {@code download[/{version}]}). None of these routes has an auth-handler in its
 * pipeline (mesh mTLS is the trust boundary) — the {@code userId} PATH segment alone determines
 * whose ACLs are checked / who owns a new node.
 *
 * <p><b>Config-split (D3/Batch D):</b> the original class held 9 methods. ONE scenario ({@code
 * givenABodyOverTheConfiguredSizeCapInternalUploadShouldStillSucceed}) asserts that this trusted
 * surface bypasses the configured upload-size cap entirely — which requires an ACTUAL cap to be
 * configured to be a meaningful assertion (not a vacuous "succeeds because nothing is capped"). It
 * moved to the sibling {@link InternalBlobResourceSizeCapIT} ({@code
 * @WithTestResource(UploadCapResource.class)}, cap=0, so ANY non-empty body proves the bypass).
 * This class keeps the remaining 8 methods on the shared default (uncapped) stack. Mapping: 8
 * (here) + 1 ({@code InternalBlobResourceSizeCapIT}) = 9 (unchanged from the original).
 */
class InternalBlobResourceApiIT extends AbstractFilesIT {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OTHER_USER_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String REQUESTER_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String OTHER_USER_COOKIE = "ZM_AUTH_TOKEN=fake-token-b";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", REQUESTER_ID);
    FilesStackTestResource.getUserManagementService().registerToken("fake-token-b", OTHER_USER_ID);
  }

  private static String toBase64(String value) {
    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  // --------------------------------------------------------------------------------------- upload

  /** Deliberately takes NO cookie parameter: {@code /internal/**} has no auth-handler. */
  private static Response internalUpload(String userId, String parentId, String filenameB64, byte[] body) {
    var request = RestAssured.given().header("Filename", filenameB64);
    if (parentId != null) {
      request = request.header("ParentId", parentId);
    }
    return request.body(body).post("/internal/accounts/" + userId + "/upload");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> getNode(String nodeId, String cookie) {
    // NOTE: the GraphQL Node interface splits the stored filename into a base `name` (extension
    // stripped) and a separate `extension` field -- see UploadFileApiIT's helper for detail.
    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("getNode")
            .withString("node_id", nodeId)
            .withWantedResultFormat("{ id name owner { id } ... on File { extension } }")
            .build();
    Response response = graphql(bodyPayload, cookie);
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    return TestUtils.jsonResponseToMap(response.getBody().asString(), "getNode");
  }

  @Test
  void givenNoAuthenticationInternalUploadShouldSucceedAndThePathUserIdShouldDriveOwnership()
      throws Exception {
    // Given — deliberately NO cookie at all: this route has no auth-handler in its pipeline.

    // When
    Response response =
        internalUpload(REQUESTER_ID, null, toBase64("internal.txt"), "content".getBytes(StandardCharsets.UTF_8));

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Map<String, Object> json = OBJECT_MAPPER.readValue(response.getBody().asString(), Map.class);
    String nodeId = (String) json.get("nodeId");
    Assertions.assertThat(nodeId).isNotBlank();
    Assertions.assertThat(nodeExists(nodeId, REQUESTER_COOKIE)).isTrue();

    // The path userId alone determined the node's owner/creator, with no authenticated session in
    // play at all.
    Map<String, Object> node = getNode(nodeId, REQUESTER_COOKIE);
    Assertions.assertThat(node).containsEntry("name", "internal").containsEntry("extension", "txt");
    Assertions.assertThat(((Map<String, Object>) node.get("owner"))).containsEntry("id", REQUESTER_ID);
  }

  @Test
  @SuppressWarnings("unchecked")
  void givenASharedDestinationFolderInternalUploadShouldFireNoAddedNodeNotification() throws Exception {
    // Given — folder owned by REQUESTER_ID, shared with OTHER_USER_ID (who would normally be
    // notified of a new node landing in a folder shared with them).
    String folderId = seedFolder("sharedFolder", LOCAL_ROOT, REQUESTER_COOKIE);
    seedShare(folderId, OTHER_USER_ID, ACL.SharePermission.READ_AND_SHARE, REQUESTER_COOKIE);

    // When — internal upload into the shared folder, owned by the folder owner (path userId)
    Response response =
        internalUpload(
            REQUESTER_ID, folderId, toBase64("internal-in-shared.txt"), "content".getBytes(StandardCharsets.UTF_8));
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);

    // Then — OTHER_USER_ID only sees the NewShare notification from createShare/seedShare; NO
    // AddedNode notification was created for the internal upload (requesterEntity is
    // Optional.empty(), which gates BlobService#uploadFile's notification branch off regardless of
    // the share).
    String getNotificationsPayload =
        GraphqlCommandBuilder.aQueryBuilder("getNotifications")
            .withBoolean("update_last_seen", true)
            .withWantedResultFormat(
                "{ notifications { ... on AddedNode { created_at }, ... on NewShare { created_at } } }")
            .build();
    Response notificationsResponse = graphql(getNotificationsPayload, OTHER_USER_COOKIE);
    Assertions.assertThat(notificationsResponse.getStatusCode()).isEqualTo(200);

    Map<String, Object> page =
        TestUtils.jsonResponseToMap(notificationsResponse.getBody().asString(), "getNotifications");
    List<Map<String, Object>> notifications = (List<Map<String, Object>>) page.get("notifications");
    Assertions.assertThat(notifications).hasSize(1);
  }

  // ------------------------------------------------------------------------------ upload-version

  private static Response internalUploadVersion(
      String userId, String nodeId, String filenameB64, boolean overwrite, byte[] body) {
    return RestAssured.given()
        .header("NodeId", nodeId)
        .header("Filename", filenameB64)
        .header("OverwriteVersion", String.valueOf(overwrite))
        .body(body)
        .post("/internal/accounts/" + userId + "/upload-version");
  }

  @Test
  void internalUploadVersionShouldCreateASecondVersionForThePathUserId() throws Exception {
    // Given — v1 uploaded through the same trusted surface
    byte[] v1 = "version one".getBytes(StandardCharsets.UTF_8);
    byte[] v2 = "version two is longer".getBytes(StandardCharsets.UTF_8);
    Response uploadResponse = internalUpload(REQUESTER_ID, null, toBase64("doc.txt"), v1);
    Assertions.assertThat(uploadResponse.getStatusCode()).isEqualTo(200);
    String nodeId = (String) OBJECT_MAPPER.readValue(uploadResponse.getBody().asString(), Map.class).get("nodeId");

    // When
    Response versionResponse = internalUploadVersion(REQUESTER_ID, nodeId, toBase64("doc.txt"), false, v2);

    // Then
    Assertions.assertThat(versionResponse.getStatusCode()).isEqualTo(200);
    Map<String, Object> json = OBJECT_MAPPER.readValue(versionResponse.getBody().asString(), Map.class);
    Assertions.assertThat(json).containsEntry("nodeId", nodeId);
    Assertions.assertThat(((Number) json.get("version")).intValue()).isEqualTo(2);
  }

  @Test
  void internalUploadVersionWithoutPermissionOnTheNodeShouldReturn404() throws Exception {
    // Given — node owned by OTHER_USER_ID, requester (path userId) has no share on it
    Response uploadResponse =
        internalUpload(OTHER_USER_ID, null, toBase64("doc.txt"), "v1".getBytes(StandardCharsets.UTF_8));
    Assertions.assertThat(uploadResponse.getStatusCode()).isEqualTo(200);
    String nodeId = (String) OBJECT_MAPPER.readValue(uploadResponse.getBody().asString(), Map.class).get("nodeId");

    // When — a DIFFERENT path userId attempts the new version
    Response versionResponse =
        internalUploadVersion(REQUESTER_ID, nodeId, toBase64("doc.txt"), false, "v2".getBytes(StandardCharsets.UTF_8));

    // Then
    Assertions.assertThat(versionResponse.getStatusCode()).isEqualTo(404);
  }

  // ------------------------------------------------------------------------------------- download

  private static Response internalDownload(String userId, String nodeId, Integer version) {
    String endpoint =
        version == null
            ? "/internal/accounts/" + userId + "/download/" + nodeId
            : "/internal/accounts/" + userId + "/download/" + nodeId + "/" + version;
    return RestAssured.given().get(endpoint);
  }

  @Test
  void internalDownloadShouldRoundTripBytesForThePathUserId() throws Exception {
    // Given — a node uploaded through the same trusted surface
    byte[] content = "hello internal streaming world".getBytes(StandardCharsets.UTF_8);
    Response uploadResponse = internalUpload(REQUESTER_ID, null, toBase64("hello.txt"), content);
    Assertions.assertThat(uploadResponse.getStatusCode()).isEqualTo(200);
    String nodeId = (String) OBJECT_MAPPER.readValue(uploadResponse.getBody().asString(), Map.class).get("nodeId");

    // When — no cookie at all: this route has no auth-handler in its pipeline either
    Response downloadResponse = internalDownload(REQUESTER_ID, nodeId, null);

    // Then
    Assertions.assertThat(downloadResponse.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(downloadResponse.getBody().asString()).isEqualTo(new String(content, StandardCharsets.UTF_8));
  }

  @Test
  void internalDownloadShouldServeAnExplicitOlderVersion() throws Exception {
    // Given — v1 then v2 uploaded through the trusted surface
    byte[] v1 = "version one".getBytes(StandardCharsets.UTF_8);
    byte[] v2 = "version two is longer".getBytes(StandardCharsets.UTF_8);
    Response uploadResponse = internalUpload(REQUESTER_ID, null, toBase64("doc.txt"), v1);
    String nodeId = (String) OBJECT_MAPPER.readValue(uploadResponse.getBody().asString(), Map.class).get("nodeId");
    Response versionResponse = internalUploadVersion(REQUESTER_ID, nodeId, toBase64("doc.txt"), false, v2);
    Assertions.assertThat(versionResponse.getStatusCode()).isEqualTo(200);

    // When — explicit v1, even though v2 is now current
    Response firstVersionDownload = internalDownload(REQUESTER_ID, nodeId, 1);

    // Then
    Assertions.assertThat(firstVersionDownload.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(firstVersionDownload.getBody().asString()).isEqualTo(new String(v1, StandardCharsets.UTF_8));
  }

  @Test
  void internalDownloadOfANonPermittedNodeShouldReturn404() throws Exception {
    // Given — node owned by OTHER_USER_ID, never shared with REQUESTER_ID
    Response uploadResponse =
        internalUpload(OTHER_USER_ID, null, toBase64("notMine.txt"), "content".getBytes(StandardCharsets.UTF_8));
    String nodeId = (String) OBJECT_MAPPER.readValue(uploadResponse.getBody().asString(), Map.class).get("nodeId");

    // When — a DIFFERENT path userId attempts the download
    Response downloadResponse = internalDownload(REQUESTER_ID, nodeId, null);

    // Then — legacy parity: permission-denied collapses into the same 404 as "not found"
    Assertions.assertThat(downloadResponse.getStatusCode()).isEqualTo(404);
  }

  @Test
  void internalDownloadOfANonExistentNodeShouldReturn404() {
    String nonExistentId = "10000000-0000-0000-0000-00000000ffff";
    Response downloadResponse = internalDownload(REQUESTER_ID, nonExistentId, null);
    Assertions.assertThat(downloadResponse.getStatusCode()).isEqualTo(404);
  }
}
