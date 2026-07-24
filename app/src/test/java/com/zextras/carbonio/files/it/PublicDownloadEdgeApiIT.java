// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code com.zextras.carbonio.files.acceptance.PublicDownloadEdgeApiIT} rewritten as an
 * out-of-process {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}. Covers the two
 * "check" variants of the public download surface plus the query-string-order routing quirk on
 * {@code /public/download/{id}/check}:
 *
 * <ul>
 *   <li>{@code checkDownloadPublicFile} ({@code GET /public/download/{id}/check?node_link_id=...})
 *       -&gt; 204 on success, 404 on any not-accessible condition.
 *   <li>{@code checkDownloadPublicMultiple} ({@code POST /public/download-multiple/check}, raw
 *       JSON body) -&gt; 204/404/500.
 *   <li>{@code PublicBlobResource#requireNodeLinkIdFirst} hard-codes {@code node_link_id}
 *       immediately after {@code ?} on {@code checkDownloadPublicFile}; any other query order
 *       (e.g. {@code access_code} first) is rejected with a 404 before {@code BlobService} is ever
 *       consulted — ported verbatim as a raw hand-built query string (not RestAssured's {@code
 *       .queryParam(...)}, whose insertion order this test must control byte-exactly).
 * </ul>
 *
 * All 8 methods and their assertions are preserved verbatim; only the seeding mechanism (real
 * {@code seedFolder}/{@code seedFile}/{@code createLink} API, capturing server-generated ids) and
 * the transport changed.
 */
class PublicDownloadEdgeApiIT extends AbstractFilesIT {

