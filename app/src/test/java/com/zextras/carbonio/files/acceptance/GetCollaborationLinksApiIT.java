// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.acceptance;

import com.zextras.carbonio.files.FilesStackTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.acceptance.seam.FilesTestApp;
import com.zextras.carbonio.files.acceptance.seam.impl.QuarkusFilesTestAppBuilder;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.api.utilities.entities.SimplePopulatorFolder;
import com.zextras.carbonio.files.api.utilities.entities.SimplePopulatorTextFile;
import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import com.zextras.carbonio.files.utilities.http.HttpRequest;
import com.zextras.carbonio.files.utilities.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Task 3.2 of the acceptance coverage-expansion plan: {@code getCollaborationLinks} top-level
 * query AND the {@code File.collaboration_links}/{@code Folder.collaboration_links} field
 * resolvers — both are backed by the SAME {@code
 * CollaborationLinkDataFetcher#getCollaborationLinksByNodeId} lambda instance (the top-level query
 * reads the {@code node_id} argument, the field resolvers read the node id from local context).
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
@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class GetCollaborationLinksApiIT {

  static FilesTestApp app;

  private static final String OWNER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String READ_SHARE_TARGET_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String READ_WRITE_SHARE_TARGET_ID = "cccccccc-cccc-cccc-cccc-cccccccccccc";
  private static final String NODE_ID = "00000000-0000-0000-0000-000000000000";

  @BeforeAll
  static void init() {
    app =
        QuarkusFilesTestAppBuilder.aFilesTestApp()
            .withDatabase()
            .withServiceDiscover()
            .withUserManagement(
                Map.of(
                    "fake-token", OWNER_ID,
                    "fake-token-b", READ_SHARE_TARGET_ID,
                    "fake-token-c", READ_WRITE_SHARE_TARGET_ID))
            .build();
  }

  @AfterEach
  void cleanUp() {
    app.backdoor().resetDatabase();
  }

  @AfterAll
  static void cleanUpAll() {
    app.close();
  }

  private void createFile(String nodeId, String ownerId) {
    app.backdoor().populator().addNode(new SimplePopulatorTextFile(nodeId, ownerId));
  }

  private void createFolder(String nodeId, String ownerId) {
    app.backdoor().populator().addNode(new SimplePopulatorFolder(nodeId, ownerId));
  }

  private void createShare(String nodeId, String targetUserId, SharePermission permission) {
    app.backdoor().populator().addShare(nodeId, targetUserId, permission);
  }

  private void createCollaborationLink(String nodeId, SharePermission permission) {
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("createCollaborationLink")
            .withString("node_id", nodeId)
            .withEnumLiteral("permission", permission.name())
            .withWantedResultFormat("{ id }")
            .build();
    HttpResponse response =
        app.send(HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload));
    Assertions.assertThat(response.getStatus()).isEqualTo(200);
  }

  private HttpResponse getCollaborationLinks(String cookie, String nodeId) {
    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("getCollaborationLinks")
            .withString("node_id", nodeId)
            .withWantedResultFormat("{ id permission }")
            .build();
    return app.send(HttpRequest.of("POST", "/graphql/", cookie, bodyPayload));
  }

  @Test
  void givenAnOwnerAndBothPermissionTierLinksTheGetCollaborationLinksShouldReturnBoth() {
    // Given
    createFile(NODE_ID, OWNER_ID);
    createCollaborationLink(NODE_ID, SharePermission.READ_AND_SHARE);
    createCollaborationLink(NODE_ID, SharePermission.READ_WRITE_AND_SHARE);

    // When — the owner's ACL is a superset of every tier
    HttpResponse httpResponse = getCollaborationLinks("ZM_AUTH_TOKEN=fake-token", NODE_ID);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    List<Map<String, Object>> links =
        TestUtils.jsonResponseToList(httpResponse.getBodyPayload(), "getCollaborationLinks");
    Assertions.assertThat(links)
        .hasSize(2)
        .extracting(link -> link.get("permission"))
        .containsExactlyInAnyOrder("READ_AND_SHARE", "READ_WRITE_AND_SHARE");
  }

  @Test
  void givenAShareTargetWithReadAndShareOnlyTheGetCollaborationLinksShouldReturnOnlyThatTier() {
    // Given — both tiers exist, but the requester's own ACL only covers READ_AND_SHARE
    createFile(NODE_ID, OWNER_ID);
    createCollaborationLink(NODE_ID, SharePermission.READ_AND_SHARE);
    createCollaborationLink(NODE_ID, SharePermission.READ_WRITE_AND_SHARE);
    createShare(NODE_ID, READ_SHARE_TARGET_ID, SharePermission.READ_AND_SHARE);

    // When
    HttpResponse httpResponse = getCollaborationLinks("ZM_AUTH_TOKEN=fake-token-b", NODE_ID);

    // Then — the READ_WRITE_AND_SHARE-tier link is invisible to this requester
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    List<Map<String, Object>> links =
        TestUtils.jsonResponseToList(httpResponse.getBodyPayload(), "getCollaborationLinks");
    Assertions.assertThat(links).hasSize(1);
    Assertions.assertThat(links.get(0)).containsEntry("permission", "READ_AND_SHARE");
  }

  @Test
  void givenAShareTargetWithReadWriteAndShareTheGetCollaborationLinksShouldReturnBothTiers() {
    // Given — a READ_WRITE_AND_SHARE ACL is a bitwise superset of both existing links' tiers
    createFile(NODE_ID, OWNER_ID);
    createCollaborationLink(NODE_ID, SharePermission.READ_AND_SHARE);
    createCollaborationLink(NODE_ID, SharePermission.READ_WRITE_AND_SHARE);
    createShare(NODE_ID, READ_WRITE_SHARE_TARGET_ID, SharePermission.READ_WRITE_AND_SHARE);

    // When
    HttpResponse httpResponse = getCollaborationLinks("ZM_AUTH_TOKEN=fake-token-c", NODE_ID);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    List<Map<String, Object>> links =
        TestUtils.jsonResponseToList(httpResponse.getBodyPayload(), "getCollaborationLinks");
    Assertions.assertThat(links)
        .hasSize(2)
        .extracting(link -> link.get("permission"))
        .containsExactlyInAnyOrder("READ_AND_SHARE", "READ_WRITE_AND_SHARE");
  }

  @Test
  void givenAShareTargetWithoutShareRightsTheGetCollaborationLinksShouldReturnAPermissionDeniedError() {
    // Given — READ_ONLY carries no SHARE bit, so the requester never passes the top-level gate
    createFile(NODE_ID, OWNER_ID);
    createCollaborationLink(NODE_ID, SharePermission.READ_AND_SHARE);
    createShare(NODE_ID, READ_SHARE_TARGET_ID, SharePermission.READ_ONLY);

    // When
    HttpResponse httpResponse = getCollaborationLinks("ZM_AUTH_TOKEN=fake-token-b", NODE_ID);

    // Then — data is a one-element list whose only element is null, plus one top-level error
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    List<Map<String, Object>> links =
        TestUtils.jsonResponseToList(httpResponse.getBodyPayload(), "getCollaborationLinks");
    Assertions.assertThat(links).hasSize(1);
    Assertions.assertThat(links.get(0)).isNull();

    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly(
            "There was a problem while executing requested operation on node: " + NODE_ID);
  }

  @SuppressWarnings("unchecked")
  @Test
  void givenAnOwnerTheFileCollaborationLinksFieldShouldResolveBothLinks() {
    // Given — the File.collaboration_links field-resolver path (getNode -> collaboration_links)
    createFile(NODE_ID, OWNER_ID);
    createCollaborationLink(NODE_ID, SharePermission.READ_AND_SHARE);
    createCollaborationLink(NODE_ID, SharePermission.READ_WRITE_AND_SHARE);

    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("getNode")
            .withString("node_id", NODE_ID)
            .withWantedResultFormat("{ id collaboration_links { id permission } }")
            .build();
    HttpResponse httpResponse =
        app.send(HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload));

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Map<String, Object> node = TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "getNode");
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
    createFolder(NODE_ID, OWNER_ID);
    createCollaborationLink(NODE_ID, SharePermission.READ_AND_SHARE);

    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("getNode")
            .withString("node_id", NODE_ID)
            .withWantedResultFormat("{ id collaboration_links { id permission } }")
            .build();
    HttpResponse httpResponse =
        app.send(HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload));

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Map<String, Object> node = TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "getNode");
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
    createFile(NODE_ID, OWNER_ID);
    createCollaborationLink(NODE_ID, SharePermission.READ_AND_SHARE);
    createShare(NODE_ID, READ_SHARE_TARGET_ID, SharePermission.READ_ONLY);

    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("getNode")
            .withString("node_id", NODE_ID)
            .withWantedResultFormat("{ id collaboration_links { id permission } }")
            .build();
    HttpResponse httpResponse =
        app.send(HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token-b", bodyPayload));

    // Then — the node itself resolves (id present), only the sub-field errors
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Map<String, Object> node = TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "getNode");
    Assertions.assertThat(node).containsEntry("id", NODE_ID);
    List<Map<String, Object>> links = (List<Map<String, Object>>) node.get("collaboration_links");
    Assertions.assertThat(links).hasSize(1);
    Assertions.assertThat(links.get(0)).isNull();

    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly(
            "There was a problem while executing requested operation on node: " + NODE_ID);
  }
}
