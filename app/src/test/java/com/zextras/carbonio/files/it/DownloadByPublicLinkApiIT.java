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
import java.sql.SQLException;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * {@code com.zextras.carbonio.files.acceptance.DownloadByPublicLinkApiIT} rewritten as an
 * out-of-process {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}: {@code GET /link/{id}}
 * and its alias {@code GET /public/link/download/{id}} — both route to the same {@code
 * PublicBlobResource#doDownloadByPublicLink}. All 7 methods and their assertions are preserved
 * verbatim; only the seeding mechanism and transport changed.
 *
 * <p><b>The 8/32/50-char {@code publicLinkId} variants need the raw-JDBC escape hatch (D1 rule
 * 4).</b> The real {@code createLink} mutation always generates a random 50-char {@code publicId}
 * (see {@link GetPublicNodeApiIT}'s class javadoc), so the two shorter legacy-format lengths this
 * class deliberately covers (an 8-char and a 32-char id) are API-observable-but-not-API-creatable
 * pre-states — seeded via {@link #seedLinkRawJdbc}. The singular (non-parameterized) methods below
 * seed their link through the real {@code createLink} API where a 50-char id is all that's needed.
 *
 * <p>Redirects are disabled on every request ({@code .redirects().follow(false)}) so the 307 case
 * can assert the {@code Location} header directly rather than transparently following it.
 */
class DownloadByPublicLinkApiIT extends AbstractFilesIT {

  private static final String OWNER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OWNER_COOKIE = "ZM_AUTH_TOKEN=fake-token";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", OWNER_ID);
  }

  private static Response publicLinkDownload(
      String pathPrefix, String publicLinkId, String cookie) {
    var request = RestAssured.given().redirects().follow(false);
    if (cookie != null) {
      request = request.header("Cookie", cookie);
    }
    return request.get(pathPrefix + publicLinkId);
  }

  /**
   * Creates a link via the real mutation and returns its {@code public_id} (last 50 chars of the
   * url).
   */
  private static String createLink(String nodeId, Integer expiresAt, String ownerCookie) {
    GraphqlCommandBuilder builder =
        GraphqlCommandBuilder.aMutationBuilder("createLink").withString("node_id", nodeId);
    if (expiresAt != null) {
      builder = builder.withInteger("expires_at", expiresAt);
    }
    String bodyPayload = builder.withWantedResultFormat("{ url }").build();
    Response response = graphql(bodyPayload, ownerCookie);
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    String url =
        (String)
            TestUtils.jsonResponseToMap(response.getBody().asString(), "createLink").get("url");
    return url.substring(url.length() - 50);
  }

  @ParameterizedTest
  @CsvSource({
    "abcd1234,/public/link/download/,",
    "abcd1234abcd1234abcd1234abcd1234,/public/link/download/,",
    "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234ab,/public/link/download/,",
    "abcd1234,/link/,",
    "abcd1234abcd1234abcd1234abcd1234,/link/,",
    "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234ab,/link/,",
    "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234ab,/public/link/download/,fake-token",
    "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234ab,/link/,fake-token",
  })
  void
      givenAUserWithOrWithoutCookieAnExistingFileAndAnExistingPublicLinkAssociatedTheDownloadByPublicLinkShouldReturnTheBlob(
          String publicLinkId, String publicLinkEndpoint, String userToken) throws SQLException {
    // Given
    String nodeId =
        seedFile("test.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    seedLinkRawJdbc(UUID.randomUUID().toString(), nodeId, publicLinkId, null, null, null);
    // seedFile's upload itself performs one storages verify-blob-exists GET /download; reset so
    // the assertion below covers only the download-by-link attempt under test.
    FilesStackTestResource.getStoragesService().reset();
    String cookie = userToken == null ? null : "ZM_AUTH_TOKEN=" + userToken;

    // When
    Response response = publicLinkDownload(publicLinkEndpoint, publicLinkId, cookie);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    FilesStackTestResource.getStoragesService().verifyDownloaded(nodeId, 1);
  }

  @ParameterizedTest
  @CsvSource({
    "abcd1234,/public/link/download/,",
    "abcd1234abcd1234abcd1234abcd1234,/public/link/download/,",
    "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234ab,/public/link/download/,",
    "abcd1234,/link/,",
    "abcd1234abcd1234abcd1234abcd1234,/link/,",
    "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234ab,/link/,",
    "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234ab,/public/link/download/,fake-token",
    "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234ab,/link/,fake-token",
  })
  void
      givenAUserWithOrWithoutCookieAnExistingFileAndAnExistingPublicLinkAssociatedWithAccessCodeTheDownloadByPublicLinkShouldRedirect(
          String publicLinkId, String publicLinkEndpoint, String userToken) throws SQLException {
    // Given
    String nodeId =
        seedFile("test.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    seedLinkRawJdbc(UUID.randomUUID().toString(), nodeId, publicLinkId, null, null, "test");
    String cookie = userToken == null ? null : "ZM_AUTH_TOKEN=" + userToken;

    // When
    Response response = publicLinkDownload(publicLinkEndpoint, publicLinkId, cookie);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(307);
    Assertions.assertThat(response.getHeader("location"))
        .isEqualTo("/files/public/link/access/" + publicLinkId);
  }

  @Test
  void givenAnExistingFileAndAnExpiredLinkTheDownloadByPublicLinkShouldReturnA404StatusCode() {
    // Given
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    String publicId = createLink(nodeId, 1, OWNER_COOKIE);
    FilesStackTestResource.getStoragesService().reset();

    // When
    Response response = publicLinkDownload("/public/link/download/", publicId, null);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(404);
    Assertions.assertThat(response.getBody().asString()).isEqualTo("404 Not Found");
    FilesStackTestResource.getStoragesService().verifyNeverDownloaded();
  }

  @Test
  void givenANotExistingLinkTheDownloadByPublicLinkShouldReturnA404StatusCode()
      throws SQLException {
    // Given — a real link exists, but with a DIFFERENT public id than the one requested
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    seedLinkRawJdbc(UUID.randomUUID().toString(), nodeId, "000000", null, null, null);
    FilesStackTestResource.getStoragesService().reset();

    // When
    Response response = publicLinkDownload("/public/link/download/", "1234abcd", null);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(404);
    Assertions.assertThat(response.getBody().asString()).isEqualTo("404 Not Found");
    FilesStackTestResource.getStoragesService().verifyNeverDownloaded();
  }

  @Test
  void givenANotExistingNodeTheDownloadByPublicLinkShouldReturnA404StatusCode() {
    // Given — nothing seeded at all
    // When
    Response response = publicLinkDownload("/public/link/download/", "1234abcd", null);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(404);
    Assertions.assertThat(response.getBody().asString()).isEqualTo("404 Not Found");
    FilesStackTestResource.getStoragesService().verifyNeverDownloaded();
  }

  @Test
  void
      givenAnExistingFileAndAValidPublicLinkAssociatedAndAConnectionProblemToStoragesTheTheDownloadByPublicLinkShouldReturnA500StatusCode() {
    // Given
    String nodeId =
        seedFile("test.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    String publicId = createLink(nodeId, null, OWNER_COOKIE);
    FilesStackTestResource.getStoragesService().setDownloadFails(true);

    // When
    Response response = publicLinkDownload("/public/link/download/", publicId, null);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(500);
    Assertions.assertThat(response.getBody().asString()).isEqualTo("500 Internal Server Error");
  }

  @ParameterizedTest
  @CsvSource({
    "abcd1234,/public/link/download/,",
    "abcd1234abcd1234abcd1234abcd1234,/public/link/download/,",
    "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234ab,/public/link/download/,",
    "abcd1234,/link/,",
    "abcd1234abcd1234abcd1234abcd1234,/link/,",
    "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234ab,/link/,",
    "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234ab,/public/link/download/,fake-token",
    "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234ab,/link/,fake-token",
  })
  void
      givenAUserWithOrWithoutCookieAnExistingTrashedFileAndAnExistingPublicLinkAssociatedTheDownloadByPublicLinkShouldReturn404(
          String publicLinkId, String publicLinkEndpoint, String userToken) throws SQLException {
    // Given
    String nodeId =
        seedFile("test.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    seedLinkRawJdbc(UUID.randomUUID().toString(), nodeId, publicLinkId, null, null, null);
    seedTrashed(nodeId, OWNER_COOKIE);
    String cookie = userToken == null ? null : "ZM_AUTH_TOKEN=" + userToken;

    // When
    Response response = publicLinkDownload(publicLinkEndpoint, publicLinkId, cookie);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(404);
  }
}
