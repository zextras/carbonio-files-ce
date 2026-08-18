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
import java.util.stream.Stream;
import org.apache.commons.lang3.RandomStringUtils;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@code com.zextras.carbonio.files.acceptance.CreatePublicLinkApiIT} rewritten as an
 * out-of-process {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}. All 10 methods and
 * their assertions are preserved verbatim; only the seeding mechanism (API calls capturing
 * server-generated ids; fixed token {@code fake-token-account-for-sharing} was renamed to the
 * suite-wide {@code fake-token-b} convention documented on {@link AbstractFilesIT} — the user id
 * itself is unchanged) and the transport changed. The 50-links-limit test seeds via 50 sequential
 * {@code createLink} API calls (replacing the seam's {@code DatabasePopulator#addLinks} bulk
 * repository write) since the count, not the identity, of the pre-existing links is what matters.
 */
class CreatePublicLinkApiIT extends AbstractFilesIT {

  private static final String OWNER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String TARGET_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String OWNER_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String TARGET_COOKIE = "ZM_AUTH_TOKEN=fake-token-b";

  // It is needed since strings cannot be generated as constants in the @ValueSource definition
  static Stream<Arguments> invalidAccessCodesProvider() {
    return Stream.of(
        Arguments.of(RandomStringUtils.secure().nextAlphanumeric(9)),
        Arguments.of(RandomStringUtils.secure().nextAlphanumeric(255)));
  }

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", OWNER_ID);
    FilesStackTestResource.getUserManagementService().registerToken("fake-token-b", TARGET_ID);
  }

  private Response createLink(
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
    String bodyPayload =
        builder
            .withWantedResultFormat(
                "{ id url expires_at created_at description access_code node { id } }")
            .build();
    return graphql(bodyPayload, cookie);
  }

  @Test
  void givenAFileIdAndAllLinkFieldsTheCreateLinkShouldCreateANewPublicLink() {
    // Given
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);

    // When
    Response response =
        createLink(nodeId, 5, "super-description", "fake-access-code", OWNER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Map<String, Object> createdLink =
        TestUtils.jsonResponseToMap(response.getBody().asString(), "createLink");

    Assertions.assertThat((String) createdLink.get("id")).isNotNull().hasSize(36);
    Assertions.assertThat((String) createdLink.get("url"))
        .startsWith("example.com/services/files/public/link/download/")
        .hasSize("example.com/services/files/public/link/download/".length() + 50);

    Assertions.assertThat(createdLink)
        .containsEntry("expires_at", 5)
        .containsEntry("description", "super-description")
        .containsEntry("access_code", "fake-access-code");

    Assertions.assertThat((Map<String, Object>) createdLink.get("node"))
        .containsEntry("id", nodeId);
  }

  @Test
  void givenAFileIdAndOnlyMandatoryLinkFieldsTheCreateLinkShouldCreateANewPublicLink() {
    // Given
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);

    // When
    Response response = createLink(nodeId, null, null, null, OWNER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Map<String, Object> createdLink =
        TestUtils.jsonResponseToMap(response.getBody().asString(), "createLink");

    Assertions.assertThat((String) createdLink.get("id")).isNotNull().hasSize(36);
    Assertions.assertThat((String) createdLink.get("url"))
        .startsWith("example.com/services/files/public/link/download/")
        .hasSize("example.com/services/files/public/link/download/".length() + 50);

    Assertions.assertThat(createdLink)
        .containsEntry("expires_at", null)
        .containsEntry("description", null)
        .containsEntry("access_code", null);

    Assertions.assertThat((Map<String, Object>) createdLink.get("node"))
        .containsEntry("id", nodeId);
  }

  @Test
  void givenAFolderIdAndOnlyMandatoryLinkFieldsTheCreateLinkShouldCreateANewPublicLink() {
    // Given
    String nodeId = seedFolder("folder", LOCAL_ROOT, OWNER_COOKIE);

    // When
    Response response = createLink(nodeId, null, null, null, OWNER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Map<String, Object> createdLink =
        TestUtils.jsonResponseToMap(response.getBody().asString(), "createLink");

    Assertions.assertThat((String) createdLink.get("id")).isNotNull().hasSize(36);
    Assertions.assertThat((String) createdLink.get("url"))
        .startsWith("example.com/files/public/link/access/")
        .hasSize("example.com/files/public/link/access/".length() + 50);

    Assertions.assertThat(createdLink)
        .containsEntry("expires_at", null)
        .containsEntry("description", null)
        .containsEntry("access_code", null);

    Assertions.assertThat((Map<String, Object>) createdLink.get("node"))
        .containsEntry("id", nodeId);
  }

  @Test
  void givenANodeIdAndAnExpiresAtToZeroTheCreateLinkShouldCreateANewPublicLinkWithoutExpiration() {
    // Given
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);

    // When
    Response response = createLink(nodeId, 0, null, null, OWNER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Map<String, Object> createdLink =
        TestUtils.jsonResponseToMap(response.getBody().asString(), "createLink");

    Assertions.assertThat((String) createdLink.get("id")).isNotNull().hasSize(36);
    Assertions.assertThat(createdLink).containsEntry("expires_at", null);
  }

  @ParameterizedTest
  @MethodSource("invalidAccessCodesProvider")
  void givenAFileIdAndAnInvalidAccessCodeLengthTheCreateLinkShouldReturn200CodeWithAnErrorMessage(
      String invalidAccessCode) {
    // Given
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);

    // When
    Response response = createLink(nodeId, 5, "super-description", invalidAccessCode, OWNER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errorResponse = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errorResponse)
        .hasSize(1)
        .containsExactly(
            "Invalid link access code. The access code must be between 10 and 255 characters long");
  }

  @Test
  void givenANotExistingNodeIdTheCreateLinkShouldReturn200CodeWithAnErrorMessage() {
    // Given
    String nonExistentNodeId = "00000000-0000-0000-0000-000000000000";

    // When
    Response response = createLink(nonExistentNodeId, null, null, null, OWNER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errorResponse = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errorResponse)
        .hasSize(1)
        .containsExactly(
            "There was a problem while executing requested operation on node: "
                + nonExistentNodeId);
  }

  @Test
  void givenAnExistingNodeSharedToAUserWithShareRightsTheCreateLinkShouldReturnANewPublicLink() {
    // Given
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    seedShare(nodeId, TARGET_ID, SharePermission.READ_AND_SHARE, OWNER_COOKIE);

    // When
    Response response = createLink(nodeId, null, null, null, TARGET_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Map<String, Object> createdLink =
        TestUtils.jsonResponseToMap(response.getBody().asString(), "createLink");

    Assertions.assertThat((String) createdLink.get("id")).isNotNull().hasSize(36);
  }

  @Test
  void
      givenAnExistingNodeSharedToAUserWithoutShareRightsTheCreateLinkShouldReturn200CodeWithAnErrorMessage() {
    // Given
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    seedShare(nodeId, TARGET_ID, SharePermission.READ_ONLY, OWNER_COOKIE);

    // When
    Response response = createLink(nodeId, null, null, null, TARGET_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errorResponse = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errorResponse)
        .hasSize(1)
        .containsExactly(
            "There was a problem while executing requested operation on node: " + nodeId);
  }

  @Test
  void
      givenAnExistingNodeAndAUserWithoutPermissionsTheCreateLinkShouldReturn200CodeWithAnErrorMessage() {
    // Given
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);

    // When
    Response response = createLink(nodeId, null, null, null, TARGET_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errorResponse = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errorResponse)
        .hasSize(1)
        .containsExactly(
            "There was a problem while executing requested operation on node: " + nodeId);
  }

  @Test
  void
      givenAFileIdWithMoreThanFiftyLinksAndOnlyMandatoryLinkFieldsTheCreateLinkShouldReturn200CodeWithAnErrorMessage() {
    // Given
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    for (int i = 0; i < 50; i++) {
      seedLink(nodeId, OWNER_COOKIE);
    }

    // When
    Response response = createLink(nodeId, null, null, null, OWNER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errorResponse = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errorResponse)
        .hasSize(1)
        .containsExactly("The limit for links has been reached for this node: " + nodeId);
  }
}
