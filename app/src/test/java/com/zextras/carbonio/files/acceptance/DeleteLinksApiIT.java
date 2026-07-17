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
import com.zextras.carbonio.files.api.utilities.entities.SimplePopulatorTextFile;
import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import com.zextras.carbonio.files.utilities.http.HttpRequest;
import com.zextras.carbonio.files.utilities.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * NEW canonical file: the {@code deleteLinks} mutation (bound to {@code
 * LinkDataFetcher#deleteLinks()}) had ZERO branch coverage before this file — {@code
 * ValidationErrorsApiIT} only exercises the request-VALIDATION layer (invalid link-id length),
 * which rejects the request before the data fetcher's own body ever runs.
 *
 * <p>Also closes two small {@code LinkDataFetcher} gaps opportunistically:
 *
 * <ul>
 *   <li>{@code getLinks} as a FIELD RESOLVER ({@code File.links}) — every existing {@code
 *       GetPublicLinksApiIT} test only calls the TOP-LEVEL {@code getLinks(node_id: ...)} query
 *       (argument-driven), never the {@code links} field on a {@code getNode} result
 *       (localContext-driven) — the two paths are the same fetcher instance but read the node id
 *       from a different source ({@code getLinks()}'s {@code optLocalContext.isPresent()} ternary).
 *   <li>{@code createLink} targeting {@code LOCAL_ROOT} itself (a {@code ROOT} node) — {@code
 *       createLink} explicitly excludes {@code NodeType.ROOT} even when the permission check would
 *       otherwise pass, a branch no existing {@code CreatePublicLinkApiIT} test reaches (every one
 *       of them targets a real file/folder).
 * </ul>
 */
@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class DeleteLinksApiIT {

  static FilesTestApp app;

  private static final String OWNER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OTHER_USER_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String OWNER_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String OTHER_COOKIE = "ZM_AUTH_TOKEN=fake-token-b";

  @BeforeAll
  static void init() {
    app =
        QuarkusFilesTestAppBuilder.aFilesTestApp()
            .withDatabase()
            .withServiceDiscover()
            .withUserManagement(
                Map.of(
                    "fake-token", OWNER_ID,
                    "fake-token-b", OTHER_USER_ID))
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

  private void addLink(String linkId, String nodeId) {
    app.backdoor()
        .populator()
        .addLink(linkId, nodeId, "publicid1234", Optional.empty(), Optional.empty(), Optional.empty());
  }

  private HttpResponse deleteLinks(String[] linkIds, String cookie) {
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("deleteLinks")
            .withListOfStrings("link_ids", linkIds)
            .withWantedResultFormat("")
            .build();
    return app.send(HttpRequest.of("POST", "/graphql/", cookie, bodyPayload));
  }

  @Test
  void givenShareRightsDeleteLinksShouldDeleteTheLinkAndReturnItsId() {
    // Given
    String nodeId = "90000000-0000-0000-0000-000000000001";
    String linkId = "aaaaaaaa-0000-0000-0000-000000000001";
    createFile(nodeId, OWNER_ID);
    addLink(linkId, nodeId);

    // When
    HttpResponse httpResponse = deleteLinks(new String[] {linkId}, OWNER_COOKIE);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    List<String> deletedIds =
        (List<String>) TestUtils.jsonResponseToValue(httpResponse.getBodyPayload(), "deleteLinks")
            .orElse(List.of());
    Assertions.assertThat(deletedIds).containsExactly(linkId);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload())).isEmpty();
  }

  @Test
  void givenNoShareRightsDeleteLinksShouldReturnLinkNotFoundError() {
    // Given — link exists, but the caller has no READ_AND_SHARE on its node
    String nodeId = "90000000-0000-0000-0000-000000000002";
    String linkId = "aaaaaaaa-0000-0000-0000-000000000002";
    createFile(nodeId, OWNER_ID);
    addLink(linkId, nodeId);

    // When
    HttpResponse httpResponse = deleteLinks(new String[] {linkId}, OTHER_COOKIE);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    List<String> deletedIds =
        (List<String>) TestUtils.jsonResponseToValue(httpResponse.getBodyPayload(), "deleteLinks")
            .orElse(List.of());
    Assertions.assertThat(deletedIds).isEmpty();
    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors).hasSize(1).containsExactly("Could not find link with id " + linkId);
  }

  @Test
  void givenANonExistentLinkIdDeleteLinksShouldReturnLinkNotFoundError() {
    // Given
    String nonExistentLinkId = "aaaaaaaa-0000-0000-0000-0000000000ff";

    // When
    HttpResponse httpResponse = deleteLinks(new String[] {nonExistentLinkId}, OWNER_COOKIE);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("Could not find link with id " + nonExistentLinkId);
  }

  @Test
  void givenAMixOfDeletableAndForbiddenLinksDeleteLinksShouldReturnPartialSuccess() {
    // Given — one link the caller can delete (own node), one they cannot (someone else's node)
    String ownNodeId = "90000000-0000-0000-0000-000000000003";
    String otherNodeId = "90000000-0000-0000-0000-000000000004";
    String ownLinkId = "aaaaaaaa-0000-0000-0000-000000000003";
    String otherLinkId = "aaaaaaaa-0000-0000-0000-000000000004";
    createFile(ownNodeId, OWNER_ID);
    createFile(otherNodeId, OTHER_USER_ID);
    addLink(ownLinkId, ownNodeId);
    addLink(otherLinkId, otherNodeId);

    // When
    HttpResponse httpResponse = deleteLinks(new String[] {ownLinkId, otherLinkId}, OWNER_COOKIE);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    List<String> deletedIds =
        (List<String>) TestUtils.jsonResponseToValue(httpResponse.getBodyPayload(), "deleteLinks")
            .orElse(List.of());
    Assertions.assertThat(deletedIds).containsExactly(ownLinkId);
    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("Could not find link with id " + otherLinkId);
  }

  @Test
  @SuppressWarnings("unchecked")
  void givenAFileWithALinkTheLinksFieldResolverShouldReturnItFromLocalContextNotAnArgument() {
    // Given — the `File.links` field resolver (getNode -> links), as opposed to the top-level
    // getLinks(node_id: ...) query every GetPublicLinksApiIT test uses
    String nodeId = "90000000-0000-0000-0000-000000000005";
    String linkId = "aaaaaaaa-0000-0000-0000-000000000005";
    createFile(nodeId, OWNER_ID);
    addLink(linkId, nodeId);

    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("getNode")
            .withString("node_id", nodeId)
            .withWantedResultFormat("{ id links { id } }")
            .build();

    // When
    HttpResponse httpResponse =
        app.send(HttpRequest.of("POST", "/graphql/", OWNER_COOKIE, bodyPayload));

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Map<String, Object> node = TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "getNode");
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
            .withString("node_id", "LOCAL_ROOT")
            .withWantedResultFormat("{ id }")
            .build();

    // When
    HttpResponse httpResponse =
        app.send(HttpRequest.of("POST", "/graphql/", OWNER_COOKIE, bodyPayload));

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("There was a problem while executing requested operation on node: LOCAL_ROOT");
  }
}
