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
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code com.zextras.carbonio.files.acceptance.UpdatePublicLinkApiIT} rewritten as an
 * out-of-process {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}. All 9 methods and
 * their assertions are preserved verbatim; only the seeding mechanism changed: the seam's {@code
 * DatabasePopulator#addLink} let the test choose the link's public id (a fixed 51-char literal),
 * which the public API cannot do ({@code LinkDataFetcher#createLinkFetcher} always generates a
 * random 50-char {@code publicId}) — so each test now creates the link via the real {@code
 * createLink} mutation and captures its SERVER-GENERATED {@code id}/{@code url}, then asserts that
 * {@code updateLink} returns the SAME id/url (proving the URL is stable across an update) instead
 * of a hard-coded string. Fixed token {@code fake-token-account-for-sharing} was renamed to the
 * suite-wide {@code fake-token-b} convention documented on {@link AbstractFilesIT} — the user id
 * itself is unchanged.
 */
class UpdatePublicLinkApiIT extends AbstractFilesIT {

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

  private Response updateLink(
      String linkId, Integer expiresAt, String description, String accessCode, String cookie) {
    GraphqlCommandBuilder builder =
        GraphqlCommandBuilder.aMutationBuilder("updateLink").withString("link_id", linkId);
    if (expiresAt != null) {
      builder = builder.withInteger("expires_at", expiresAt);
    }
    if (description != null) {
      builder = builder.withString("description", description);
    }
    if (accessCode != null) {
      builder = builder.withString("access_code", accessCode);
    }
    String bodyPayload =
        builder
            .withWantedResultFormat(
                "{ id url expires_at created_at description access_code node { id } }")
            .build();
    return graphql(bodyPayload, cookie);
  }

  @Test
  void
      givenAnExistingFileAnExistingLinkAndAllUpdatedFieldsTheUpdateLinkShouldReturnTheUpdatedLink() {
    // Given
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    Map<String, Object> created =
        createLink(nodeId, 5, "super-description", "fake-access-code", OWNER_COOKIE);
    String linkId = (String) created.get("id");
    String originalUrl = (String) created.get("url");

    // When
    Response response =
        updateLink(linkId, 10, "another-description", "another-fake-access-code", OWNER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Map<String, Object> updatedLink =
        TestUtils.jsonResponseToMap(response.getBody().asString(), "updateLink");

    Assertions.assertThat((String) updatedLink.get("url")).isEqualTo(originalUrl);

    Assertions.assertThat(updatedLink)
        .containsEntry("id", linkId)
        .containsEntry("expires_at", 10)
        .containsEntry("description", "another-description")
        .containsEntry("access_code", "another-fake-access-code");

    Assertions.assertThat((Map<String, Object>) updatedLink.get("node"))
        .containsEntry("id", nodeId);
  }

  @Test
  void
      givenAnExistingFileAnExistingLinkAndEmptyAccessCodeTheUpdateLinkShouldReturnTheUpdatedLink() {
    // Given
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    Map<String, Object> created =
        createLink(nodeId, 5, "super-description", "fake-access-code", OWNER_COOKIE);
    String linkId = (String) created.get("id");
    String originalUrl = (String) created.get("url");

    // When
    Response response = updateLink(linkId, 10, "another-description", "", OWNER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Map<String, Object> updatedLink =
        TestUtils.jsonResponseToMap(response.getBody().asString(), "updateLink");

    Assertions.assertThat((String) updatedLink.get("url")).isEqualTo(originalUrl);

    Assertions.assertThat(updatedLink)
        .containsEntry("id", linkId)
        .containsEntry("expires_at", 10)
        .containsEntry("description", "another-description")
        .containsEntry("access_code", null);

    Assertions.assertThat((Map<String, Object>) updatedLink.get("node"))
        .containsEntry("id", nodeId);
  }

  @Test
  void
      givenAnExistingFileAnExistingLinkAndNoFieldsToUpdateTheUpdateLinkShouldReturnTheUntouchedLink() {
    // Given
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    Map<String, Object> created = createLink(nodeId, 5, "super-description", null, OWNER_COOKIE);
    String linkId = (String) created.get("id");
    String originalUrl = (String) created.get("url");

    // When
    Response response = updateLink(linkId, null, null, null, OWNER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Map<String, Object> updatedLink =
        TestUtils.jsonResponseToMap(response.getBody().asString(), "updateLink");

    Assertions.assertThat((String) updatedLink.get("id")).isNotNull().hasSize(36);
    Assertions.assertThat((String) updatedLink.get("url")).isEqualTo(originalUrl);

    Assertions.assertThat(updatedLink)
        .containsEntry("id", linkId)
        .containsEntry("expires_at", 5)
        .containsEntry("description", "super-description")
        .containsEntry("access_code", null);

    Assertions.assertThat((Map<String, Object>) updatedLink.get("node"))
        .containsEntry("id", nodeId);
  }

  @Test
  void
      givenAnExistingFolderAnExistingLinkAndAllUpdatedFieldsTheUpdateLinkShouldReturnTheUpdatedLink() {
    // Given
    String nodeId = seedFolder("folder", LOCAL_ROOT, OWNER_COOKIE);
    Map<String, Object> created = createLink(nodeId, null, null, null, OWNER_COOKIE);
    String linkId = (String) created.get("id");
    String originalUrl = (String) created.get("url");

    // When
    Response response = updateLink(linkId, 10, "another-description", null, OWNER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Map<String, Object> updatedLink =
        TestUtils.jsonResponseToMap(response.getBody().asString(), "updateLink");

    Assertions.assertThat((String) updatedLink.get("url")).isEqualTo(originalUrl);

    Assertions.assertThat(updatedLink)
        .containsEntry("id", linkId)
        .containsEntry("expires_at", 10)
        .containsEntry("description", "another-description")
        .containsEntry("access_code", null);

    Assertions.assertThat((Map<String, Object>) updatedLink.get("node"))
        .containsEntry("id", nodeId);
  }

  @Test
  void givenANotExistingLinkTheUpdateLinkShouldReturn200CodeWithAnErrorMessage() {
    // Given
    String nonExistentLinkId = "cc83bd73-8c5c-4e7c-8c34-3e3919ff6c9b";

    // When
    Response response = updateLink(nonExistentLinkId, null, null, null, OWNER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errorResponse = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errorResponse)
        .hasSize(1)
        .containsExactly("Could not find link with id " + nonExistentLinkId);
  }

  @Test
  void
      givenAnExistingNodeSharedToAUserWithShareRightsAndAnExistingLinkTheUpdateLinkShouldReturnAnUpdatedLink() {
    // Given
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    seedShare(nodeId, TARGET_ID, SharePermission.READ_AND_SHARE, OWNER_COOKIE);
    Map<String, Object> created = createLink(nodeId, 5, "super-description", null, OWNER_COOKIE);
    String linkId = (String) created.get("id");

    // When
    Response response = updateLink(linkId, null, "", null, TARGET_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Map<String, Object> updatedLink =
        TestUtils.jsonResponseToMap(response.getBody().asString(), "updateLink");

    Assertions.assertThat(updatedLink).containsEntry("id", linkId).containsEntry("description", "");
  }

  @Test
  void
      givenAnExistingNodeAnExistingLinkWithExpirationAndAnExpiresAtToZeroTheUpdateLinkShouldReturnAnUpdatedLinkWithoutExpiration() {
    // Given
    String nodeId = seedFolder("folder", LOCAL_ROOT, OWNER_COOKIE);
    Map<String, Object> created = createLink(nodeId, 5, null, null, OWNER_COOKIE);
    String linkId = (String) created.get("id");

    // When
    Response response = updateLink(linkId, 0, null, null, OWNER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Map<String, Object> updatedLink =
        TestUtils.jsonResponseToMap(response.getBody().asString(), "updateLink");

    Assertions.assertThat(updatedLink)
        .containsEntry("id", linkId)
        .containsEntry("expires_at", null);
  }

  @Test
  void
      givenAnExistingNodeSharedToAUserWithoutShareRightsAndAnExistingLinkTheUpdateLinkShouldReturn200CodeWithAnErrorMessage() {
    // Given
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    seedShare(nodeId, TARGET_ID, SharePermission.READ_ONLY, OWNER_COOKIE);
    Map<String, Object> created = createLink(nodeId, 5, "super-description", null, OWNER_COOKIE);
    String linkId = (String) created.get("id");

    // When
    Response response = updateLink(linkId, null, null, null, TARGET_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errorResponse = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errorResponse)
        .hasSize(1)
        .containsExactly("Could not find link with id " + linkId);
  }

  @Test
  void
      givenAnExistingNodeAndAUserWithoutPermissionsAndAnExistingLinkTheUpdateLinkShouldReturn200CodeWithAnErrorMessage() {
    // Given
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    Map<String, Object> created = createLink(nodeId, 5, "super-description", null, OWNER_COOKIE);
    String linkId = (String) created.get("id");

    // When
    Response response = updateLink(linkId, null, null, null, TARGET_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errorResponse = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errorResponse)
        .hasSize(1)
        .containsExactly("Could not find link with id " + linkId);
  }
}
