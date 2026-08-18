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
 * {@code com.zextras.carbonio.files.acceptance.CreateCollaborationLinkApiIT} rewritten as an
 * out-of-process {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}. All 6 methods and
 * their assertions are preserved verbatim; only the seeding mechanism (API calls capturing
 * server-generated ids) and the transport changed.
 *
 * <p><b>URL shape:</b> {@code <domain><Endpoints.COLLABORATION_LINK_URL><8-char invitationId>}
 * (here {@code example.com/services/files/invite/<8 chars>}). The invitation id is deliberately 8
 * alphanumeric characters — distinct from the 36-char UUID {@code id} field of the link itself —
 * matching {@code CollaborationLinkRepository#createLink}'s {@code invitationId} contract (see also
 * {@code InviteRedirectApiIT}, which consumes this same 8-char id via {@code GET /invite/{id}}).
 *
 * <p><b>Permission gate:</b> {@code CollaborationLinkDataFetcher#createCollaborationLink} checks
 * {@code permissionsChecker.getPermissions(nodeId, requesterId).has(permission)} where {@code
 * permission} is literally the mutation's {@code permission} argument — the requester must ALREADY
 * hold (as a bitwise subset of their own ACL) the exact tier they are requesting a link for. A
 * missing node maps to {@code ACL.NONE} (see {@code PermissionsChecker#getPermissions}), so a
 * non-existing node collapses into the identical {@code nodeWriteError} shape as a genuine
 * permission denial — asserted explicitly below rather than assumed.
 */
class CreateCollaborationLinkApiIT extends AbstractFilesIT {

  private static final String OWNER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String SHARE_TARGET_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String OWNER_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String SHARE_TARGET_COOKIE = "ZM_AUTH_TOKEN=fake-token-b";
  private static final String URL_PREFIX = "example.com/services/files/invite/";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", OWNER_ID);
    FilesStackTestResource.getUserManagementService()
        .registerToken("fake-token-b", SHARE_TARGET_ID);
  }

  private Response createCollaborationLink(
      String cookie, String nodeId, SharePermission permission) {
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("createCollaborationLink")
            .withString("node_id", nodeId)
            .withEnumLiteral("permission", permission.name())
            .withWantedResultFormat("{ id url created_at permission node { id } }")
            .build();
    return graphql(bodyPayload, cookie);
  }

  @Test
  void givenAnOwnerTheCreateCollaborationLinkShouldCreateANewLinkWithTheExpectedShape() {
    // Given
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);

    // When
    Response response =
        createCollaborationLink(OWNER_COOKIE, nodeId, SharePermission.READ_AND_SHARE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Map<String, Object> link =
        TestUtils.jsonResponseToMap(response.getBody().asString(), "createCollaborationLink");

    String id = (String) link.get("id");
    String url = (String) link.get("url");
    Assertions.assertThat(id).isNotNull().hasSize(36);
    Assertions.assertThat(url).startsWith(URL_PREFIX).hasSize(URL_PREFIX.length() + 8);
    Assertions.assertThat(link).containsEntry("permission", "READ_AND_SHARE");
    Assertions.assertThat((Map<String, Object>) link.get("node")).containsEntry("id", nodeId);
    Assertions.assertThat(link.get("created_at")).isNotNull();
  }

  @Test
  void
      givenAnExistingLinkWithTheSamePermissionTheCreateCollaborationLinkShouldReturnItIdempotently() {
    // Given
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    Response first = createCollaborationLink(OWNER_COOKIE, nodeId, SharePermission.READ_AND_SHARE);
    String firstId =
        (String)
            TestUtils.jsonResponseToMap(first.getBody().asString(), "createCollaborationLink")
                .get("id");

    // When — same node, same requester, same permission tier requested again
    Response second = createCollaborationLink(OWNER_COOKIE, nodeId, SharePermission.READ_AND_SHARE);

    // Then — the system returns the EXISTING link, it does not create a duplicate
    Assertions.assertThat(second.getStatusCode()).isEqualTo(200);
    Map<String, Object> link =
        TestUtils.jsonResponseToMap(second.getBody().asString(), "createCollaborationLink");
    Assertions.assertThat(link.get("id")).isEqualTo(firstId);
  }

  @Test
  void givenADifferentPermissionTheCreateCollaborationLinkShouldCreateASecondDistinctLink() {
    // Given — a node can have at most 2 collaboration links, one per R/W tier
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    Response readShareResponse =
        createCollaborationLink(OWNER_COOKIE, nodeId, SharePermission.READ_AND_SHARE);
    String readShareId =
        (String)
            TestUtils.jsonResponseToMap(
                    readShareResponse.getBody().asString(), "createCollaborationLink")
                .get("id");

    // When
    Response readWriteShareResponse =
        createCollaborationLink(OWNER_COOKIE, nodeId, SharePermission.READ_WRITE_AND_SHARE);

    // Then — a second, distinct link is created for the other tier
    Assertions.assertThat(readWriteShareResponse.getStatusCode()).isEqualTo(200);
    Map<String, Object> link =
        TestUtils.jsonResponseToMap(
            readWriteShareResponse.getBody().asString(), "createCollaborationLink");
    Assertions.assertThat(link.get("id")).isNotNull().isNotEqualTo(readShareId);
    Assertions.assertThat(link).containsEntry("permission", "READ_WRITE_AND_SHARE");
  }

  @Test
  void
      givenAShareTargetWithoutShareRightsTheCreateCollaborationLinkShouldReturnAPermissionDeniedError() {
    // Given — READ_ONLY carries no SHARE bit
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    seedShare(nodeId, SHARE_TARGET_ID, SharePermission.READ_ONLY, OWNER_COOKIE);

    // When
    Response response =
        createCollaborationLink(SHARE_TARGET_COOKIE, nodeId, SharePermission.READ_AND_SHARE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly(
            "There was a problem while executing requested operation on node: " + nodeId);
  }

  @Test
  void
      givenAShareTargetWithWriteButNoShareRightsTheCreateCollaborationLinkShouldReturnAPermissionDeniedError() {
    // Given — READ_AND_WRITE has the WRITE bit but not the SHARE bit: holding write alone is not
    // enough to request a share-tier collaboration link (documents that the gate checks the
    // exact requested tier, not just "some" permission)
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    seedShare(nodeId, SHARE_TARGET_ID, SharePermission.READ_AND_WRITE, OWNER_COOKIE);

    // When
    Response response =
        createCollaborationLink(SHARE_TARGET_COOKIE, nodeId, SharePermission.READ_AND_SHARE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly(
            "There was a problem while executing requested operation on node: " + nodeId);
  }

  @Test
  void
      givenANonExistingNodeTheCreateCollaborationLinkShouldReturnTheSamePermissionDeniedErrorShape() {
    // Given — no node is created at all: PermissionsChecker#getPermissions maps a missing node to
    // ACL.NONE, the same shape as an existing-but-forbidden node (mirrors the analogous finding in
    // AuthenticatedDownloadApiIT/CreatePublicLinkApiIT for their respective operations)
    String nonExistentNodeId = "00000000-0000-0000-0000-000000000000";

    // When
    Response response =
        createCollaborationLink(OWNER_COOKIE, nonExistentNodeId, SharePermission.READ_AND_SHARE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly(
            "There was a problem while executing requested operation on node: "
                + nonExistentNodeId);
  }
}
