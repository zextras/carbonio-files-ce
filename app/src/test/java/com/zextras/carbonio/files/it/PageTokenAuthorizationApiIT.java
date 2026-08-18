// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zextras.carbonio.files.Constants;
import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.dal.repositories.impl.NodeRepositoryImpl;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Authorization tests for the {@code findNodes} keyset page token (public AND authenticated flows).
 * These are NOT regression tests for a message string: every assertion here is on the OUTCOME
 * (whether node data comes back, whether a page exceeds {@code Pagination.LIMIT}) — never on the
 * wording of an error message, since a message-string assertion is exactly the pattern that let the
 * underlying vulnerability (dropped HMAC signing of the page token, dropped folderId enforcement,
 * dropped limit clamp on the token path) survive the Quarkus port undetected: {@code
 * PublicFindNodesApiIT}'s and {@code ExceptionResidueApiIT}'s existing token-forging tests pass
 * today for the WRONG reason (a Jackson {@code UnrecognizedPropertyException} on the legacy {@code
 * PageQuery} shape's {@code keySet}/{@code signature} fields, not any real signature check).
 *
 * <p>Every "tamper" helper here mints a REAL page token through the real public/authenticated API
 * first, then edits ONE field and re-serialises it via the port's actual {@link
 * NodeRepositoryImpl.PageToken} shape, WITHOUT recomputing any signature — exactly what an attacker
 * who intercepted a legitimate token and edited it client-side would send. This is why these tests
 * cannot pass "by accident": a token that merely fails to Jackson-parse would prove nothing about
 * authorization, only about strict deserialization.
 */
class PageTokenAuthorizationApiIT extends AbstractFilesIT {

