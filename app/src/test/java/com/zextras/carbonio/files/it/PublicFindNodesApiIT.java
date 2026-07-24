// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code com.zextras.carbonio.files.acceptance.PublicFindNodesApiIT} rewritten as an
 * out-of-process {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}. All 12 methods and
 * their assertions are preserved verbatim; only the seeding mechanism (real {@code
 * seedFolder}/{@code seedFile}/{@code createLink} API, capturing server-generated ids — {@link
 * #createFolderTree} returns them as an array so id AND name assertions both stay literal) and the
 * transport ({@link #publicFindNodes} posts unauthenticated via {@link #publicGraphql}) changed.
 *
 * <p><b>The forged page-token's embedded {@code folderId} is a fixed, unrelated literal by
 * design.</b> {@link #forgeTamperedPageTokenMissingSignature()}/{@link
 * #forgeTamperedPageTokenWithWrongSignature(String)} always embed the SAME hard-coded {@code
 * "77777777-7777-7777-7777-777777777777"} (ported verbatim from the deleted seam's {@code
 * QuarkusTestDataAccess}) regardless of what real data exists — the server rejects the token at
 * SIGNATURE-verification time, before that embedded value is ever consulted. The two
 * tampered-token tests still seed a small decoy "not public folder" tree for scenario fidelity
 * (an unrelated, non-public folder a hacker might try to pivot into), even though it is not
 * required for the assertion to hold.
 *
 * <p><b>Access codes must satisfy the schema's 10-254 char bound.</b> {@code createLink}'s {@code
 * access_code} argument is validated ({@code schema.graphql}: "must be equal or longer than 10 and
 * shorter than 255 characters"); the original's in-JVM backdoor bypassed this, so the access code
 * used here ({@code "fakeaccesscode"}) is a compliant-length replacement for the original's
 * shorter {@code "fakecode"} literal — same scenario intent (a link protected by an access code),
 * just a real-API-creatable value.
 */
class PublicFindNodesApiIT extends AbstractFilesIT {

  private static final String OWNER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OWNER_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String ACCESS_CODE = "fakeaccesscode";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", OWNER_ID);
  }

  /**
   * Seeds a "public folder" containing one child folder and four child files, named so the
   * default (category then alphabetical) sort order matches the original fixture's expectations.
   * Returns the captured ids as {@code [publicFolderId, folderChildId, file2Id, file3Id, file4Id,
   * file5Id]}.
   */
  private String[] createFolderTree(String ownerCookie) {
    String publicFolderId = seedFolder("public folder", LOCAL_ROOT, ownerCookie);
    String folderChildId = seedFolder("folder child", publicFolderId, ownerCookie);
    byte[] content = "x".getBytes(StandardCharsets.UTF_8);
    String file2Id = seedFile("file child id 2.txt", publicFolderId, content, ownerCookie);
    String file3Id = seedFile("file child id 3.txt", publicFolderId, content, ownerCookie);
    String file4Id = seedFile("file child id 4.txt", publicFolderId, content, ownerCookie);
    String file5Id = seedFile("file child id 5.txt", publicFolderId, content, ownerCookie);
    return new String[] {publicFolderId, folderChildId, file2Id, file3Id, file4Id, file5Id};
  }

  /** Creates a link via the real mutation and returns its {@code public_id} (last 50 chars of the url). */
  private String createLink(String nodeId, Integer expiresAt, String accessCode, String ownerCookie) {
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
        (String) TestUtils.jsonResponseToMap(response.getBody().asString(), "createLink").get("url");
    return url.substring(url.length() - 50);
  }

  private static Response publicFindNodes(
      String folderId, Integer limit, String nodeLinkId, String accessCode, String pageToken) {
    GraphqlCommandBuilder builder = GraphqlCommandBuilder.aQueryBuilder("findNodes");
    if (folderId != null) {
      builder = builder.withString("folder_id", folderId);
    }
    if (limit != null) {
      builder = builder.withInteger("limit", limit);
    }
    if (nodeLinkId != null) {
      builder = builder.withString("node_link_id", nodeLinkId);
    }
    if (accessCode != null) {
      builder = builder.withString("access_code", accessCode);
    }
    if (pageToken != null) {
      builder = builder.withString("page_token", pageToken);
    }
    String bodyPayload = builder.withWantedResultFormat("{ nodes { id name }, page_token }").build();
    return publicGraphql(bodyPayload);
  }

  @DisplayName(
      """
    Given an existing folder with five nodes inside, a valid public link associated, a limit of
    three elements per page: the findNodes should return the first page containing three nodes
    ordered by category and alphabetically and the page_token for the next page""")
  @Test
  void givenAnExistingFolderAndAValidPublicLinkTheFindNodesShouldReturnTheFirstPage() {
    // Given
    String[] tree = createFolderTree(OWNER_COOKIE);
    String publicId = createLink(tree[0], null, null, OWNER_COOKIE);

    // When
    Response response = publicFindNodes(tree[0], 3, publicId, null, null);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Map<String, Object> page = TestUtils.jsonResponseToMap(response.getBody().asString(), "findNodes");
    Assertions.assertThat(page.get("page_token")).isNotNull();

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> nodes = (List<Map<String, Object>>) page.get("nodes");
    Assertions.assertThat(nodes).hasSize(3);
    Assertions.assertThat(nodes.get(0)).containsEntry("id", tree[1]).containsEntry("name", "folder child");
    Assertions.assertThat(nodes.get(1)).containsEntry("id", tree[2]).containsEntry("name", "file child id 2");
    Assertions.assertThat(nodes.get(2)).containsEntry("id", tree[3]).containsEntry("name", "file child id 3");
  }

  @DisplayName(
      """
    Given an existing folder with five nodes inside, a valid public link associated, a limit of
    three elements per page and a page token for the second page: the findNodes should return the
    second page containing two nodes and a null page_token""")
  @Test
  void givenAnExistingFolderAndAValidLinkTheFindNodesShouldReturnTheSecondPage() {
    // Given
    String[] tree = createFolderTree(OWNER_COOKIE);
    String publicId = createLink(tree[0], null, null, OWNER_COOKIE);

    // Start request first page of the folder content
    Response firstResponse = publicFindNodes(tree[0], 3, publicId, null, null);
    Assertions.assertThat(firstResponse.getStatusCode()).isEqualTo(200);
    String pageToken =
        (String)
            TestUtils.jsonResponseToMap(firstResponse.getBody().asString(), "findNodes").get("page_token");
    // End request first page of the folder content

    // When
    Response response = publicFindNodes(tree[0], 3, publicId, null, pageToken);

    // Then
    Map<String, Object> page = TestUtils.jsonResponseToMap(response.getBody().asString(), "findNodes");
    Assertions.assertThat(page.get("page_token")).isNull();

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> nodes = (List<Map<String, Object>>) page.get("nodes");
    Assertions.assertThat(nodes).hasSize(2);
    Assertions.assertThat(nodes.get(0)).containsEntry("id", tree[4]).containsEntry("name", "file child id 4");
    Assertions.assertThat(nodes.get(1)).containsEntry("id", tree[5]).containsEntry("name", "file child id 5");
  }

  @DisplayName(
      """
    Given an existing folder with five nodes inside, a valid public link associated, a limit of
    six elements per page: the findNodes should return the first page containing five nodes ordered
    by category and alphabetically and a null page_token since there is no more pages to fetch""")
  @Test
  void givenAnExistingFolderAndAValidPublicLinkTheFindNodesShouldReturnTheOnlyPageExisting() {
    // Given
    String[] tree = createFolderTree(OWNER_COOKIE);
    String publicId = createLink(tree[0], null, null, OWNER_COOKIE);

    // When
    Response response = publicFindNodes(tree[0], 6, publicId, null, null);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Map<String, Object> page = TestUtils.jsonResponseToMap(response.getBody().asString(), "findNodes");
    Assertions.assertThat(page.get("page_token")).isNull();

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> nodes = (List<Map<String, Object>>) page.get("nodes");
    Assertions.assertThat(nodes).hasSize(5);
    Assertions.assertThat(nodes.get(0)).containsEntry("id", tree[1]).containsEntry("name", "folder child");
    Assertions.assertThat(nodes.get(1)).containsEntry("id", tree[2]).containsEntry("name", "file child id 2");
    Assertions.assertThat(nodes.get(2)).containsEntry("id", tree[3]).containsEntry("name", "file child id 3");
    Assertions.assertThat(nodes.get(3)).containsEntry("id", tree[4]).containsEntry("name", "file child id 4");
    Assertions.assertThat(nodes.get(4)).containsEntry("id", tree[5]).containsEntry("name", "file child id 5");
  }

  @DisplayName(
      """
    Given an existing folder without nodes inside, a valid public link associated: the findNodes
    should return an empty first page and a null page_token""")
  @Test
  void givenAnExistingEmptyFolderAndAValidPublicLinkTheFindNodesShouldReturnAnEmptyFirstPage() {
    // Given
    String folderId = seedFolder("public folder", LOCAL_ROOT, OWNER_COOKIE);
    String publicId = createLink(folderId, null, null, OWNER_COOKIE);

    // When
    Response response = publicFindNodes(folderId, null, publicId, null, null);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Map<String, Object> page = TestUtils.jsonResponseToMap(response.getBody().asString(), "findNodes");
    Assertions.assertThat(page.get("page_token")).isNull();

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> nodes = (List<Map<String, Object>>) page.get("nodes");
    Assertions.assertThat(nodes).isEmpty();
  }

  @DisplayName(
      """
    Given an existing folder without nodes inside, a valid public link associated: the findNodes
    should return an empty first page and a null page_token""")
  @Test
  void givenAnExistingFolderAndAnExpiredPublicLinkTheFindNodesShouldReturn200CodeAndAnErrorMessage() {
    // Given — expires_at is a raw millis passthrough; 1 is already in the past
    String folderId = seedFolder("public folder", LOCAL_ROOT, OWNER_COOKIE);
    String publicId = createLink(folderId, 1, null, OWNER_COOKIE);

    // When
    Response response = publicFindNodes(folderId, null, publicId, null, null);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors).hasSize(1).containsExactly("Could not find node with id " + folderId);
  }

  @DisplayName(
      """
    Given an existing folder with five nodes inside, a not existing public link associated: the
    findNodes should return 200 status code and an error message""")
  @Test
  void givenAnExistingFolderAndANotExistingPublicLinkTheFindNodesShouldReturnAn200StatusWithAnErrorMessage() {
    // Given
    String[] tree = createFolderTree(OWNER_COOKIE);

    // When
    Response response =
        publicFindNodes(
            tree[0], null, "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234ab", null, null);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors).hasSize(1).containsExactly("Could not find node with id " + tree[0]);
  }

  @Test
  void givenANotExistingFolderTheFindNodesShouldReturnAn200StatusWithAnErrorMessage() {
    // Given
    String nonExistentFolderId = "00000000-0000-0000-0000-000000000000";

    // When
    Response response =
        publicFindNodes(
            nonExistentFolderId,
            null,
            "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234ab",
            null,
            null);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("Could not find node with id " + nonExistentFolderId);
  }

  @DisplayName(
      """
    Given an existing folder with five nodes inside, a valid public link associated, another folder
    not public and an hacked page token that is formed to try access a private node: the findNodes
    should return an empty page""")
  @Test
  void givenAnHackedPageTokenWithoutSignatureTheFindNodesShouldReturnAnError() {
    // Given
    String[] tree = createFolderTree(OWNER_COOKIE);
    String publicId = createLink(tree[0], null, null, OWNER_COOKIE);
    // Decoy non-public folder tree — unrelated to the forged token's hard-coded target folder id
    // (see class javadoc); ported for scenario fidelity only.
    String notPublicFolderId = seedFolder("not public folder", LOCAL_ROOT, OWNER_COOKIE);
    seedFolder("folder child", notPublicFolderId, OWNER_COOKIE);
    seedFolder("folder child 2", notPublicFolderId, OWNER_COOKIE);

    String pageTokenHacked = forgeTamperedPageTokenMissingSignature();

    // When
    Response response = publicFindNodes(tree[0], 1, publicId, null, pageTokenHacked);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("Exception while fetching data (/findNodes) : Invalid token signature");
  }

  @Test
  void givenAnHackedPageTokenWithWrongSignatureTheFindNodesShouldReturnAnError() {
    // Given
    String[] tree = createFolderTree(OWNER_COOKIE);
    String publicId = createLink(tree[0], null, null, OWNER_COOKIE);
    String notPublicFolderId = seedFolder("not public folder", LOCAL_ROOT, OWNER_COOKIE);
    seedFolder("folder child", notPublicFolderId, OWNER_COOKIE);
    seedFolder("folder child 2", notPublicFolderId, OWNER_COOKIE);

    String pageTokenHacked = forgeTamperedPageTokenWithWrongSignature("wrong_signature");

    // When
    Response response = publicFindNodes(tree[0], 1, publicId, null, pageTokenHacked);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("Exception while fetching data (/findNodes) : Invalid token signature");
  }

  @Test
  void givenAnExistingFolderAndAValidPublicLinkNotPassedInQueryTheFindNodesShouldReturn200AndAnErrorCode() {
    // Given
    String[] tree = createFolderTree(OWNER_COOKIE);
    createLink(tree[0], null, null, OWNER_COOKIE);

    // When — node_link_id is not passed at all
    Response response = publicFindNodes(tree[0], 3, null, null, null);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors).hasSize(1).containsExactly("Could not find node with id " + tree[0]);
  }

  @Test
  void
      givenAnExistingFolderAndAValidPublicLinkWithAccessCodeTheFindNodesWithoutAccessCodeShouldReturn200CodeAndAnErrorMessage() {
    // Given
    String folderId = seedFolder("public folder", LOCAL_ROOT, OWNER_COOKIE);
    String publicId = createLink(folderId, null, ACCESS_CODE, OWNER_COOKIE);

    // When
    Response response = publicFindNodes(folderId, null, publicId, null, null);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly(
            "Access code is required for accessing the resource with public link id: " + publicId);
  }

  @Test
  void
      givenAnExistingFolderAndAValidPublicLinkWithAccessCodeTheFindNodesWithAccessCodeShouldReturnTheCorrectPage() {
    // Given
    String[] tree = createFolderTree(OWNER_COOKIE);
    String publicId = createLink(tree[0], null, ACCESS_CODE, OWNER_COOKIE);

    // When
    Response response = publicFindNodes(tree[0], 3, publicId, ACCESS_CODE, null);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Map<String, Object> page = TestUtils.jsonResponseToMap(response.getBody().asString(), "findNodes");
    Assertions.assertThat(page.get("page_token")).isNotNull();

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> nodes = (List<Map<String, Object>>) page.get("nodes");
    Assertions.assertThat(nodes).hasSize(3);
    Assertions.assertThat(nodes.get(0)).containsEntry("id", tree[1]).containsEntry("name", "folder child");
    Assertions.assertThat(nodes.get(1)).containsEntry("id", tree[2]).containsEntry("name", "file child id 2");
    Assertions.assertThat(nodes.get(2)).containsEntry("id", tree[3]).containsEntry("name", "file child id 3");
  }
}
