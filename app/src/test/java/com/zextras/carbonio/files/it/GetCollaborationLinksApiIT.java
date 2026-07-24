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
 * {@code com.zextras.carbonio.files.acceptance.GetCollaborationLinksApiIT} rewritten as an
 * out-of-process {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}. All 7 methods and
 * their assertions are preserved verbatim; only the seeding mechanism (API calls capturing
 * server-generated ids) and the transport changed.
 *
 * <p><b>Visibility rule:</b> {@code getCollaborationLinksByNodeId} requires the requester to hold
 * at least {@code READ_AND_SHARE} or {@code READ_WRITE_AND_SHARE}; once past that gate, the
 * returned links are further filtered to only those whose permission tier the requester's own ACL
 * is a bitwise superset of ({@code permissions.has(collaborationLink.getPermissions())}) — a
 * {@code READ_AND_SHARE} sharer sees ONLY the {@code READ_AND_SHARE}-tier link even when a {@code
 * READ_WRITE_AND_SHARE}-tier link also exists on the same node, while a {@code
 * READ_WRITE_AND_SHARE} sharer (a bitwise superset of both) sees both.
 *
 * <p><b>Permission-denied shape:</b> the fetcher returns {@code
 * Collections.singletonList(DataFetcherResult.error(nodeWriteError))} — i.e. the GraphQL response
 * has data {@code [null]} (a one-element list whose only element is {@code null}) PLUS a top-level
 * {@code nodeWriteError} in {@code errors}, mirroring the identical pattern already documented for
 * {@code getLinks} in {@code GetPublicLinksApiIT}.
 */
class GetCollaborationLinksApiIT extends AbstractFilesIT {