  private static final String OWNER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OWNER_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String OTHER_USER_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String OTHER_COOKIE = "ZM_AUTH_TOKEN=fake-token-b";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", OWNER_ID);
    FilesStackTestResource.getUserManagementService().registerToken("fake-token-b", OTHER_USER_ID);
  }

  // --------------------------------------------------------------------------------- helpers

  private static String createLink(String nodeId, String ownerCookie) {
    String mutation =
        GraphqlCommandBuilder.aMutationBuilder("createLink")
            .withString("node_id", nodeId)
            .withWantedResultFormat("{ url }")
            .build();
    Response response = graphql(mutation, ownerCookie);
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    String url =
        (String)
            TestUtils.jsonResponseToMap(response.getBody().asString(), "createLink").get("url");
    return url.substring(url.length() - 50);
  }

  private static Response publicFindNodes(
      String folderId, Integer limit, String nodeLinkId, String pageToken) {
    GraphqlCommandBuilder builder =
        GraphqlCommandBuilder.aQueryBuilder("findNodes").withString("folder_id", folderId);
    if (limit != null) {
      builder = builder.withInteger("limit", limit);
    }
    if (nodeLinkId != null) {
      builder = builder.withString("node_link_id", nodeLinkId);
    }
    if (pageToken != null) {
      builder = builder.withString("page_token", pageToken);
    }
    String bodyPayload =
        builder.withWantedResultFormat("{ nodes { id name }, page_token }").build();
    return publicGraphql(bodyPayload);
  }

  private static String pageTokenOf(Response response) {
    return (String)
        TestUtils.jsonResponseToMap(response.getBody().asString(), "findNodes").get("page_token");
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> nodesOf(Response response) {
    Map<String, Object> page =
        TestUtils.jsonResponseToMap(response.getBody().asString(), "findNodes");
    Object nodes = page.get("nodes");
    return nodes == null ? null : (List<Map<String, Object>>) nodes;
  }

  /** Decodes a real, server-minted page token into the port's actual wire shape, for tampering. */
  private static NodeRepositoryImpl.PageToken decode(String token) throws Exception {
    return new ObjectMapper()
        .readValue(Base64.getUrlDecoder().decode(token), NodeRepositoryImpl.PageToken.class);
  }

  /**
   * Re-encodes a (tampered) token WITHOUT recomputing any signature: exactly what an attacker who
   * intercepted a real token and edited one field client-side would send back.
   */
  private static String encodeTampered(NodeRepositoryImpl.PageToken token) throws Exception {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(new ObjectMapper().writeValueAsBytes(token));
  }

  /** Flips one character of a Base64url token to a different valid Base64url character. */
  private static String flipOneCharacter(String base64UrlToken) {
    int mid = base64UrlToken.length() / 2;
    char original = base64UrlToken.charAt(mid);
    char replacement = original == 'A' ? 'B' : 'A';
    return base64UrlToken.substring(0, mid) + replacement + base64UrlToken.substring(mid + 1);
  }

  // ----------------------------------------------------------------------------------- tests

  @DisplayName(
      """
      A valid page token tampered to point its folderId at a DIFFERENT, unrelated (non-public)
      folder must be denied outright, not leak that folder's children\
      """)
  @Test
  void givenATamperedFolderIdInAValidTokenPublicFindNodesMustDenyAccess() throws Exception {
    // Given: a public, link-covered folder with more than one page of children ...
    String publicFolderId = seedFolder("pta public folder", LOCAL_ROOT, OWNER_COOKIE);
    seedFile("a.txt", publicFolderId, "a".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    seedFile("b.txt", publicFolderId, "b".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    String publicId = createLink(publicFolderId, OWNER_COOKIE);

    // ... and a completely unrelated, NON-public folder with a distinctive child.
    String secretFolderId = seedFolder("pta secret folder", LOCAL_ROOT, OWNER_COOKIE);
    seedFile("secret.txt", secretFolderId, "s".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);

    // Mint a LEGITIMATE token by paginating the public folder for real.
    Response firstPage = publicFindNodes(publicFolderId, 1, publicId, null);
    Assertions.assertThat(firstPage.getStatusCode()).isEqualTo(200);
    String legitToken = pageTokenOf(firstPage);
    Assertions.assertThat(legitToken).isNotNull();

    // Tamper: redirect the token's folderId at the unrelated secret folder.
    NodeRepositoryImpl.PageToken tampered = decode(legitToken);
    tampered.folderId = secretFolderId;
    String tamperedToken = encodeTampered(tampered);

    // When: replay the tampered token under the SAME (still legitimately valid) link/folder pair.
    Response response = publicFindNodes(publicFolderId, 1, publicId, tamperedToken);

    // Then: denial. The secret folder's content must never come back.
    List<Map<String, Object>> nodes = nodesOf(response);
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(nodes == null || nodes.isEmpty())
        .as("no node data must be returned for a tampered folderId, but got: %s", nodes)
        .isTrue();
    Assertions.assertThat(errors).isNotEmpty();
  }

  @DisplayName(
      """
      A valid page token tampered to OMIT folderId must NOT fall back to leaking LOCAL_ROOT's
      children (every user's top-level nodes)\
      """)
  @Test
  void givenATokenOmittingFolderIdPublicFindNodesMustNotFallBackToLocalRoot() throws Exception {
    String publicFolderId = seedFolder("pta public folder 2", LOCAL_ROOT, OWNER_COOKIE);
    // A FOLDER child (category 1, sorts before files) is the first page-1 item, so the cursor it
    // mints is comparable against OTHER top-level folders (also category 1) once the scope is
    // switched to LOCAL_ROOT below — a cursor minted from a FILE child (category 2) would never
    // match a folder-shaped row and would give a false "no leak" reading regardless of the fix.
    seedFolder("aaa anchor subfolder", publicFolderId, OWNER_COOKIE);
    seedFile("a.txt", publicFolderId, "a".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    seedFile("b.txt", publicFolderId, "b".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    String publicId = createLink(publicFolderId, OWNER_COOKIE);

    // A totally unrelated user's own top-level folder, named to sort AFTER "aaa anchor subfolder"
    // alphabetically: if the token's missing folderId ever falls back to LOCAL_ROOT, THIS is what
    // would leak (LOCAL_ROOT is the shared parent of every user's top-level nodes, not just this
    // link owner's).
    String otherUsersFolderId =
        seedFolder("zzz other user's private folder", LOCAL_ROOT, OTHER_COOKIE);

    Response firstPage = publicFindNodes(publicFolderId, 1, publicId, null);
    String legitToken = pageTokenOf(firstPage);
    Assertions.assertThat(legitToken).isNotNull();

    NodeRepositoryImpl.PageToken tampered = decode(legitToken);
    tampered.folderId = null;
    tampered.limit = 10; // enough to cover every top-level folder seeded by this test
    String tamperedToken = encodeTampered(tampered);

    Response response = publicFindNodes(publicFolderId, 50, publicId, tamperedToken);

    List<Map<String, Object>> nodes = nodesOf(response);
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(nodes == null || nodes.isEmpty())
        .as("no node data must be returned when folderId is omitted, but got: %s", nodes)
        .isTrue();
    Assertions.assertThat(errors).isNotEmpty();
    if (nodes != null) {
      Assertions.assertThat(nodes).extracting(n -> n.get("id")).doesNotContain(otherUsersFolderId);
    }
  }

  @DisplayName(
      """
      A valid page token tampered to raise limit far above Pagination.LIMIT must not bypass the
      clamp on the PUBLIC findNodes token path\
      """)
  @Test
  void givenATokenWithOversizedLimitPublicFindNodesMustNotBypassTheClamp() throws Exception {
    int childCount = Constants.Config.Pagination.LIMIT + 5;
    String publicFolderId = seedFolder("pta big public folder", LOCAL_ROOT, OWNER_COOKIE);
    for (int i = 0; i < childCount; i++) {
      seedFile(
          String.format("f%03d.txt", i),
          publicFolderId,
          "x".getBytes(StandardCharsets.UTF_8),
          OWNER_COOKIE);
    }
    String publicId = createLink(publicFolderId, OWNER_COOKIE);

    Response firstPage = publicFindNodes(publicFolderId, 1, publicId, null);
    String legitToken = pageTokenOf(firstPage);
    Assertions.assertThat(legitToken).isNotNull();

    NodeRepositoryImpl.PageToken tampered = decode(legitToken);
    tampered.limit = childCount; // far above Pagination.LIMIT
    String tamperedToken = encodeTampered(tampered);

    Response response = publicFindNodes(publicFolderId, 1, publicId, tamperedToken);

    List<Map<String, Object>> nodes = nodesOf(response);
    Assertions.assertThat(nodes == null ? 0 : nodes.size())
        .as(
            "a single page must never exceed Pagination.LIMIT (%d)",
            Constants.Config.Pagination.LIMIT)
        .isLessThanOrEqualTo(Constants.Config.Pagination.LIMIT);
  }

  @DisplayName("A page token with one flipped character must be rejected outright")
  @Test
  void givenATokenWithOneFlippedCharacterPublicFindNodesMustRejectIt() throws Exception {
    String publicFolderId = seedFolder("pta flip char folder", LOCAL_ROOT, OWNER_COOKIE);
    seedFile("a.txt", publicFolderId, "a".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    seedFile("b.txt", publicFolderId, "b".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    String publicId = createLink(publicFolderId, OWNER_COOKIE);

    Response firstPage = publicFindNodes(publicFolderId, 1, publicId, null);
    String legitToken = pageTokenOf(firstPage);
    Assertions.assertThat(legitToken).isNotNull();

    String corrupted = flipOneCharacter(legitToken);

    Response response = publicFindNodes(publicFolderId, 1, publicId, corrupted);

    List<Map<String, Object>> nodes = nodesOf(response);
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(nodes == null || nodes.isEmpty())
        .as("no node data must be returned for a corrupted token, but got: %s", nodes)
        .isTrue();
    Assertions.assertThat(errors).isNotEmpty();
  }

  @DisplayName(
      """
      A page token minted under public link A must be rejected when replayed under a DIFFERENT
      public link B, even though it is untampered and validly signed\
      """)
  @Test
  void givenATokenMintedUnderOneLinkPublicFindNodesMustRejectItUnderAnotherLink() throws Exception {
    String folderAId = seedFolder("pta folder A", LOCAL_ROOT, OWNER_COOKIE);
    seedFile("a1.txt", folderAId, "a".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    seedFile("a2.txt", folderAId, "a".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    String linkA = createLink(folderAId, OWNER_COOKIE);

    String folderBId = seedFolder("pta folder B", LOCAL_ROOT, OWNER_COOKIE);
    seedFile("b1.txt", folderBId, "b".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    String linkB = createLink(folderBId, OWNER_COOKIE);

    // Mint a REAL, validly-signed token by paginating folder A through link A.
    Response firstPage = publicFindNodes(folderAId, 1, linkA, null);
    Assertions.assertThat(firstPage.getStatusCode()).isEqualTo(200);
    String tokenFromA = pageTokenOf(firstPage);
    Assertions.assertThat(tokenFromA).isNotNull();

    // Replay it UNTAMPERED under folder B / link B.
    Response response = publicFindNodes(folderBId, 1, linkB, tokenFromA);

    List<Map<String, Object>> nodes = nodesOf(response);
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(nodes == null || nodes.isEmpty())
        .as(
            "no node data must be returned for a token replayed under a different link, but got:"
                + " %s",
            nodes)
        .isTrue();
    Assertions.assertThat(errors).isNotEmpty();
    if (nodes != null) {
      Assertions.assertThat(nodes).extracting(n -> n.get("name")).doesNotContain("a2.txt");
    }
  }

  @DisplayName("A fully hand-built, unsigned, port-shaped token must be rejected")
  @Test
  void givenAFullyHandBuiltPortShapedUnsignedTokenPublicFindNodesMustRejectIt() throws Exception {
    String publicFolderId = seedFolder("pta hand built folder", LOCAL_ROOT, OWNER_COOKIE);
    seedFile("only.txt", publicFolderId, "x".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    String publicId = createLink(publicFolderId, OWNER_COOKIE);

    // Hand-built to match NodeRepositoryImpl.PageToken's REAL field names exactly (never the
    // legacy PageQuery shape with keySet/signature that AbstractFilesIT's older helper uses) —
    // no signature field at all, no in-JVM secret used to produce one.
    String json =
        String.format(
            """
            {
              "limit": 1,
              "sort": "NAME_ASC",
              "flagged": null,
              "folderId": "%s",
              "cascade": null,
              "sharedWithMe": null,
              "sharedByMe": null,
              "directShare": null,
              "nodeType": null,
              "ownerId": null,
              "keywords": [],
              "cursor": [0, "", ""]
            }\
            """,
            publicFolderId);
    String handBuiltToken =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(json.getBytes(StandardCharsets.UTF_8));

    Response response = publicFindNodes(publicFolderId, 1, publicId, handBuiltToken);

    List<Map<String, Object>> nodes = nodesOf(response);
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(nodes == null || nodes.isEmpty())
        .as("no node data must be returned for a hand-built unsigned token, but got: %s", nodes)
        .isTrue();
    Assertions.assertThat(errors).isNotEmpty();
  }

  @DisplayName(
      """
      A valid page token tampered to raise limit far above Pagination.LIMIT must not bypass the
      clamp on the AUTHENTICATED findNodes token path\
      """)
  @Test
  void givenATokenWithOversizedLimitAuthenticatedFindNodesMustNotBypassTheClamp() throws Exception {
    int childCount = Constants.Config.Pagination.LIMIT + 5;
    String folderId = seedFolder("pta auth big folder", LOCAL_ROOT, OWNER_COOKIE);
    for (int i = 0; i < childCount; i++) {
      seedFile(
          String.format("g%03d.txt", i),
          folderId,
          "x".getBytes(StandardCharsets.UTF_8),
          OWNER_COOKIE);
    }

    String firstQuery =
        GraphqlCommandBuilder.aQueryBuilder("findNodes")
            .withString("folder_id", folderId)
            .withBoolean("cascade", false)
            .withEnumLiteral("sort", "NAME_ASC")
            .withInteger("limit", 1)
            .withWantedResultFormat("{ nodes { id name }, page_token }")
            .build();
    Response firstPage = graphql(firstQuery, OWNER_COOKIE);
    Assertions.assertThat(firstPage.getStatusCode()).isEqualTo(200);
    String legitToken = pageTokenOf(firstPage);
    Assertions.assertThat(legitToken).isNotNull();

    NodeRepositoryImpl.PageToken tampered = decode(legitToken);
    tampered.limit = childCount;
    String tamperedToken = encodeTampered(tampered);

    String secondQuery =
        GraphqlCommandBuilder.aQueryBuilder("findNodes")
            .withString("folder_id", folderId)
            .withBoolean("cascade", false)
            .withEnumLiteral("sort", "NAME_ASC")
            .withInteger("limit", 1)
            .withString("page_token", tamperedToken)
            .withWantedResultFormat("{ nodes { id name }, page_token }")
            .build();
    Response response = graphql(secondQuery, OWNER_COOKIE);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Object nodesObj =
        TestUtils.jsonResponseToMap(response.getBody().asString(), "findNodes").get("nodes");
    int size = nodesObj == null ? 0 : ((List<?>) nodesObj).size();
    Assertions.assertThat(size)
        .as(
            "a single authenticated page must never exceed Pagination.LIMIT (%d)",
            Constants.Config.Pagination.LIMIT)
        .isLessThanOrEqualTo(Constants.Config.Pagination.LIMIT);
  }
}