  private static final String OWNER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OWNER_COOKIE = "ZM_AUTH_TOKEN=fake-token";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", OWNER_ID);
  }

  /** Creates a link via the real mutation and returns its {@code public_id} (last 50 chars of the url). */
  private static String createLink(String nodeId, String accessCode, String ownerCookie) {
    GraphqlCommandBuilder builder =
        GraphqlCommandBuilder.aMutationBuilder("createLink").withString("node_id", nodeId);
    if (accessCode != null) {
      builder = builder.withString("access_code", accessCode);
    }
    String bodyPayload = builder.withWantedResultFormat("{ url }").build();
    Response response = graphql(bodyPayload, ownerCookie);
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    String url =
        (String) TestUtils.jsonResponseToMap(response.getBody().asString(), "createLink").get("url");
    return url.substring(url.length() - 50);
  }

  private static Response checkPublicSingle(String nodeId, String nodeLinkId, String accessCode) {
    StringBuilder path =
        new StringBuilder("/public/download/").append(nodeId).append("/check?node_link_id=").append(nodeLinkId);
    if (accessCode != null) {
      path.append("&access_code=").append(accessCode);
    }
    return RestAssured.given().get(path.toString());
  }

  private static Response checkPublicSingleRawQuery(String rawPathWithQuery) {
    return RestAssured.given().get(rawPathWithQuery);
  }

  private static Response checkPublicMultipleJson(
      List<String> nodeIds, String nodeLinkId, String accessCode) {
    StringBuilder json = new StringBuilder("{\"nodeIds\":[");
    json.append(nodeIds.stream().map(id -> "\"" + id + "\"").collect(Collectors.joining(",")));
    json.append("],\"nodeLinkId\":\"").append(nodeLinkId).append("\"");
    if (accessCode != null) {
      json.append(",\"accessCode\":\"").append(accessCode).append("\"");
    }
    json.append("}");
    return RestAssured.given()
        .contentType("application/json")
        .body(json.toString())
        .post("/public/download-multiple/check");
  }

  // --- checkDownloadPublicFile (GET /public/download/{id}/check?node_link_id=...) ------------

  @Test
  void givenAnAccessibleNodeTheCheckDownloadPublicFileApiShouldReturnA204StatusCode() {
    // Given
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    String publicId = createLink(nodeId, null, OWNER_COOKIE);

    // When
    Response response = checkPublicSingle(nodeId, publicId, null);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(204);
  }

  @Test
  void givenANotExistingLinkTheCheckDownloadPublicFileApiShouldReturnA404StatusCode() {
    // When
    Response response =
        checkPublicSingle(
            "00000000-0000-0000-0000-000000000302",
            "nonexistentlink0000000000000000000000000000000ab",
            null);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(404);
  }

  @Test
  void givenAProtectedLinkWithoutTheAccessCodeTheCheckDownloadPublicFileApiShouldReturnA404StatusCode() {
    // Given
    String nodeId =
        seedFile(
            "protected.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    String publicId = createLink(nodeId, "secretcode123", OWNER_COOKIE);

    // When
    Response response = checkPublicSingle(nodeId, publicId, null);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(404);
  }

  // --- checkDownloadPublicMultiple (POST /public/download-multiple/check, JSON body) ----------

  @Test
  void givenAccessibleNodesTheCheckDownloadPublicMultipleApiShouldReturnA204StatusCode() {
    // Given
    String folderId = seedFolder("check-folder", LOCAL_ROOT, OWNER_COOKIE);
    String fileId =
        seedFile("file.txt", folderId, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    String publicId = createLink(folderId, null, OWNER_COOKIE);

    // When
    Response response = checkPublicMultipleJson(List.of(fileId), publicId, null);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(204);
  }

  @Test
  void givenANotExistingLinkTheCheckDownloadPublicMultipleApiShouldReturnA404StatusCode() {
    // When
    Response response =
        checkPublicMultipleJson(
            List.of("00000000-0000-0000-0000-000000000402"),
            "nonexistentlink0000000000000000000000000000000ab",
            null);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(404);
  }

  /**
   * FINDING (real production behaviour, ported verbatim): {@code
   * PublicBlobResource#checkDownloadPublicMultiple}'s JSON-parse-failure branch wraps the caught
   * {@code JsonProcessingException} as the CAUSE of the {@code IllegalArgumentException} it fires.
   * {@code BlobExceptionMapper} unconditionally unwraps to {@code exception.getCause()} before
   * classifying the status code, so it reclassifies based on the INNER {@code
   * JsonProcessingException} — which matches no dedicated branch and falls to the generic 500 —
   * instead of the outer, intended {@code IllegalArgumentException} -&gt; 400. Not fixed here
   * (src/main is out of scope) — asserted as the real behaviour.
   */
  @Test
  void givenAMalformedJsonBodyTheCheckDownloadPublicMultipleApiShouldReturnA500StatusCode() {
    // When
    Response response =
        RestAssured.given()
            .contentType("application/json")
            .body("{not-valid-json")
            .post("/public/download-multiple/check");

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(500);
  }

  // --- Query-string parameter ORDER edge on checkDownloadPublicFile ---------------------------

  @Test
  void givenNodeLinkIdBeforeAccessCodeTheCheckDownloadPublicFileApiShouldReturnA204StatusCode() {
    // Given
    String nodeId =
        seedFile("ordered.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    String publicId = createLink(nodeId, "secretcode123", OWNER_COOKIE);

    // When
    String correctOrderUrl =
        "/public/download/" + nodeId + "/check?node_link_id=" + publicId + "&access_code=secretcode123";
    Response response = checkPublicSingleRawQuery(correctOrderUrl);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(204);
  }

  @Test
  void givenAccessCodeBeforeNodeLinkIdTheCheckDownloadPublicFileApiShouldReturnA404StatusCode() {
    // Given — same node/link/access-code as the previous test, only the query-parameter ORDER
    // differs: access_code first, node_link_id second. requireNodeLinkIdFirst rejects this shape
    // with a 404 before PublicBlobResource ever consults BlobService.
    String nodeId =
        seedFile(
            "misordered.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    String publicId = createLink(nodeId, "secretcode123", OWNER_COOKIE);

    // When
    String wrongOrderUrl =
        "/public/download/" + nodeId + "/check?access_code=secretcode123&node_link_id=" + publicId;
    Response response = checkPublicSingleRawQuery(wrongOrderUrl);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(404);
  }
}
