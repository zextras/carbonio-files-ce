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
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code com.zextras.carbonio.files.acceptance.UploadFileApiIT} rewritten as an out-of-process
 * {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}: {@code POST /upload}, the plain
 * new-node upload route.
 *
 * <p><b>Config-split (D3/Batch D):</b> the original class held 10 methods. The single
 * upload-size-cap=0 scenario ({@code givenABodyOverTheConfiguredSizeCapUploadShouldReturn413})
 * cannot share this class's default (uncapped) stack — {@code FilesConfig}'s size cap is a
 * BOOT-TIME snapshot on the launched out-of-process app, not a per-test-runtime override — so it
 * moved verbatim to the sibling {@link UploadFileSizeCapIT}, which carries a class-restricted
 * {@code @WithTestResource(UploadCapResource.class)}. This class keeps the remaining 9 methods on
 * the shared default stack. Mapping: 9 methods here + 1 in {@code UploadFileSizeCapIT} = 10
 * (unchanged from the original).
 *
 * <p><b>FINDING (carried over verbatim from the seam original):</b> the "missing {@code
 * Content-Length} header -> 500" scenario is NOT reproducible on either the old seam or this
 * RestAssured-driven harness: RestAssured (like the JDK HTTP client before it) computes {@code
 * Content-Length} itself from the body and does not let a caller omit or override it. This scenario
 * remains DELIBERATELY OMITTED, as in the original.
 */
