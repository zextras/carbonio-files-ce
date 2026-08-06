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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code com.zextras.carbonio.files.acceptance.PublicDownloadMultipleApiIT} rewritten as an
 * out-of-process {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}: {@code POST
 * /public/download-multiple} (form-encoded, ZIP download) — {@code
 * PublicBlobResource#downloadPublicMultiple}. All 8 methods and their assertions are preserved
 * verbatim; only the seeding mechanism (real {@code seedFolder}/{@code seedFile}/{@code createLink}
 * API, capturing server-generated ids) and the transport changed. Content bytes are now real,
 * storages-backed uploads (via {@code seedFile}) rather than the seam's {@code storagesServesBlob}
 * stub registration — the fake serves whatever content was actually uploaded.
 */
class PublicDownloadMultipleApiIT extends AbstractFilesIT {

  private static final String OWNER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OWNER_COOKIE = "ZM_AUTH_TOKEN=fake-token";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", OWNER_ID);
  }

  /**
   * Creates a link via the real mutation and returns its {@code public_id} (last 50 chars of the
   * url).
   */
  private static String createLink(
      String nodeId, String description, String accessCode, String ownerCookie) {
    GraphqlCommandBuilder builder =
        GraphqlCommandBuilder.aMutationBuilder("createLink").withString("node_id", nodeId);
    if (description != null) {
      builder = builder.withString("description", description);
    }
    if (accessCode != null) {
      builder = builder.withString("access_code", accessCode);
    }
    String bodyPayload = builder.withWantedResultFormat("{ url }").build();
    Response response = graphql(bodyPayload, ownerCookie);
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    String url =
        (String)
            TestUtils.jsonResponseToMap(response.getBody().asString(), "createLink").get("url");
    return url.substring(url.length() - 50);
  }

  private static Response publicDownloadMultiple(
      List<String> nodeIds, String nodeLinkId, String accessCode) {
    String jsonArray =
        "[" + nodeIds.stream().map(id -> "\"" + id + "\"").collect(Collectors.joining(",")) + "]";
    StringBuilder body =
        new StringBuilder("nodeIds=").append(URLEncoder.encode(jsonArray, StandardCharsets.UTF_8));
    if (nodeLinkId != null) {
      body.append("&nodeLinkId=").append(nodeLinkId);
    }
    if (accessCode != null) {
      body.append("&accessCode=").append(accessCode);
    }
    return RestAssured.given()
        .contentType("application/x-www-form-urlencoded")
        .body(body.toString())
        .post("/public/download-multiple");
  }

  @Test
  void givenMultipleFilesWithPublicLinkTheDownloadMultipleShouldReturnZipWith200() {
    // Given
    String folderId = seedFolder("test-folder", LOCAL_ROOT, OWNER_COOKIE);
    String fileId1 =
        seedFile("file1.txt", folderId, "one".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    String fileId2 =
        seedFile("file2.pdf", folderId, "two".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    String fileId3 =
        seedFile("file3.jpg", folderId, "three".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    String publicId = createLink(folderId, "Public folder link", null, OWNER_COOKIE);

    // When
    Response response = publicDownloadMultiple(List.of(fileId1, fileId2, fileId3), publicId, null);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(response.getHeader("content-type")).contains("application/zip");
    Assertions.assertThat(response.getHeader("content-disposition"))
        .contains("attachment")
        .contains("Files.zip");
    FilesStackTestResource.getStoragesService().verifyDownloaded(fileId1, 1);
  }

  @Test
  void givenPublicLinkWithAccessCodeTheDownloadMultipleShouldReturnZipWith200() {
    // Given
    String folderId = seedFolder("protected-folder", LOCAL_ROOT, OWNER_COOKIE);
    String fileId1 =
        seedFile("file1.txt", folderId, "one".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    String fileId2 =
        seedFile("file2.txt", folderId, "two".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    String publicId = createLink(folderId, "Protected link", "secret123456", OWNER_COOKIE);

    // When
    Response response = publicDownloadMultiple(List.of(fileId1, fileId2), publicId, "secret123456");

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
  }

  @Test
  void givenInvalidPublicLinkTheDownloadMultipleShouldReturn404() {
    // Given
    String fileId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);

    // When
    Response response = publicDownloadMultiple(List.of(fileId), UUID.randomUUID().toString(), null);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(404);
  }

  @Test
  void givenMissingNodeLinkIdTheDownloadMultipleShouldReturn400() {
    // When
    Response response =
        publicDownloadMultiple(List.of("00000000-0000-0000-0000-000000000501"), null, null);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(400);
  }

  @Test
  void givenEmptyNodeListTheDownloadMultipleShouldReturn404() {
    // When
    Response response = publicDownloadMultiple(List.of(), UUID.randomUUID().toString(), null);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(404);
  }

  @Test
  void givenMixedNodesWithPublicLinkTheDownloadMultipleShouldReturnZipWith200() {
    // Given
    String parentFolderId = seedFolder("parent-folder", LOCAL_ROOT, OWNER_COOKIE);
    String subFolderId = seedFolder("sub-folder", parentFolderId, OWNER_COOKIE);
    String fileId1 =
        seedFile("file1.txt", parentFolderId, "one".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    seedFile("file2.txt", subFolderId, "two".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    String publicId = createLink(parentFolderId, null, null, OWNER_COOKIE);

    // When
    Response response = publicDownloadMultiple(List.of(fileId1, subFolderId), publicId, null);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
  }

  @Test
  void givenMissingAccessCodeForProtectedLinkTheDownloadMultipleShouldReturn404() {
    // Given
    String folderId = seedFolder("protected-folder", LOCAL_ROOT, OWNER_COOKIE);
    String fileId =
        seedFile("file.txt", folderId, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    String publicId = createLink(folderId, null, "required123", OWNER_COOKIE);

    // When
    Response response = publicDownloadMultiple(List.of(fileId), publicId, null);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(404);
  }

  @Test
  void givenEmptyFolderWithPublicLinkTheDownloadMultipleShouldReturn200() {
    // Given
    String folderId = seedFolder("empty-folder", LOCAL_ROOT, OWNER_COOKIE);
    String publicId = createLink(folderId, "Empty folder link", null, OWNER_COOKIE);

    // When
    Response response = publicDownloadMultiple(List.of(folderId), publicId, null);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
  }
}
