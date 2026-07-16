// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.acceptance;

import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.acceptance.seam.FilesTestApp;
import com.zextras.carbonio.files.acceptance.seam.impl.GuiceNettyFilesTestAppBuilder;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.api.utilities.entities.SimplePopulatorTextFile;
import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import com.zextras.carbonio.files.utilities.http.HttpRequest;
import com.zextras.carbonio.files.utilities.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Task 3.3 of the acceptance coverage-expansion plan: {@code deleteCollaborationLinks} mutation.
 *
 * <p><b>FINDING (documented, not fixed):</b> {@code
 * CollaborationLinkDataFetcher#deleteCollaborationLinks} computes "forbidden" as {@code
 * collaborationLinkRepository.getLinkById(id).filter(requesterHasShareRights).isEmpty()} — this
 * single boolean conflates TWO distinct causes (the id does not exist at all, and the id exists
 * but the requester lacks {@code READ_AND_SHARE}/{@code READ_WRITE_AND_SHARE} on its node) into
 * the SAME generic {@code missingField} error ("Could not find data to retrieve for requested
 * field", {@code errorCode: MISSING_FIELD}) with no id-specific detail — a genuine not-found and a
 * genuine permission-denial are indistinguishable over HTTP. Both scenarios are asserted below
 * with their real, identical shape; see also the analogous {@code
 * AuthenticatedDownloadApiIT}/{@code GetPublicLinksApiIT} not-found-vs-forbidden findings.
 */
class DeleteCollaborationLinksApiIT {

  static FilesTestApp app;

  private static final String OWNER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String SHARE_TARGET_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String NODE_ID = "00000000-0000-0000-0000-000000000000";

  @BeforeAll
  static void init() {
    app =
        GuiceNettyFilesTestAppBuilder.aFilesTestApp()
            .withDatabase()
            .withServiceDiscover()
            .withUserManagement(
                Map.of(
                    "fake-token", OWNER_ID,
                    "fake-token-b", SHARE_TARGET_ID))
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

  private void createShare(String nodeId, String targetUserId, SharePermission permission) {
    app.backdoor().populator().addShare(nodeId, targetUserId, permission);
  }

  private String createCollaborationLink(String nodeId, SharePermission permission) {
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("createCollaborationLink")
            .withString("node_id", nodeId)
            .withEnumLiteral("permission", permission.name())
            .withWantedResultFormat("{ id }")
            .build();
    HttpResponse response =
        app.send(HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload));
    Assertions.assertThat(response.getStatus()).isEqualTo(200);
    return (String)
        TestUtils.jsonResponseToMap(response.getBodyPayload(), "createCollaborationLink").get("id");
  }

  private HttpResponse deleteCollaborationLinks(String cookie, String... linkIds) {
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("deleteCollaborationLinks")
            .withListOfStrings("collaboration_link_ids", linkIds)
            .withWantedResultFormat("")
            .build();
    return app.send(HttpRequest.of("POST", "/graphql/", cookie, bodyPayload));
  }

  private List<String> remainingLinkIds(String nodeId) {
    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("getCollaborationLinks")
            .withString("node_id", nodeId)
            .withWantedResultFormat("{ id }")
            .build();
    HttpResponse response =
        app.send(HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload));
    return TestUtils.jsonResponseToList(response.getBodyPayload(), "getCollaborationLinks").stream()
        .map(link -> (String) link.get("id"))
        .toList();
  }

  @Test
  void givenAnOwnedLinkTheDeleteCollaborationLinksShouldDeleteItAndReturnItsId() {
    // Given
    createFile(NODE_ID, OWNER_ID);
    String linkId = createCollaborationLink(NODE_ID, SharePermission.READ_AND_SHARE);

    // When
    HttpResponse httpResponse = deleteCollaborationLinks("ZM_AUTH_TOKEN=fake-token", linkId);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    List<String> deletedIds =
        (List<String>)
            TestUtils.jsonResponseToValue(httpResponse.getBodyPayload(), "deleteCollaborationLinks")
                .orElse(List.of());
    Assertions.assertThat(deletedIds).containsExactly(linkId);

    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors).isEmpty();

    Assertions.assertThat(remainingLinkIds(NODE_ID)).doesNotContain(linkId);
  }

  @Test
  void givenAForbiddenLinkTheDeleteCollaborationLinksShouldReturnAMissingFieldError() {
    // Given — the share target has READ_ONLY (no SHARE bit) on the node that owns the link
    createFile(NODE_ID, OWNER_ID);
    String linkId = createCollaborationLink(NODE_ID, SharePermission.READ_AND_SHARE);
    createShare(NODE_ID, SHARE_TARGET_ID, SharePermission.READ_ONLY);

    // When
    HttpResponse httpResponse = deleteCollaborationLinks("ZM_AUTH_TOKEN=fake-token-b", linkId);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    List<String> deletedIds =
        (List<String>)
            TestUtils.jsonResponseToValue(httpResponse.getBodyPayload(), "deleteCollaborationLinks")
                .orElse(List.of());
    Assertions.assertThat(deletedIds).isEmpty();

    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("Could not find data to retrieve for requested field");

    // The link is untouched.
    Assertions.assertThat(remainingLinkIds(NODE_ID)).containsExactly(linkId);
  }

  @Test
  void givenANotExistingLinkIdTheDeleteCollaborationLinksShouldReturnTheSameMissingFieldError() {
    // Given — see the class-level FINDING: not-found collapses into the identical shape as
    // permission-denied
    String notExistingLinkId = UUID.randomUUID().toString();

    // When
    HttpResponse httpResponse =
        deleteCollaborationLinks("ZM_AUTH_TOKEN=fake-token", notExistingLinkId);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    List<String> deletedIds =
        (List<String>)
            TestUtils.jsonResponseToValue(httpResponse.getBodyPayload(), "deleteCollaborationLinks")
                .orElse(List.of());
    Assertions.assertThat(deletedIds).isEmpty();

    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("Could not find data to retrieve for requested field");
  }

  @Test
  void givenAnAllowedAndAForbiddenLinkTheDeleteCollaborationLinksShouldReturnAPartialSuccess() {
    // Given — one link the owner may delete, one forbidden id (not-found) in the same call
    createFile(NODE_ID, OWNER_ID);
    String allowedLinkId = createCollaborationLink(NODE_ID, SharePermission.READ_AND_SHARE);
    String forbiddenLinkId = UUID.randomUUID().toString();

    // When
    HttpResponse httpResponse =
        deleteCollaborationLinks("ZM_AUTH_TOKEN=fake-token", allowedLinkId, forbiddenLinkId);

    // Then — the allowed id is deleted and returned, the forbidden one produces one error
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    List<String> deletedIds =
        (List<String>)
            TestUtils.jsonResponseToValue(httpResponse.getBodyPayload(), "deleteCollaborationLinks")
                .orElse(List.of());
    Assertions.assertThat(deletedIds).containsExactly(allowedLinkId);

    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("Could not find data to retrieve for requested field");

    Assertions.assertThat(remainingLinkIds(NODE_ID)).doesNotContain(allowedLinkId);
  }
}
