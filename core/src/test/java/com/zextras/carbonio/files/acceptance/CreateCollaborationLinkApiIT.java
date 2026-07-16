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
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Task 3.1 of the acceptance coverage-expansion plan: {@code createCollaborationLink} mutation,
 * exercising {@code CollaborationLinkDataFetcher#createCollaborationLink} (0% before this file).
 *
 * <p><b>URL shape:</b> {@code <domain><Endpoints.COLLABORATION_LINK_URL><8-char invitationId>}
 * (here {@code example.com/services/files/invite/<8 chars>}). The invitation id is deliberately
 * 8 alphanumeric characters — distinct from the 36-char UUID {@code id} field of the link itself
 * — matching {@code CollaborationLinkRepository#createLink}'s {@code invitationId} contract (see
 * also {@code InviteRedirectApiIT}, which consumes this same 8-char id via {@code GET
 * /invite/{id}}).
 *
 * <p><b>Permission gate:</b> {@code CollaborationLinkDataFetcher#createCollaborationLink} checks
 * {@code permissionsChecker.getPermissions(nodeId, requesterId).has(permission)} where {@code
 * permission} is literally the mutation's {@code permission} argument — the requester must
 * ALREADY hold (as a bitwise subset of their own ACL) the exact tier they are requesting a link
 * for. A missing node maps to {@code ACL.NONE} (see {@code PermissionsChecker#getPermissions}), so
 * a non-existing node collapses into the identical {@code nodeWriteError} shape as a genuine
 * permission denial — asserted explicitly below rather than assumed.
 */
class CreateCollaborationLinkApiIT {

  static FilesTestApp app;

  private static final String OWNER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String SHARE_TARGET_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String NODE_ID = "00000000-0000-0000-0000-000000000000";
  private static final String URL_PREFIX = "example.com/services/files/invite/";

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

  private HttpResponse createCollaborationLink(
      String cookie, String nodeId, SharePermission permission) {
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("createCollaborationLink")
            .withString("node_id", nodeId)
            .withEnumLiteral("permission", permission.name())
            .withWantedResultFormat("{ id url created_at permission node { id } }")
            .build();
    return app.send(HttpRequest.of("POST", "/graphql/", cookie, bodyPayload));
  }

  @SuppressWarnings("unchecked")
  @Test
  void givenAnOwnerTheCreateCollaborationLinkShouldCreateANewLinkWithTheExpectedShape() {
    // Given
    createFile(NODE_ID, OWNER_ID);

    // When
    HttpResponse httpResponse =
        createCollaborationLink(
            "ZM_AUTH_TOKEN=fake-token", NODE_ID, SharePermission.READ_AND_SHARE);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Map<String, Object> link =
        TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "createCollaborationLink");

    String id = (String) link.get("id");
    String url = (String) link.get("url");
    Assertions.assertThat(id).isNotNull().hasSize(36);
    Assertions.assertThat(url).startsWith(URL_PREFIX).hasSize(URL_PREFIX.length() + 8);
    Assertions.assertThat(link).containsEntry("permission", "READ_AND_SHARE");
    Assertions.assertThat((Map<String, Object>) link.get("node")).containsEntry("id", NODE_ID);
    Assertions.assertThat(link.get("created_at")).isNotNull();
  }

  @Test
  void givenAnExistingLinkWithTheSamePermissionTheCreateCollaborationLinkShouldReturnItIdempotently() {
    // Given
    createFile(NODE_ID, OWNER_ID);
    HttpResponse first =
        createCollaborationLink(
            "ZM_AUTH_TOKEN=fake-token", NODE_ID, SharePermission.READ_AND_SHARE);
    String firstId =
        (String)
            TestUtils.jsonResponseToMap(first.getBodyPayload(), "createCollaborationLink")
                .get("id");

    // When — same node, same requester, same permission tier requested again
    HttpResponse second =
        createCollaborationLink(
            "ZM_AUTH_TOKEN=fake-token", NODE_ID, SharePermission.READ_AND_SHARE);

    // Then — the system returns the EXISTING link, it does not create a duplicate
    Assertions.assertThat(second.getStatus()).isEqualTo(200);
    Map<String, Object> link =
        TestUtils.jsonResponseToMap(second.getBodyPayload(), "createCollaborationLink");
    Assertions.assertThat(link.get("id")).isEqualTo(firstId);
  }

  @Test
  void givenADifferentPermissionTheCreateCollaborationLinkShouldCreateASecondDistinctLink() {
    // Given — a node can have at most 2 collaboration links, one per R/W tier
    createFile(NODE_ID, OWNER_ID);
    HttpResponse readShareResponse =
        createCollaborationLink(
            "ZM_AUTH_TOKEN=fake-token", NODE_ID, SharePermission.READ_AND_SHARE);
    String readShareId =
        (String)
            TestUtils.jsonResponseToMap(readShareResponse.getBodyPayload(), "createCollaborationLink")
                .get("id");

    // When
    HttpResponse readWriteShareResponse =
        createCollaborationLink(
            "ZM_AUTH_TOKEN=fake-token", NODE_ID, SharePermission.READ_WRITE_AND_SHARE);

    // Then — a second, distinct link is created for the other tier
    Assertions.assertThat(readWriteShareResponse.getStatus()).isEqualTo(200);
    Map<String, Object> link =
        TestUtils.jsonResponseToMap(readWriteShareResponse.getBodyPayload(), "createCollaborationLink");
    Assertions.assertThat(link.get("id")).isNotNull().isNotEqualTo(readShareId);
    Assertions.assertThat(link).containsEntry("permission", "READ_WRITE_AND_SHARE");
  }

  @Test
  void givenAShareTargetWithoutShareRightsTheCreateCollaborationLinkShouldReturnAPermissionDeniedError() {
    // Given — READ_ONLY carries no SHARE bit
    createFile(NODE_ID, OWNER_ID);
    createShare(NODE_ID, SHARE_TARGET_ID, SharePermission.READ_ONLY);

    // When
    HttpResponse httpResponse =
        createCollaborationLink(
            "ZM_AUTH_TOKEN=fake-token-b", NODE_ID, SharePermission.READ_AND_SHARE);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly(
            "There was a problem while executing requested operation on node: " + NODE_ID);
  }

  @Test
  void givenAShareTargetWithWriteButNoShareRightsTheCreateCollaborationLinkShouldReturnAPermissionDeniedError() {
    // Given — READ_AND_WRITE has the WRITE bit but not the SHARE bit: holding write alone is not
    // enough to request a share-tier collaboration link (documents that the gate checks the
    // exact requested tier, not just "some" permission)
    createFile(NODE_ID, OWNER_ID);
    createShare(NODE_ID, SHARE_TARGET_ID, SharePermission.READ_AND_WRITE);

    // When
    HttpResponse httpResponse =
        createCollaborationLink(
            "ZM_AUTH_TOKEN=fake-token-b", NODE_ID, SharePermission.READ_AND_SHARE);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly(
            "There was a problem while executing requested operation on node: " + NODE_ID);
  }

  @Test
  void givenANonExistingNodeTheCreateCollaborationLinkShouldReturnTheSamePermissionDeniedErrorShape() {
    // Given — no node is created at all: PermissionsChecker#getPermissions maps a missing node to
    // ACL.NONE, the same shape as an existing-but-forbidden node (mirrors the analogous finding in
    // AuthenticatedDownloadApiIT/CreatePublicLinkApiIT for their respective operations)

    // When
    HttpResponse httpResponse =
        createCollaborationLink(
            "ZM_AUTH_TOKEN=fake-token", NODE_ID, SharePermission.READ_AND_SHARE);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly(
            "There was a problem while executing requested operation on node: " + NODE_ID);
  }
}
