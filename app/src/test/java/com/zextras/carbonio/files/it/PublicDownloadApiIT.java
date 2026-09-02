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
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@code com.zextras.carbonio.files.acceptance.PublicDownloadApiIT} rewritten as an out-of-process
 * {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}: {@code GET
 * /public/download/{nodeId}?node_link_id=...&access_code=...} ({@code
 * PublicBlobResource#downloadPublicFile}). All 8 methods and their assertions are preserved
 * verbatim; only the seeding mechanism (real {@code createLink} API, capturing the server-generated
 * 50-char public id from the {@code url} field — see {@link GetPublicNodeApiIT}'s class javadoc)
 * and the transport changed.
 */
class PublicDownloadApiIT extends AbstractFilesIT {

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
      String nodeId, Integer expiresAt, String accessCode, String ownerCookie) {
    GraphqlCommandBuilder builder =
        GraphqlCommandBuilder.aMutationBuilder("createLink").withString("node_id", nodeId);
    if (expiresAt != null) {
      builder = builder.withInteger("expires_at", expiresAt);
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

  private static Response publicDownloadByNodeId(
      String nodeId, String nodeLinkId, String accessCode, String cookie) {
    StringBuilder path = new StringBuilder("/public/download/").append(nodeId);
    if (nodeLinkId != null) {
      path.append("?node_link_id=").append(nodeLinkId);
      if (accessCode != null) {
        path.append("&access_code=").append(accessCode);
      }
    }
    var request = RestAssured.given();
    if (cookie != null) {
      request = request.header("Cookie", cookie);
    }
    return request.get(path.toString());
  }

  @ParameterizedTest
  @ValueSource(strings = {"fake-token"})
  void
      givenAUserWithOrWithoutCookieAnExistingFileAndAValidPublicLinkAssociatedThePublicDownloadByNodeIdShouldReturnTheBlob(
          String userToken) {
    // Given
    String nodeId =
        seedFile("test.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    String publicId = createLink(nodeId, null, null, OWNER_COOKIE);
    FilesStackTestResource.getStoragesService().reset();

    // When
    Response response =
        publicDownloadByNodeId(nodeId, publicId, null, "ZM_AUTH_TOKEN=" + userToken);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    FilesStackTestResource.getStoragesService().verifyDownloaded(nodeId, 1);
  }

  @Test
  void givenACyrillicFilenameThePublicDownloadShouldReturnAnAsciiRfc8187ContentDisposition() {
    // Regression: the public download Content-Disposition must be RFC 8187 filename* (pure ASCII,
    // %20 not +) so a non-ASCII name is neither mangled nor rejected.
    String nodeId =
        seedFile(
            "Привет.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    String publicId = createLink(nodeId, null, null, OWNER_COOKIE);
    FilesStackTestResource.getStoragesService().reset();

    Response response = publicDownloadByNodeId(nodeId, publicId, null, null);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(response.getHeader("Content-Disposition"))
        .isEqualTo("attachment; filename*=UTF-8''%D0%9F%D1%80%D0%B8%D0%B2%D0%B5%D1%82.txt")
        .doesNotContain("+");
  }

  @Test
  void givenAnExistingFileAndAnExpiredLinkThePublicDownloadByNodeIdShouldReturnA404StatusCode() {
    // Given
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    String publicId = createLink(nodeId, 1, null, OWNER_COOKIE);
    FilesStackTestResource.getStoragesService().reset();

    // When
    Response response = publicDownloadByNodeId(nodeId, publicId, null, null);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(404);
    Assertions.assertThat(response.getBody().asString()).isEqualTo("404 Not Found");
    FilesStackTestResource.getStoragesService().verifyNeverDownloaded();
  }

  @Test
  void givenAnExistingFileAndANotExistingLinkThePublicDownloadByNodeIdShouldReturnA404StatusCode() {
    // Given — the file exists but no link is associated with it
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    FilesStackTestResource.getStoragesService().reset();

    // When
    Response response =
        publicDownloadByNodeId(
            nodeId, "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234ab", null, null);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(404);
    Assertions.assertThat(response.getBody().asString()).isEqualTo("404 Not Found");
    FilesStackTestResource.getStoragesService().verifyNeverDownloaded();
  }

  @Test
  void givenANotExistingNodeThePublicDownloadByNodeIdShouldReturnA404StatusCode() {
    // Given — nothing seeded at all
    // When
    Response response =
        publicDownloadByNodeId(
            "00000000-0000-0000-0000-000000000000",
            "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234ab",
            null,
            null);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(404);
    Assertions.assertThat(response.getBody().asString()).isEqualTo("404 Not Found");
    FilesStackTestResource.getStoragesService().verifyNeverDownloaded();
  }

  @Test
  void
      givenAnExistingFileAndAValidPublicLinkAssociatedAndAConnectionProblemToStoragesTheThePublicDownloadByNodeIdShouldReturnA500StatusCode() {
    // Given
    String nodeId =
        seedFile("test.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    String publicId = createLink(nodeId, null, null, OWNER_COOKIE);
    FilesStackTestResource.getStoragesService().setDownloadFails(true);

    // When
    Response response = publicDownloadByNodeId(nodeId, publicId, null, null);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(500);
    Assertions.assertThat(response.getBody().asString()).isEqualTo("500 Internal Server Error");
  }

  @ParameterizedTest
  @ValueSource(strings = {"fake-token"})
  void
      givenAUserWithOrWithoutCookieAnExistingFileAndAValidPublicLinkAssociatedButNotPassedInUrlThePublicDownloadByNodeIdShouldReturnA404StatusCode(
          String userToken) {
    // Given
    String nodeId =
        seedFile("test.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    createLink(nodeId, null, null, OWNER_COOKIE);
    FilesStackTestResource.getStoragesService().reset();

    // When — the query string does not carry node_link_id at all
    Response response = publicDownloadByNodeId(nodeId, null, null, "ZM_AUTH_TOKEN=" + userToken);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(404);
  }

  @Test
  void
      givenAnExistingFileWithAccessCodeAndAValidLinkThePublicDownloadByNodeIdWithoutAccessCodeShouldReturnA404StatusCode() {
    // Given
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    String publicId = createLink(nodeId, null, "accesscode", OWNER_COOKIE);

    // When
    Response response = publicDownloadByNodeId(nodeId, publicId, null, null);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(404);
  }

  @Test
  void
      givenAnExistingFileWithAccessCodeAndAValidLinkThePublicDownloadByNodeIdWithAccessCodeShouldReturnTheBlob() {
    // Given
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    String publicId = createLink(nodeId, null, "accesscode", OWNER_COOKIE);
    FilesStackTestResource.getStoragesService().reset();

    // When
    Response response = publicDownloadByNodeId(nodeId, publicId, "accesscode", null);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
  }
}
