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
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code com.zextras.carbonio.files.acceptance.DeleteCollaborationLinksApiIT} rewritten as an
 * out-of-process {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}. All 4 methods and
 * their assertions are preserved verbatim; only the seeding mechanism (API calls capturing
 * server-generated ids) and the transport changed.
 *
 * <p><b>FINDING (documented, not fixed):</b> {@code
 * CollaborationLinkDataFetcher#deleteCollaborationLinks} computes "forbidden" as {@code
 * collaborationLinkRepository.getLinkById(id).filter(requesterHasShareRights).isEmpty()} — this
 * single boolean conflates TWO distinct causes (the id does not exist at all, and the id exists but
 * the requester lacks {@code READ_AND_SHARE}/{@code READ_WRITE_AND_SHARE} on its node) into the
 * SAME generic {@code missingField} error ("Could not find data to retrieve for requested field",
 * {@code errorCode: MISSING_FIELD}) with no id-specific detail — a genuine not-found and a genuine
 * permission-denial are indistinguishable over HTTP. Both scenarios are asserted below with their
 * real, identical shape; see also the analogous {@code AuthenticatedDownloadApiIT}/{@code
 * GetPublicLinksApiIT} not-found-vs-forbidden findings.
 */
class DeleteCollaborationLinksApiIT extends AbstractFilesIT {

  private static final String OWNER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String SHARE_TARGET_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String OWNER_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String SHARE_TARGET_COOKIE = "ZM_AUTH_TOKEN=fake-token-b";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", OWNER_ID);
    FilesStackTestResource.getUserManagementService()
        .registerToken("fake-token-b", SHARE_TARGET_ID);
  }

  private String createCollaborationLink(String nodeId, SharePermission permission) {
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("createCollaborationLink")
            .withString("node_id", nodeId)
            .withEnumLiteral("permission", permission.name())
            .withWantedResultFormat("{ id }")
            .build();
    Response response = graphql(bodyPayload, OWNER_COOKIE);
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    return (String)
        TestUtils.jsonResponseToMap(response.getBody().asString(), "createCollaborationLink")
            .get("id");
  }

  private Response deleteCollaborationLinks(String cookie, String... linkIds) {
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("deleteCollaborationLinks")
            .withListOfStrings("collaboration_link_ids", linkIds)
            .withWantedResultFormat("")
            .build();
    return graphql(bodyPayload, cookie);
  }

  @SuppressWarnings("unchecked")
  private List<String> deletedIds(Response response) {
    return (List<String>)
        TestUtils.jsonResponseToValue(response.getBody().asString(), "deleteCollaborationLinks")
            .orElse(List.of());
  }

  private List<String> remainingLinkIds(String nodeId) {
    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("getCollaborationLinks")
            .withString("node_id", nodeId)
            .withWantedResultFormat("{ id }")
            .build();
    Response response = graphql(bodyPayload, OWNER_COOKIE);
    return TestUtils.jsonResponseToList(response.getBody().asString(), "getCollaborationLinks")
        .stream()
        .map(link -> (String) link.get("id"))
        .toList();
  }

  @Test
  void givenAnOwnedLinkTheDeleteCollaborationLinksShouldDeleteItAndReturnItsId() {
    // Given
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    String linkId = createCollaborationLink(nodeId, SharePermission.READ_AND_SHARE);

    // When
    Response response = deleteCollaborationLinks(OWNER_COOKIE, linkId);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(deletedIds(response)).containsExactly(linkId);

    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors).isEmpty();

    Assertions.assertThat(remainingLinkIds(nodeId)).doesNotContain(linkId);
  }

  @Test
  void givenAForbiddenLinkTheDeleteCollaborationLinksShouldReturnAMissingFieldError() {
    // Given — the share target has READ_ONLY (no SHARE bit) on the node that owns the link
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    String linkId = createCollaborationLink(nodeId, SharePermission.READ_AND_SHARE);
    seedShare(nodeId, SHARE_TARGET_ID, SharePermission.READ_ONLY, OWNER_COOKIE);

    // When
    Response response = deleteCollaborationLinks(SHARE_TARGET_COOKIE, linkId);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(deletedIds(response)).isEmpty();

    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("Could not find data to retrieve for requested field");

    // The link is untouched.
    Assertions.assertThat(remainingLinkIds(nodeId)).containsExactly(linkId);
  }

  @Test
  void givenANotExistingLinkIdTheDeleteCollaborationLinksShouldReturnTheSameMissingFieldError() {
    // Given — see the class-level FINDING: not-found collapses into the identical shape as
    // permission-denied
    String notExistingLinkId = UUID.randomUUID().toString();

    // When
    Response response = deleteCollaborationLinks(OWNER_COOKIE, notExistingLinkId);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(deletedIds(response)).isEmpty();

    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("Could not find data to retrieve for requested field");
  }

  @Test
  void givenAnAllowedAndAForbiddenLinkTheDeleteCollaborationLinksShouldReturnAPartialSuccess() {
    // Given — one link the owner may delete, one forbidden id (not-found) in the same call
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    String allowedLinkId = createCollaborationLink(nodeId, SharePermission.READ_AND_SHARE);
    String forbiddenLinkId = UUID.randomUUID().toString();

    // When
    Response response = deleteCollaborationLinks(OWNER_COOKIE, allowedLinkId, forbiddenLinkId);

    // Then — the allowed id is deleted and returned, the forbidden one produces one error
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(deletedIds(response)).containsExactly(allowedLinkId);

    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("Could not find data to retrieve for requested field");

    Assertions.assertThat(remainingLinkIds(nodeId)).doesNotContain(allowedLinkId);
  }
}