class UploadFileApiIT extends AbstractFilesIT {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  protected static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  protected static final String OTHER_USER_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  protected static final String REQUESTER_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  protected static final String OTHER_COOKIE = "ZM_AUTH_TOKEN=fake-token-b";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", REQUESTER_ID);
    FilesStackTestResource.getUserManagementService().registerToken("fake-token-b", OTHER_USER_ID);
  }

  @SuppressWarnings("unchecked")
  protected static Map<String, Object> getNode(String nodeId, String cookie) {
    // NOTE: the GraphQL Node interface splits the stored filename into a base `name` (extension
    // stripped, see Node#getName()) and a separate `extension` field -- there is no combined
    // "full name" field on File/Folder. Both must be queried and reassembled to check a filename.
    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("getNode")
            .withString("node_id", nodeId)
            .withWantedResultFormat("{ id name owner { id } ... on File { extension } }")
            .build();
    Response response = graphql(bodyPayload, cookie);
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    return TestUtils.jsonResponseToMap(response.getBody().asString(), "getNode");
  }

  @SuppressWarnings("unchecked")
  protected static List<String> childNodeIds(String folderId) {
    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("findNodes")
            .withString("folder_id", folderId)
            .withEnumLiteral("sort", "NAME_ASC")
            .withInteger("limit", 50)
            .withWantedResultFormat("{ nodes { id }, page_token }")
            .build();
    Response response = graphql(bodyPayload, REQUESTER_COOKIE);
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Map<String, Object> page =
        TestUtils.jsonResponseToMap(response.getBody().asString(), "findNodes");
    List<Map<String, Object>> nodes = (List<Map<String, Object>>) page.get("nodes");
    return nodes.stream().map(node -> (String) node.get("id")).toList();
  }

  /**
   * Reads the current value of the Prometheus {@code files_upload_total{...,uri="/upload",...}}
   * counter off {@code GET /metrics} (an unauthenticated route, see {@code MetricsApiIT}). Used to
   * assert a delta rather than an absolute value so the check is order-independent across tests in
   * this class.
   */
  protected static double uploadCounterValue() {
    Response response = RestAssured.given().get("/metrics");
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    for (String line : response.getBody().asString().split("\n")) {
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
    double counterBefore = uploadCounterValue();

    // When
    Response response =
        upload(
            null,
            null,
            "hello world".getBytes(StandardCharsets.UTF_8),
            "hello.txt",
            REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Map<String, Object> json = OBJECT_MAPPER.readValue(response.getBody().asString(), Map.class);
    Assertions.assertThat(json).containsKey("nodeId");
    String nodeId = (String) json.get("nodeId");
    Assertions.assertThat(nodeId).isNotBlank();
    // KNOWN QUIRK (Wave 0): UploadVersionResponse#setVersion(int) only stores values > 1, so a
    // fresh upload's version (1) is never set -> the field is entirely absent from the JSON.
    Assertions.assertThat(json).doesNotContainKey("version");

    Assertions.assertThat(nodeExists(nodeId, REQUESTER_COOKIE)).isTrue();

    Map<String, Object> node = getNode(nodeId, REQUESTER_COOKIE);
    Assertions.assertThat(node).containsEntry("name", "hello").containsEntry("extension", "txt");
    Assertions.assertThat(((Map<String, Object>) node.get("owner")))
        .containsEntry("id", REQUESTER_ID);

    Assertions.assertThat(uploadCounterValue()).isEqualTo(counterBefore + 1.0);
  }

  @Test
  void givenAnUploadIntoASharedFolderItShouldInheritTheFolderOwner() {
    // Given — folder owned by OTHER_USER_ID, shared with the requester with write access
    String folderId = seedFolder("sharedParent", LOCAL_ROOT, OTHER_COOKIE);
    seedShare(folderId, REQUESTER_ID, ACL.SharePermission.READ_AND_WRITE, OTHER_COOKIE);

    // When
    Response response =
        upload(
            folderId,
            null,
            "content".getBytes(StandardCharsets.UTF_8),
            "shared.txt",
            REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Map<String, Object> json = response.jsonPath().getMap("$");
    String nodeId = (String) json.get("nodeId");

    Map<String, Object> node = getNode(nodeId, REQUESTER_COOKIE);
    Assertions.assertThat(((Map<String, Object>) node.get("owner")))
        .containsEntry("id", OTHER_USER_ID);
  }

  @Test
  void givenNoWritePermissionOnTheParentUploadShouldReturn404AndCreateNoOrphanNode() {
    // Given — folder owned by OTHER_USER_ID, never shared with the requester
    String folderId = seedFolder("privateParent", LOCAL_ROOT, OTHER_COOKIE);
    List<String> childrenBefore = childNodeIds(folderId);

    // When
    Response response =
        upload(
            folderId,
            null,
            "content".getBytes(StandardCharsets.UTF_8),
            "nope.txt",
            REQUESTER_COOKIE);

    // Then — BlobService#uploadFile returns Optional.empty() -> NoSuchElementException -> 404
    Assertions.assertThat(response.getStatusCode()).isEqualTo(404);
    Assertions.assertThat(response.getBody().asString()).isEqualTo("404 Not Found");
    Assertions.assertThat(childNodeIds(folderId)).isEqualTo(childrenBefore);
  }

  @Test
  void givenANonBase64FilenameUploadShouldReturn400() {
    // When — the header is sent RAW (not base64-encoded) by bypassing the base helper's encoding
    Response response =
        RestAssured.given()
            .header("Filename", "not-!-valid-!-base64")
            .header("Cookie", REQUESTER_COOKIE)
            .body("content".getBytes(StandardCharsets.UTF_8))
            .post("/upload");

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(400);
    Assertions.assertThat(response.getBody().asString()).isEqualTo("400 Bad Request");
  }

  @Test
  void givenAnEmptyFilenameUploadShouldReturn400() {
    // When — base64 of the empty string decodes to an empty (blank-after-trim) filename
    Response response =
        upload(null, null, "content".getBytes(StandardCharsets.UTF_8), "", REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(400);
    Assertions.assertThat(response.getBody().asString()).isEqualTo("400 Bad Request");
  }

  @Test
  void givenAFilenameLongerThan1024CharactersUploadShouldReturn400() {
    // Given
    String tooLongName = "a".repeat(1021) + ".txt"; // 1025 chars decoded

    // When
    Response response =
        upload(
            null, null, "content".getBytes(StandardCharsets.UTF_8), tooLongName, REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(400);
    Assertions.assertThat(response.getBody().asString()).isEqualTo("400 Bad Request");
  }

  @Test
  void givenStoragesUploadFailsUploadShouldReturn500AndCreateNoOrphanNode() {
    // Given
    FilesStackTestResource.getStoragesService().setUploadFails(true);
    List<String> childrenBefore = childNodeIds(LOCAL_ROOT);

    // When
    Response response =
        upload(
            null,
            null,
            "content".getBytes(StandardCharsets.UTF_8),
            "willfail.txt",
            REQUESTER_COOKIE);

    // Then — DependencyException falls into ExceptionsHandler's generic else-branch -> 500;
    // the node row created before the storages call is deleted (BlobService#uploadFile's
    // Try#getOrElseThrow rollback).
    Assertions.assertThat(response.getStatusCode()).isEqualTo(500);
    Assertions.assertThat(response.getBody().asString()).isEqualTo("500 Internal Server Error");
    Assertions.assertThat(childNodeIds(LOCAL_ROOT)).isEqualTo(childrenBefore);
  }

  @Test
  void givenStoragesVerifyExistsFailsUploadShouldReturn500AndCreateNoOrphanNode() {
    // Given
    FilesStackTestResource.getStoragesService().setUploadSkipsStore(true);
    List<String> childrenBefore = childNodeIds(LOCAL_ROOT);

    // When
    Response response =
        upload(
            null,
            null,
            "content".getBytes(StandardCharsets.UTF_8),
            "willfail2.txt",
            REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(500);
    Assertions.assertThat(response.getBody().asString()).isEqualTo("500 Internal Server Error");
    Assertions.assertThat(childNodeIds(LOCAL_ROOT)).isEqualTo(childrenBefore);
  }

  @Test
  void givenANameCollisionUploadShouldDedupTheName() {
    // Given
    seedFile(
        "sameName.txt", LOCAL_ROOT, "existing".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);

    // When
    Response response =
        upload(
            null,
            null,
            "content".getBytes(StandardCharsets.UTF_8),
            "sameName.txt",
            REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Map<String, Object> json = response.jsonPath().getMap("$");
    String nodeId = (String) json.get("nodeId");

    Map<String, Object> node = getNode(nodeId, REQUESTER_COOKIE);
    Assertions.assertThat(node)
        .containsEntry("name", "sameName (1)")
        .containsEntry("extension", "txt");
  }
}
