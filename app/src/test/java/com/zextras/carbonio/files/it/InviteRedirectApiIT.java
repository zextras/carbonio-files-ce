// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code com.zextras.carbonio.files.acceptance.InviteRedirectApiIT} rewritten as an out-of-process
 * {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}. All 5 methods and their assertions
 * are preserved verbatim; only the seeding mechanism (API calls capturing server-generated ids —
 * storages bulk-delete defaults to full success on {@code deleteNodes}, so no explicit fake setup
 * is needed, matching {@code DeleteNodesApiIT}'s convention) and the transport changed: RestAssured
 * follows redirects by default, so {@code clickInvite} explicitly disables that (via {@code
 * .redirects().follow(false)}) to observe the raw 307/404 status and headers. The seam's
 * dual-transport note (embedded + {@code -Dfiles.test.transport=http}) is now moot — every {@code
 * @QuarkusIntegrationTest} already talks to the launched app over real HTTP unconditionally.
 *
 * <p><b>{@code GET /invite/{id}}</b> — {@code CollaborationLinkController} consuming the 8-char
 * invitation id produced by {@code createCollaborationLink} (see {@code
 * CreateCollaborationLinkApiIT}) to auto-create/update a share and 307-redirect.
 *
 * <p><b>FINDING analysed and verified NOT to cause a transport divergence:</b> {@code
 * CollaborationLinkController#channelRead0} calls {@code
 * context.fireChannelRead(new NoSuchElementException())} UNCONDITIONALLY after handling the
 * request — including on the 307-success path, where it runs right after the {@code
 * DefaultFullHttpResponse} has already been queued via {@code writeAndFlush(...)
 * .addListener(ChannelFutureListener.CLOSE)}. This looks alarming but is inert: {@code
 * fireChannelRead} propagates an INBOUND pipeline event (a "message was read" signal), not an
 * outbound write — it never puts another byte on the wire. The next handler in the pipeline,
 * {@code exceptions-handler} ({@code ExceptionsHandler}), overrides only {@code exceptionCaught}
 * and not {@code channelRead}, so {@code ChannelInboundHandlerAdapter}'s default implementation
 * just forwards the object to the pipeline tail, which silently discards a non-{@code ByteBuf}
 * inbound message. The tests below assert the real, correct 307/404 status/headers/body to confirm
 * this — a genuine divergence would show up as a wrong status, an extra response, or a hung/reset
 * connection, none of which occurs.
 */
class InviteRedirectApiIT extends AbstractFilesIT {

  private static final String OWNER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String INVITEE_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String OWNER_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String INVITEE_COOKIE = "ZM_AUTH_TOKEN=fake-token-b";
  private static final String EXPECTED_DOMAIN = "example.com";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", OWNER_ID);
    FilesStackTestResource.getUserManagementService().registerToken("fake-token-b", INVITEE_ID);
  }

  /** Creates a collaboration link via the mutation and returns its 8-char invitation id. */
  private String createCollaborationLink(String nodeId, SharePermission permission) {
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("createCollaborationLink")
            .withString("node_id", nodeId)
            .withEnumLiteral("permission", permission.name())
            .withWantedResultFormat("{ url }")
            .build();
    Response response = graphql(bodyPayload, OWNER_COOKIE);
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    String url =
        (String)
            TestUtils.jsonResponseToMap(response.getBody().asString(), "createCollaborationLink")
                .get("url");
    return url.substring(url.length() - 8);
  }

  /** {@code GET /invite/{id}} with redirect-following DISABLED, so the raw 307/404 is observable. */
  private Response clickInvite(String invitationId, String cookie) {
    var request = RestAssured.given().redirects().follow(false);
    if (cookie != null) {
      request = request.header("Cookie", cookie);
    }
    return request.get("/invite/" + invitationId);
  }

  private String expectedLocation(String nodeId) {
    return EXPECTED_DOMAIN + "/carbonio/files/?file=" + nodeId + "&node=" + nodeId + "&tab=sharing";
  }

  /** Reads back a share's permission tier via the API — requires the caller to hold READ_ONLY. */
  private String getSharePermission(String cookie, String nodeId, String targetUserId) {
    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("getShare")
            .withString("node_id", nodeId)
            .withString("share_target_id", targetUserId)
            .withWantedResultFormat("{ permission }")
            .build();
    Response response = graphql(bodyPayload, cookie);
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Map<String, Object> share = TestUtils.jsonResponseToMap(response.getBody().asString(), "getShare");
    return (String) share.get("permission");
  }

  private void deleteNode(String nodeId) {
    // storages bulk-delete defaults to full success (empty failed list) — no mock setup needed.
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("deleteNodes")
            .withListOfStrings("node_ids", new String[] {nodeId})
            .withWantedResultFormat("")
            .build();
    Response response = graphql(bodyPayload, OWNER_COOKIE);
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
  }

  @Test
  void givenAValidInvitationAndANonOwnerTheInviteShouldRedirectAndAutoCreateTheShare() {
    // Given
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    String invitationId = createCollaborationLink(nodeId, SharePermission.READ_AND_SHARE);
    Assertions.assertThat(shareExists(nodeId, INVITEE_ID, OWNER_COOKIE)).isFalse();

    // When
    Response response = clickInvite(invitationId, INVITEE_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(307);
    Assertions.assertThat(response.getHeader("location")).isEqualTo(expectedLocation(nodeId));
    Assertions.assertThat(response.getHeader("content-length")).isEqualTo("0");

    Assertions.assertThat(shareExists(nodeId, INVITEE_ID, OWNER_COOKIE)).isTrue();
    Assertions.assertThat(getSharePermission(INVITEE_COOKIE, nodeId, INVITEE_ID))
        .isEqualTo("READ_AND_SHARE");
  }

  @Test
  void givenAnExistingShareTheInviteShouldUpdateItInPlace() {
    // Given — a pre-existing direct READ_ONLY share, then a READ_WRITE_AND_SHARE collaboration link
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    seedShare(nodeId, INVITEE_ID, SharePermission.READ_ONLY, OWNER_COOKIE);
    Assertions.assertThat(getSharePermission(INVITEE_COOKIE, nodeId, INVITEE_ID)).isEqualTo("READ_ONLY");
    String invitationId = createCollaborationLink(nodeId, SharePermission.READ_WRITE_AND_SHARE);

    // When
    Response response = clickInvite(invitationId, INVITEE_COOKIE);

    // Then — same share row, but its permission tier is now updated, not duplicated
    Assertions.assertThat(response.getStatusCode()).isEqualTo(307);
    Assertions.assertThat(response.getHeader("location")).isEqualTo(expectedLocation(nodeId));
    Assertions.assertThat(shareExists(nodeId, INVITEE_ID, OWNER_COOKIE)).isTrue();
    Assertions.assertThat(getSharePermission(INVITEE_COOKIE, nodeId, INVITEE_ID))
        .isEqualTo("READ_WRITE_AND_SHARE");
  }

  @Test
  void givenTheOwnerClickingItsOwnLinkTheInviteShouldRedirectWithoutAnyShareMutation() {
    // Given
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    String invitationId = createCollaborationLink(nodeId, SharePermission.READ_AND_SHARE);
    Assertions.assertThat(shareExists(nodeId, OWNER_ID, OWNER_COOKIE)).isFalse();

    // When
    Response response = clickInvite(invitationId, OWNER_COOKIE);

    // Then — same 307 success shape as a non-owner, but the owner never gets an explicit share row
    Assertions.assertThat(response.getStatusCode()).isEqualTo(307);
    Assertions.assertThat(response.getHeader("location")).isEqualTo(expectedLocation(nodeId));
    Assertions.assertThat(response.getHeader("content-length")).isEqualTo("0");
    Assertions.assertThat(shareExists(nodeId, OWNER_ID, OWNER_COOKIE)).isFalse();
  }

  @Test
  void givenABadInvitationIdTheInviteShouldReturn404() {
    // Given — syntactically valid (8 word chars, matches the routing regex) but not tied to any
    // collaboration link
    String badInvitationId = "aaaaaaaa";

    // When
    Response response = clickInvite(badInvitationId, INVITEE_COOKIE);

    // Then — NoSuchElementException -> ExceptionsHandler's generic 404 shape
    Assertions.assertThat(response.getStatusCode()).isEqualTo(404);
    Assertions.assertThat(response.getBody().asString()).isEqualTo("404 Not Found");
  }

  @Test
  void givenAnInvitationWhoseNodeWasDeletedTheInviteShouldReturn404() {
    // Given — the collaboration link outlives the node it points to: nothing in this codebase
    // cleans up a CollaborationLink row when its node is hard-deleted
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    String invitationId = createCollaborationLink(nodeId, SharePermission.READ_AND_SHARE);
    deleteNode(nodeId);
    Assertions.assertThat(nodeExists(nodeId, OWNER_COOKIE)).isFalse();

    // When
    Response response = clickInvite(invitationId, INVITEE_COOKIE);

    // Then — same generic 404 shape as a bad invitation id (both are NoSuchElementException)
    Assertions.assertThat(response.getStatusCode()).isEqualTo(404);
    Assertions.assertThat(response.getBody().asString()).isEqualTo("404 Not Found");
  }
}
