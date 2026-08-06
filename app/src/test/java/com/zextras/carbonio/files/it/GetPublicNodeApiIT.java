// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code com.zextras.carbonio.files.acceptance.GetPublicNodeApiIT} rewritten as an out-of-process
 * {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}. All 7 methods and their assertions
 * are preserved verbatim; only the seeding mechanism changed: the node/link is seeded via the real
 * {@code seedFolder}/{@code seedFile}/{@code createLink} API (capturing the server-generated {@code
 * public_id} from the {@code createLink} response's {@code url} — its last 50 characters,
 * regardless of the file/folder URL-prefix difference — since {@code createLink} can no longer be
 * pointed at a caller-chosen id) and the transport changed; the {@code getPublicNode} query itself
 * already runs unauthenticated via {@link #publicGraphql}, exactly as in the original suite. {@code
 * expires_at} is a raw millis passthrough (see {@code LinkDataFetcher#createLinkFetcher}), so
 * requesting {@code expires_at: 1} produces an already-expired link exactly like the seam's direct
 * {@code Optional.of(1L)} write did.
 */
class GetPublicNodeApiIT extends AbstractFilesIT {

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
  private String createLinkAndGetPublicId(
      String nodeId, Integer expiresAt, String accessCode, String cookie) {
    GraphqlCommandBuilder builder =
        GraphqlCommandBuilder.aMutationBuilder("createLink").withString("node_id", nodeId);
    if (expiresAt != null) {
      builder = builder.withInteger("expires_at", expiresAt);
    }
    if (accessCode != null) {
      builder = builder.withString("access_code", accessCode);
    }
    String bodyPayload = builder.withWantedResultFormat("{ url }").build();
    Response response = graphql(bodyPayload, cookie);
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    String url =
        (String)
            TestUtils.jsonResponseToMap(response.getBody().asString(), "createLink").get("url");
    return url.substring(url.length() - 50);
  }

  private Response getPublicNode(String publicLinkId, String accessCode, String resultFormat) {
    GraphqlCommandBuilder builder =
        GraphqlCommandBuilder.aQueryBuilder("getPublicNode")
            .withString("node_link_id", publicLinkId);
    if (accessCode != null) {
      builder = builder.withString("access_code", accessCode);
    }
    String bodyPayload = builder.withWantedResultFormat(resultFormat).build();
    return publicGraphql(bodyPayload);
  }

  @Test
  void givenAPublicLinkIdAndAnExistingFolderTheGetPublicNodeShouldReturnThePublicFolder() {
    // Given
    long now = System.currentTimeMillis();
    String nodeId = seedFolder("folder", LOCAL_ROOT, OWNER_COOKIE);
    String publicId = createLinkAndGetPublicId(nodeId, null, null, OWNER_COOKIE);

    // When
    Response response = getPublicNode(publicId, null, "{ id created_at updated_at name type }");

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Map<String, Object> publicNode =
        TestUtils.jsonResponseToMap(response.getBody().asString(), "getPublicNode");

    Assertions.assertThat(publicNode.get("id")).isEqualTo(nodeId);
    Assertions.assertThat((long) publicNode.get("created_at")).isGreaterThanOrEqualTo(now);
    Assertions.assertThat((long) publicNode.get("updated_at")).isGreaterThanOrEqualTo(now);
    Assertions.assertThat(publicNode.get("name")).isEqualTo("folder");
    Assertions.assertThat(publicNode.get("type")).isEqualTo(NodeType.FOLDER.toString());
  }

  @Test
  void givenAPublicLinkIdAndAnExistingFileTheGetPublicNodeShouldReturnThePublicFile() {
    // Given
    long now = System.currentTimeMillis();
    String nodeId =
        seedFile("test.txt", LOCAL_ROOT, "conte".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    String publicId = createLinkAndGetPublicId(nodeId, null, null, OWNER_COOKIE);

    // When
    Response response =
        getPublicNode(
            publicId,
            null,
            "{ id created_at updated_at name type ... on File { extension mime_type size } }");

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Map<String, Object> publicNode =
        TestUtils.jsonResponseToMap(response.getBody().asString(), "getPublicNode");

    Assertions.assertThat(publicNode.get("id")).isEqualTo(nodeId);
    Assertions.assertThat((long) publicNode.get("created_at")).isGreaterThanOrEqualTo(now);
    Assertions.assertThat((long) publicNode.get("updated_at")).isGreaterThanOrEqualTo(now);
    Assertions.assertThat(publicNode.get("name")).isEqualTo("test");
    Assertions.assertThat(publicNode.get("extension")).isEqualTo("txt");
    Assertions.assertThat(publicNode.get("type")).isEqualTo(NodeType.TEXT.toString());
    Assertions.assertThat(publicNode.get("mime_type")).isEqualTo("text/plain");
    Assertions.assertThat(publicNode.get("size")).isEqualTo(5.0);
  }

  @Test
  void
      givenANotExistingPublicLinkIdAndAnExistingFolderTheGetPublicNodeShouldReturn200StatusCodeWithAnErrorMessage() {
    // Given
    seedFolder("folder", LOCAL_ROOT, OWNER_COOKIE);
    String nonExistentPublicId = "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234ab";

    // When
    Response response = getPublicNode(nonExistentPublicId, null, "{ id }");

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errorMessages = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errorMessages)
        .hasSize(1)
        .containsExactly("Could not find link with id " + nonExistentPublicId);
  }

  @Test
  void
      givenAnExpiredPublicLinkIdAndAnExistingFolderTheGetPublicNodeShouldReturn200StatusCodeWithAnErrorMessage() {
    // Given — expires_at is a raw millis passthrough; 1 is already in the past
    String nodeId = seedFolder("folder", LOCAL_ROOT, OWNER_COOKIE);
    String publicId = createLinkAndGetPublicId(nodeId, 1, null, OWNER_COOKIE);

    // When
    Response response = getPublicNode(publicId, null, "{ id }");

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errorMessages = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errorMessages)
        .hasSize(1)
        .containsExactly("Could not find link with id " + publicId);
  }

  @Test
  void
      givenAPublicLinkIdWithAccessCodeAndAnExistingFolderTheGetPublicNodeWithCorrectCodeShouldReturnThePublicFolder() {
    // Given
    long now = System.currentTimeMillis();
    String nodeId = seedFolder("folder", LOCAL_ROOT, OWNER_COOKIE);
    String publicId = createLinkAndGetPublicId(nodeId, null, "fake-access-code", OWNER_COOKIE);

    // When
    Response response =
        getPublicNode(publicId, "fake-access-code", "{ id created_at updated_at name type }");

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Map<String, Object> publicNode =
        TestUtils.jsonResponseToMap(response.getBody().asString(), "getPublicNode");

    Assertions.assertThat(publicNode.get("id")).isEqualTo(nodeId);
    Assertions.assertThat((long) publicNode.get("created_at")).isGreaterThanOrEqualTo(now);
    Assertions.assertThat((long) publicNode.get("updated_at")).isGreaterThanOrEqualTo(now);
    Assertions.assertThat(publicNode.get("name")).isEqualTo("folder");
    Assertions.assertThat(publicNode.get("type")).isEqualTo(NodeType.FOLDER.toString());
  }

  @Test
  void
      givenAPublicLinkIdWithAccessCodeAndAnExistingFolderTheGetPublicNodeWithWrongCodeShouldReturnAnErrorMessage() {
    // Given
    String nodeId = seedFolder("folder", LOCAL_ROOT, OWNER_COOKIE);
    String publicId = createLinkAndGetPublicId(nodeId, null, "fake-access-code", OWNER_COOKIE);

    // When
    Response response =
        getPublicNode(publicId, "wrong-access-code", "{ id created_at updated_at name type }");

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errorMessages = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errorMessages)
        .hasSize(1)
        .containsExactly("The access code for link with public id " + publicId + " is not correct");
  }

  @Test
  void
      givenAPublicLinkIdWithAccessCodeAndAnExistingFolderTheGetPublicNodeWithNoCodeShouldReturnAnErrorMessage() {
    // Given
    String nodeId = seedFolder("folder", LOCAL_ROOT, OWNER_COOKIE);
    String publicId = createLinkAndGetPublicId(nodeId, null, "fake-access-code", OWNER_COOKIE);

    // When
    Response response = getPublicNode(publicId, null, "{ id created_at updated_at name type }");

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errorMessages = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errorMessages)
        .hasSize(1)
        .containsExactly(
            "Access code is required for accessing the resource with public link id: " + publicId);
  }
}
