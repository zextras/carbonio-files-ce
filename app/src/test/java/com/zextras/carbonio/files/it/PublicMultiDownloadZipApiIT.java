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
import java.util.Set;
import java.util.stream.Collectors;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code com.zextras.carbonio.files.acceptance.PublicMultiDownloadZipApiIT} rewritten as an
 * out-of-process {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}. Public-link-specific
 * ZIP-building branches of {@code rest.services.BlobService} that are NOT reachable through the
 * authenticated path (see {@link MultiDownloadZipApiIT}, which covers the shared {@code
 * createZip}/{@code addNodeToZip}/{@code getUniqueNameForZip} internals via the authenticated
 * route). {@link PublicDownloadMultipleApiIT} already covers the public happy-path/basic-
 * validation surface; this class targets branches unique to {@code
 * checkDownloadPublicMultiple}/{@code downloadPublicMultiple}'s own accessChecker (link-validity +
 * not-trashed, instead of permission-based) and to the public-only {@code requesterId == null}
 * branch of {@code checkDownloadMultipleInternal}'s LOCAL_ROOT-alias handling.
 *
 * <p>All 4 methods and their assertions are preserved verbatim; only the seeding mechanism (real
 * {@code seedFolder}/{@code seedFile}/{@code createLink}/{@code seedTrashed} API, capturing
 * server-generated ids) and the transport changed. {@link #zipEntryNames} (real {@code
 * ZipInputStream} parsing) replaces the original's conditional {@code
 * assertBodyContainsWhenObservable} substring-matching — {@code @QuarkusIntegrationTest} drives
 * real HTTP end to end, so the body is always fully drained (see {@link
 * MultiDownloadZipApiIT}'s class javadoc for the same observation on the authenticated route).
 *
 * <p>The trashed-child scenario now trashes the node via the REAL {@code trashNodes} mutation
 * ({@link #seedTrashed}) rather than the seam's populator, which is an even more faithful
 * reproduction of the "trashing reparents to TRASH_ROOT, so a trashed child is naturally excluded
 * from {@code getChildrenIds(originalFolder)}'s result" behaviour the original class javadoc
 * documented.
 */
class PublicMultiDownloadZipApiIT extends AbstractFilesIT {

  private static final String OWNER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OWNER_COOKIE = "ZM_AUTH_TOKEN=fake-token";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", OWNER_ID);
  }

  /** Creates a link via the real mutation and returns its {@code public_id} (last 50 chars of the url). */
  private static String createLink(String nodeId, String ownerCookie) {
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("createLink")
            .withString("node_id", nodeId)
            .withWantedResultFormat("{ url }")
            .build();
    Response response = graphql(bodyPayload, ownerCookie);
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    String url =
        (String) TestUtils.jsonResponseToMap(response.getBody().asString(), "createLink").get("url");
    return url.substring(url.length() - 50);
  }

  private static Response checkPublicMultipleJson(List<String> nodeIds, String nodeLinkId) {
    String jsonArray =
        "[" + nodeIds.stream().map(id -> "\"" + id + "\"").collect(Collectors.joining(",")) + "]";
    String jsonBody = "{\"nodeIds\":" + jsonArray + ",\"nodeLinkId\":\"" + nodeLinkId + "\"}";
    return RestAssured.given()
        .contentType("application/json")
        .body(jsonBody)
        .post("/public/download-multiple/check");
  }

  private static Response downloadPublicMultiple(List<String> nodeIds, String nodeLinkId) {
    String jsonArray =
        "[" + nodeIds.stream().map(id -> "\"" + id + "\"").collect(Collectors.joining(",")) + "]";
    String requestBody =
        "nodeIds=" + URLEncoder.encode(jsonArray, StandardCharsets.UTF_8) + "&nodeLinkId=" + nodeLinkId;
    return RestAssured.given()
        .contentType("application/x-www-form-urlencoded")
        .body(requestBody)
        .post("/public/download-multiple");
  }

  /**
   * {@code checkDownloadPublicMultiple}'s accessChecker is {@code
   * linkRepository.isLinkValidForNode(nodeLinkId, node) && !trashed} (link-scope validity), a
   * DIFFERENT check than the authenticated path's permission-based one (valid iff the node IS the
   * link's node or one of its ancestors). A node entirely outside the linked folder's tree fails
   * it and is silently skipped — same {@code continue} branch as the authenticated path, but a
   * different reason.
   */
  @Test
  void givenNodeOutsideLinkScopeMixedWithValidNodeCheckDownloadPublicMultipleShouldReturn204SkippingIt() {
    // Given
    String folderId = seedFolder("linked-folder", LOCAL_ROOT, OWNER_COOKIE);
    String insideFileId =
        seedFile("inside.txt", folderId, "in".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    // A completely separate top-level file, outside the linked folder's ancestor chain.
    String outsideFileId =
        seedFile("outside.txt", LOCAL_ROOT, "out".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    String publicId = createLink(folderId, OWNER_COOKIE);

    // When
    Response response = checkPublicMultipleJson(List.of(insideFileId, outsideFileId), publicId);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(204);
  }

  /**
   * Distinguishes {@code checkDownloadMultipleInternal}'s own {@code nodeIds.isEmpty()} -&gt;
   * {@code Optional.empty()} -&gt; 404 branch from the "link not found" -&gt; {@code
   * Optional.empty()} -&gt; 404 branch already exercised by {@code
   * PublicDownloadMultipleApiIT#givenEmptyNodeListTheDownloadMultipleShouldReturn404} (which uses
   * a random, never-created public id, so the request never reaches {@code
   * checkDownloadMultipleInternal} at all). Here the link genuinely exists and resolves, so the
   * empty-list check inside {@code checkDownloadMultipleInternal} is what actually fires.
   */
  @Test
  void givenEmptyNodeIdsWithAValidPublicLinkCheckDownloadPublicMultipleShouldReturn404() {
    // Given
    String folderId = seedFolder("real-folder", LOCAL_ROOT, OWNER_COOKIE);
    String publicId = createLink(folderId, OWNER_COOKIE);

    // When
    Response response = checkPublicMultipleJson(List.of(), publicId);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(404);
  }

  /**
   * The LOCAL_ROOT-alias branch's {@code if (requesterId == null) return Optional.empty();} is
   * UNREACHABLE for the authenticated path (requester is never null there) — it only exists for
   * the public path, where {@code checkDownloadPublicMultiple} always passes {@code null} as the
   * requesterId. This is the only way to reach it.
   */
  @Test
  void givenLocalRootAliasWithAValidPublicLinkCheckDownloadPublicMultipleShouldReturn404() {
    // Given
    String folderId = seedFolder("another-folder", LOCAL_ROOT, OWNER_COOKIE);
    String publicId = createLink(folderId, OWNER_COOKIE);

    // When
    Response response = checkPublicMultipleJson(List.of(LOCAL_ROOT), publicId);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(404);
  }

  @Test
  void givenTrashedChildInsideAPubliclyLinkedFolderDownloadPublicMultipleOmitsItFromTheZip()
      throws Exception {
    // Given
    String folderId = seedFolder("public-folder", LOCAL_ROOT, OWNER_COOKIE);
    String keptFileId =
        seedFile("kept.txt", folderId, "kept-content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    String trashedFileId =
        seedFile(
            "trashed.txt", folderId, "trashed-content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    String publicId = createLink(folderId, OWNER_COOKIE);
    seedTrashed(trashedFileId, OWNER_COOKIE);

    // When
    Response response = downloadPublicMultiple(List.of(folderId), publicId);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    FilesStackTestResource.getStoragesService().verifyDownloaded(keptFileId, 1);
    // The requested node is the folder itself (top-level), so its own name becomes the ZIP's
    // path prefix — entries are nested under "public-folder/", not bare filenames.
    Set<String> entries = zipEntryNames(response.getBody().asByteArray());
    Assertions.assertThat(entries).contains("public-folder/kept.txt");
    Assertions.assertThat(entries).noneMatch(name -> name.contains("trashed.txt"));
  }
}
