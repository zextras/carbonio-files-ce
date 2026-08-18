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
import org.junit.jupiter.api.Test;

/**
 * {@code com.zextras.carbonio.files.acceptance.DeleteLinksApiIT} rewritten as an out-of-process
 * {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}. All 6 methods and their assertions
 * are preserved verbatim; only the seeding mechanism (API calls capturing server-generated ids —
 * {@link #seedLink} replaces the seam's {@code DatabasePopulator#addLink} fixed-id backdoor) and
 * the transport changed.
 */
class DeleteLinksApiIT extends AbstractFilesIT {

  private static final String OWNER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OTHER_USER_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String OWNER_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String OTHER_COOKIE = "ZM_AUTH_TOKEN=fake-token-b";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", OWNER_ID);
    FilesStackTestResource.getUserManagementService().registerToken("fake-token-b", OTHER_USER_ID);
  }

  private Response deleteLinks(String[] linkIds, String cookie) {
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("deleteLinks")
            .withListOfStrings("link_ids", linkIds)
            .withWantedResultFormat("")
            .build();
    return graphql(bodyPayload, cookie);
  }

  @SuppressWarnings("unchecked")
  private List<String> deletedIds(Response response) {
    return (List<String>)
        TestUtils.jsonResponseToValue(response.getBody().asString(), "deleteLinks")
            .orElse(List.of());
  }

  @Test
  void givenShareRightsDeleteLinksShouldDeleteTheLinkAndReturnItsId() {
    // Given
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    String linkId = seedLink(nodeId, OWNER_COOKIE);

    // When
    Response response = deleteLinks(new String[] {linkId}, OWNER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(deletedIds(response)).containsExactly(linkId);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(response.getBody().asString())).isEmpty();
  }

  @Test
  void givenNoShareRightsDeleteLinksShouldReturnLinkNotFoundError() {
    // Given — link exists, but the caller has no READ_AND_SHARE on its node
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    String linkId = seedLink(nodeId, OWNER_COOKIE);

    // When
    Response response = deleteLinks(new String[] {linkId}, OTHER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(deletedIds(response)).isEmpty();
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("Could not find link with id " + linkId);
  }

  @Test
  void givenANonExistentLinkIdDeleteLinksShouldReturnLinkNotFoundError() {
    // Given
    String nonExistentLinkId = "aaaaaaaa-0000-0000-0000-0000000000ff";

    // When
    Response response = deleteLinks(new String[] {nonExistentLinkId}, OWNER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("Could not find link with id " + nonExistentLinkId);
  }

  @Test
  void givenAMixOfDeletableAndForbiddenLinksDeleteLinksShouldReturnPartialSuccess() {
    // Given — one link the caller can delete (own node), one they cannot (someone else's node)
    String ownNodeId =
        seedFile("own.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    String otherNodeId =
        seedFile("other.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OTHER_COOKIE);
    String ownLinkId = seedLink(ownNodeId, OWNER_COOKIE);
    String otherLinkId = seedLink(otherNodeId, OTHER_COOKIE);

    // When
    Response response = deleteLinks(new String[] {ownLinkId, otherLinkId}, OWNER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(deletedIds(response)).containsExactly(ownLinkId);
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("Could not find link with id " + otherLinkId);
  }

  @Test
  @SuppressWarnings("unchecked")
  void givenAFileWithALinkTheLinksFieldResolverShouldReturnItFromLocalContextNotAnArgument() {
    // Given — the `File.links` field resolver (getNode -> links), as opposed to the top-level
    // getLinks(node_id: ...) query every GetPublicLinksApiIT test uses
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    String linkId = seedLink(nodeId, OWNER_COOKIE);

    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("getNode")
            .withString("node_id", nodeId)
            .withWantedResultFormat("{ id links { id } }")
            .build();

    // When
    Response response = graphql(bodyPayload, OWNER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Map<String, Object> node =
        TestUtils.jsonResponseToMap(response.getBody().asString(), "getNode");
    List<Map<String, Object>> links = (List<Map<String, Object>>) node.get("links");
    Assertions.assertThat(links).hasSize(1);
    Assertions.assertThat(links.get(0)).containsEntry("id", linkId);
  }

  @Test
  void givenLocalRootAsTargetCreateLinkShouldReturnNodeWriteErrorNotCreateALink() {
    // Given — LOCAL_ROOT is a NodeType.ROOT; createLink explicitly excludes ROOT nodes even though
    // permissionsChecker grants some access to it
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("createLink")
            .withString("node_id", LOCAL_ROOT)
            .withWantedResultFormat("{ id }")
            .build();

    // When
    Response response = graphql(bodyPayload, OWNER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly(
            "There was a problem while executing requested operation on node: LOCAL_ROOT");
  }
}