  private static final String OWNER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String READ_SHARE_TARGET_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String READ_WRITE_SHARE_TARGET_ID = "cccccccc-cccc-cccc-cccc-cccccccccccc";
  private static final String OWNER_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String READ_SHARE_TARGET_COOKIE = "ZM_AUTH_TOKEN=fake-token-b";
  private static final String READ_WRITE_SHARE_TARGET_COOKIE = "ZM_AUTH_TOKEN=fake-token-c";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", OWNER_ID);
    FilesStackTestResource.getUserManagementService()
        .registerToken("fake-token-b", READ_SHARE_TARGET_ID);
    FilesStackTestResource.getUserManagementService()
        .registerToken("fake-token-c", READ_WRITE_SHARE_TARGET_ID);
  }

  private void createCollaborationLink(String nodeId, SharePermission permission) {
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("createCollaborationLink")
            .withString("node_id", nodeId)
            .withEnumLiteral("permission", permission.name())
            .withWantedResultFormat("{ id }")
            .build();
    Response response = graphql(bodyPayload, OWNER_COOKIE);
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
  }

  private Response getCollaborationLinks(String cookie, String nodeId) {
    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("getCollaborationLinks")
            .withString("node_id", nodeId)
            .withWantedResultFormat("{ id permission }")
            .build();
    return graphql(bodyPayload, cookie);
  }

  @Test
  void givenAnOwnerAndBothPermissionTierLinksTheGetCollaborationLinksShouldReturnBoth() {
    // Given
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    createCollaborationLink(nodeId, SharePermission.READ_AND_SHARE);
    createCollaborationLink(nodeId, SharePermission.READ_WRITE_AND_SHARE);

    // When — the owner's ACL is a superset of every tier
    Response response = getCollaborationLinks(OWNER_COOKIE, nodeId);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<Map<String, Object>> links =
        TestUtils.jsonResponseToList(response.getBody().asString(), "getCollaborationLinks");
    Assertions.assertThat(links)
        .hasSize(2)
        .extracting(link -> link.get("permission"))
        .containsExactlyInAnyOrder("READ_AND_SHARE", "READ_WRITE_AND_SHARE");
  }

  @Test
  void givenAShareTargetWithReadAndShareOnlyTheGetCollaborationLinksShouldReturnOnlyThatTier() {
    // Given — both tiers exist, but the requester's own ACL only covers READ_AND_SHARE
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    createCollaborationLink(nodeId, SharePermission.READ_AND_SHARE);
    createCollaborationLink(nodeId, SharePermission.READ_WRITE_AND_SHARE);
    seedShare(nodeId, READ_SHARE_TARGET_ID, SharePermission.READ_AND_SHARE, OWNER_COOKIE);

    // When
    Response response = getCollaborationLinks(READ_SHARE_TARGET_COOKIE, nodeId);

    // Then — the READ_WRITE_AND_SHARE-tier link is invisible to this requester
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<Map<String, Object>> links =
        TestUtils.jsonResponseToList(response.getBody().asString(), "getCollaborationLinks");
    Assertions.assertThat(links).hasSize(1);
    Assertions.assertThat(links.get(0)).containsEntry("permission", "READ_AND_SHARE");
  }

  @Test
  void givenAShareTargetWithReadWriteAndShareTheGetCollaborationLinksShouldReturnBothTiers() {
    // Given — a READ_WRITE_AND_SHARE ACL is a bitwise superset of both existing links' tiers
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    createCollaborationLink(nodeId, SharePermission.READ_AND_SHARE);
    createCollaborationLink(nodeId, SharePermission.READ_WRITE_AND_SHARE);
    seedShare(nodeId, READ_WRITE_SHARE_TARGET_ID, SharePermission.READ_WRITE_AND_SHARE, OWNER_COOKIE);

    // When
    Response response = getCollaborationLinks(READ_WRITE_SHARE_TARGET_COOKIE, nodeId);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<Map<String, Object>> links =
        TestUtils.jsonResponseToList(response.getBody().asString(), "getCollaborationLinks");
    Assertions.assertThat(links)
        .hasSize(2)
        .extracting(link -> link.get("permission"))
        .containsExactlyInAnyOrder("READ_AND_SHARE", "READ_WRITE_AND_SHARE");
  }

  @Test
  void givenAShareTargetWithoutShareRightsTheGetCollaborationLinksShouldReturnAPermissionDeniedError() {
    // Given — READ_ONLY carries no SHARE bit, so the requester never passes the top-level gate
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    createCollaborationLink(nodeId, SharePermission.READ_AND_SHARE);
    seedShare(nodeId, READ_SHARE_TARGET_ID, SharePermission.READ_ONLY, OWNER_COOKIE);

    // When
    Response response = getCollaborationLinks(READ_SHARE_TARGET_COOKIE, nodeId);

    // Then — data is a one-element list whose only element is null, plus one top-level error
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<Map<String, Object>> links =
        TestUtils.jsonResponseToList(response.getBody().asString(), "getCollaborationLinks");
    Assertions.assertThat(links).hasSize(1);
    Assertions.assertThat(links.get(0)).isNull();

    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("There was a problem while executing requested operation on node: " + nodeId);
  }

  @SuppressWarnings("unchecked")
  @Test
  void givenAnOwnerTheFileCollaborationLinksFieldShouldResolveBothLinks() {
    // Given — the File.collaboration_links field-resolver path (getNode -> collaboration_links)
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    createCollaborationLink(nodeId, SharePermission.READ_AND_SHARE);
    createCollaborationLink(nodeId, SharePermission.READ_WRITE_AND_SHARE);

    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("getNode")
            .withString("node_id", nodeId)
            .withWantedResultFormat("{ id collaboration_links { id permission } }")
            .build();
    Response response = graphql(bodyPayload, OWNER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Map<String, Object> node = TestUtils.jsonResponseToMap(response.getBody().asString(), "getNode");
    List<Map<String, Object>> links = (List<Map<String, Object>>) node.get("collaboration_links");
    Assertions.assertThat(links)
        .hasSize(2)
        .extracting(link -> link.get("permission"))
        .containsExactlyInAnyOrder("READ_AND_SHARE", "READ_WRITE_AND_SHARE");
  }

  @SuppressWarnings("unchecked")
  @Test
  void givenAnOwnerTheFolderCollaborationLinksFieldShouldResolveTheExistingLink() {
    // Given — the Folder.collaboration_links field-resolver path (distinct type-wiring entry from
    // File, same underlying fetcher instance)
    String nodeId = seedFolder("folder", LOCAL_ROOT, OWNER_COOKIE);
    createCollaborationLink(nodeId, SharePermission.READ_AND_SHARE);

    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("getNode")
            .withString("node_id", nodeId)
            .withWantedResultFormat("{ id collaboration_links { id permission } }")
            .build();
    Response response = graphql(bodyPayload, OWNER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Map<String, Object> node = TestUtils.jsonResponseToMap(response.getBody().asString(), "getNode");
    List<Map<String, Object>> links = (List<Map<String, Object>>) node.get("collaboration_links");
    Assertions.assertThat(links).hasSize(1);
    Assertions.assertThat(links.get(0)).containsEntry("permission", "READ_AND_SHARE");
  }

  @SuppressWarnings("unchecked")
  @Test
  void givenAShareTargetWithoutShareRightsTheFileCollaborationLinksFieldShouldReturnAScopedError() {
    // Given — the requester passes getNode's own top-level READ_ONLY gate (see
    // NodePermissionsFieldApiIT's finding) but fails the collaboration_links sub-field's own
    // READ_AND_SHARE/READ_WRITE_AND_SHARE gate
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    createCollaborationLink(nodeId, SharePermission.READ_AND_SHARE);
    seedShare(nodeId, READ_SHARE_TARGET_ID, SharePermission.READ_ONLY, OWNER_COOKIE);

    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("getNode")
            .withString("node_id", nodeId)
            .withWantedResultFormat("{ id collaboration_links { id permission } }")
            .build();
    Response response = graphql(bodyPayload, READ_SHARE_TARGET_COOKIE);

    // Then — the node itself resolves (id present), only the sub-field errors
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Map<String, Object> node = TestUtils.jsonResponseToMap(response.getBody().asString(), "getNode");
    Assertions.assertThat(node).containsEntry("id", nodeId);
    List<Map<String, Object>> links = (List<Map<String, Object>>) node.get("collaboration_links");
    Assertions.assertThat(links).hasSize(1);
    Assertions.assertThat(links.get(0)).isNull();

    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("There was a problem while executing requested operation on node: " + nodeId);
  }
}
