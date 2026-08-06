// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code com.zextras.carbonio.files.acceptance.GetPublicLinksApiIT} rewritten as an out-of-process
 * {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}. All 9 methods and their assertions
 * are preserved verbatim; only the seeding mechanism (API calls capturing server-generated ids —
 * {@link #tickClock()} replaces the seam's {@code Thread.sleep(500)} between the two links so their
 * {@code created_at} timestamps differ; one method still needs {@link #seedLinkRawJdbc} — see its
 * javadoc — for a legacy 8-char {@code public_id} pre-state, which {@code createLink} can no longer
 * produce (it always generates a random 50-char {@code publicId}); fixed token {@code
 * fake-token-account-for-sharing} was renamed to the suite-wide {@code fake-token-b} convention)
 * and the transport changed.
 */
class GetPublicLinksApiIT extends AbstractFilesIT {

  private static final String OWNER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String TARGET_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String OWNER_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String TARGET_COOKIE = "ZM_AUTH_TOKEN=fake-token-b";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", OWNER_ID);
    FilesStackTestResource.getUserManagementService().registerToken("fake-token-b", TARGET_ID);
  }

  /** Creates a link via the real mutation and returns its {@code id}/{@code url} pair. */
  private Map<String, Object> createLink(
      String nodeId, Integer expiresAt, String description, String accessCode, String cookie) {
    GraphqlCommandBuilder builder =
        GraphqlCommandBuilder.aMutationBuilder("createLink").withString("node_id", nodeId);
    if (expiresAt != null) {
      builder = builder.withInteger("expires_at", expiresAt);
    }
    if (description != null) {
      builder = builder.withString("description", description);
    }
    if (accessCode != null) {
      builder = builder.withString("access_code", accessCode);
    }
    String bodyPayload = builder.withWantedResultFormat("{ id url }").build();
    Response response = graphql(bodyPayload, cookie);
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    return TestUtils.jsonResponseToMap(response.getBody().asString(), "createLink");
  }

  private List<Map<String, Object>> getLinks(String nodeId, String cookie, String resultFormat) {
    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("getLinks")
            .withString("node_id", nodeId)
            .withWantedResultFormat(resultFormat)
            .build();
    Response response = graphql(bodyPayload, cookie);
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    return TestUtils.jsonResponseToList(response.getBody().asString(), "getLinks");
  }

  @Test
  void
      givenAnExistingFileWithTwoExistingLinksTheGetLinksShouldReturnAListOfAssociatedLinksOrderedByCreationDescending() {
    // Given
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    Map<String, Object> firstLink = createLink(nodeId, 5, "super-description", null, OWNER_COOKIE);
    // Distinct creation timestamps are required for a deterministic descending sort.
    tickClock();
    Map<String, Object> secondLink = createLink(nodeId, null, null, null, OWNER_COOKIE);

    // When
    List<Map<String, Object>> publicLinks =
        getLinks(nodeId, OWNER_COOKIE, "{ id url expires_at created_at description node { id } }");

    // Then
    Assertions.assertThat(publicLinks).hasSize(2);

    Assertions.assertThat(publicLinks.get(0))
        .containsEntry("id", secondLink.get("id"))
        .containsEntry("url", secondLink.get("url"))
        .containsEntry("expires_at", null)
        .containsEntry("description", null);
    Assertions.assertThat((Map<String, Object>) publicLinks.get(0).get("node"))
        .containsEntry("id", nodeId);

    Assertions.assertThat(publicLinks.get(1))
        .containsEntry("id", firstLink.get("id"))
        .containsEntry("url", firstLink.get("url"))
        .containsEntry("expires_at", 5)
        .containsEntry("description", "super-description");
    Assertions.assertThat((Map<String, Object>) publicLinks.get(1).get("node"))
        .containsEntry("id", nodeId);
  }

  @Test
  void
      givenAnExistingFolderWithOneExistingLinkTheGetLinksShouldReturnAListContainingTheAssociatedLink() {
    // Given
    String nodeId = seedFolder("folder", LOCAL_ROOT, OWNER_COOKIE);
    Map<String, Object> link = createLink(nodeId, 5, "super-description", null, OWNER_COOKIE);

    // When
    List<Map<String, Object>> publicLinks =
        getLinks(nodeId, OWNER_COOKIE, "{ id url expires_at created_at description node { id } }");

    // Then
    Assertions.assertThat(publicLinks).hasSize(1);

    Assertions.assertThat(publicLinks.get(0))
        .containsEntry("id", link.get("id"))
        .containsEntry("url", link.get("url"))
        .containsEntry("expires_at", 5)
        .containsEntry("description", "super-description");
    Assertions.assertThat((Map<String, Object>) publicLinks.get(0).get("node"))
        .containsEntry("id", nodeId);
  }

  @Test
  void
      givenAnExistingFolderWithOneExistingLinkWithAccessCodeTheGetLinksShouldReturnAListContainingTheAssociatedLink() {
    // Given
    String nodeId = seedFolder("folder", LOCAL_ROOT, OWNER_COOKIE);
    Map<String, Object> link =
        createLink(nodeId, 5, "super-description", "fake-access-code", OWNER_COOKIE);

    // When
    List<Map<String, Object>> publicLinks =
        getLinks(
            nodeId,
            OWNER_COOKIE,
            "{ id url expires_at created_at description access_code node { id } }");

    // Then
    Assertions.assertThat(publicLinks).hasSize(1);

    Assertions.assertThat(publicLinks.get(0))
        .containsEntry("id", link.get("id"))
        .containsEntry("url", link.get("url"))
        .containsEntry("expires_at", 5)
        .containsEntry("description", "super-description")
        .containsEntry("access_code", "fake-access-code");
    Assertions.assertThat((Map<String, Object>) publicLinks.get(0).get("node"))
        .containsEntry("id", nodeId);
  }

  @Test
  void givenAnExistingNodeWithoutLinksTheGetLinksShouldReturnAnEmptyList() {
    // Given
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);

    // When
    List<Map<String, Object>> publicLinks = getLinks(nodeId, OWNER_COOKIE, "{ id }");

    // Then
    Assertions.assertThat(publicLinks).isEmpty();
  }

  // TODO it should return an error message
  @Test
  void givenANotExistingNodeTheGetLinksShouldReturn200StatusCodeAndNull() {
    // Given
    String nonExistentNodeId = "00000000-0000-0000-0000-000000000000";

    // When
    List<Map<String, Object>> publicLinks = getLinks(nonExistentNodeId, OWNER_COOKIE, "{ id }");

    // Then
    Assertions.assertThat(publicLinks).first().isNull();
  }

  // TODO it should return an error message
  @Test
  void
      givenAnExistingNodeALinkAssociatedAndAUserWithoutPermissionsTheGetLinksShouldReturn200StatusCodeAndNull() {
    // Given
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    seedLink(nodeId, OWNER_COOKIE);

    // When — TARGET_ID has no relationship with this node at all
    List<Map<String, Object>> publicLinks = getLinks(nodeId, TARGET_COOKIE, "{ id }");

    // Then
    Assertions.assertThat(publicLinks).first().isNull();
  }

  // TODO it should return an error message
  @Test
  void
      givenAnExistingNodeSharedToAUserWithoutShareRightsAndAnExistingLinkTheGetLinksShouldReturn200CodeAndNull() {
    // Given
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    seedShare(nodeId, TARGET_ID, SharePermission.READ_ONLY, OWNER_COOKIE);
    seedLink(nodeId, OWNER_COOKIE);

    // When
    List<Map<String, Object>> publicLinks = getLinks(nodeId, TARGET_COOKIE, "{ id }");

    // Then
    Assertions.assertThat(publicLinks).first().isNull();
  }

  @Test
  void
      givenAnExistingNodeSharedToAUserWithShareRightsAndAnExistingLinkTheGetLinksShouldReturnAListOfAssociatedLinks() {
    // Given
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    seedShare(nodeId, TARGET_ID, SharePermission.READ_AND_SHARE, OWNER_COOKIE);
    String linkId = seedLink(nodeId, OWNER_COOKIE);

    // When
    List<Map<String, Object>> publicLinks = getLinks(nodeId, TARGET_COOKIE, "{ id }");

    // Then
    Assertions.assertThat(publicLinks).hasSize(1);
    Assertions.assertThat(publicLinks.get(0)).containsEntry("id", linkId);
  }

  @Test
  void
      givenAnExistingFileAndAnAssociatedLegacyPublicLinkWithAn8CharsPublicIdentifierTheGetLinksShouldReturnItCorrectly()
          throws SQLException {
    // Given — an 8-char public_id is the pre-widening (V1__init.sql CHARACTER(8)) legacy shape;
    // createLink always generates a random 50-char id, so this pre-state can only be seeded via
    // raw JDBC (see seedLinkRawJdbc's javadoc).
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    String linkId = "0c04783b-bdfb-446f-870c-625f5ae02a0a";
    seedLinkRawJdbc(linkId, nodeId, "abcd1234", null, null, null);

    // When
    List<Map<String, Object>> publicLinks = getLinks(nodeId, OWNER_COOKIE, "{ id url }");

    // Then
    Assertions.assertThat(publicLinks).hasSize(1);
    Assertions.assertThat(publicLinks.get(0))
        .containsEntry("id", linkId)
        .containsEntry("url", "example.com/services/files/public/link/download/abcd1234");
  }
}
