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
 * Task 3.4 of the acceptance coverage-expansion plan: {@code GET /invite/{id}} —
 * {@code CollaborationLinkController} consuming the 8-char invitation id produced by {@code
 * createCollaborationLink} (see {@code CreateCollaborationLinkApiIT}) to auto-create/update a
 * share and 307-redirect. Run on BOTH transports (default embedded + {@code
 * -Dfiles.test.transport=http}) since this is a redirect route with no aggregator in its pipeline
 * ({@code HttpRoutingHandler}'s {@code COLLABORATION_LINK} branch is {@code auth-handler ->
 * collaboration-link-handler -> exceptions-handler}, no {@code HttpObjectAggregator}) — exactly
 * the kind of route the plan flags as transport-sensitive.
 *
 * <p><b>FINDING analysed and verified NOT to cause a transport divergence:</b> {@code
 * CollaborationLinkController#channelRead0} calls {@code
 * context.fireChannelRead(new NoSuchElementException())} UNCONDITIONALLY after handling the
 * request — including on the 307-success path, where it runs right after the {@code
 * DefaultFullHttpResponse} has already been queued via {@code writeAndFlush(...)
 * .addListener(ChannelFutureListener.CLOSE)}. This looks alarming but is inert on both transports:
 * {@code fireChannelRead} propagates an INBOUND pipeline event (a "message was read" signal), not
 * an outbound write — it never puts another byte on the wire. The next handler in the pipeline,
 * {@code exceptions-handler} ({@code ExceptionsHandler}), overrides only {@code exceptionCaught}
 * and not {@code channelRead}, so {@code ChannelInboundHandlerAdapter}'s default implementation
 * just forwards the object to the pipeline tail, which silently discards a non-{@code ByteBuf}
 * inbound message (a `DEBUG`-level "discarded message" log, nothing else). The tests below assert
 * the real, correct 307/404 status/headers/body on both transports to confirm this — a genuine
 * divergence would show up as a wrong status, an extra response, or a hung/reset connection, none
 * of which occurs.
 */
class InviteRedirectApiIT {

  static FilesTestApp app;

  private static final String OWNER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String INVITEE_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String NODE_ID = "00000000-0000-0000-0000-000000000000";
  private static final String EXPECTED_DOMAIN = "example.com";

  @BeforeAll
  static void init() {
    app =
        GuiceNettyFilesTestAppBuilder.aFilesTestApp()
            .withDatabase()
            .withServiceDiscover()
            .withStorages()
            .withUserManagement(
                Map.of(
                    "fake-token", OWNER_ID,
                    "fake-token-b", INVITEE_ID))
            .build();
  }

  @AfterEach
  void cleanUp() {
    app.backdoor().resetDatabase();
    app.mocks().reset();
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

  /** Creates a collaboration link via the mutation and returns its 8-char invitation id. */
  private String createCollaborationLink(String nodeId, SharePermission permission) {
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("createCollaborationLink")
            .withString("node_id", nodeId)
            .withEnumLiteral("permission", permission.name())
            .withWantedResultFormat("{ url }")
            .build();
    HttpResponse response =
        app.send(HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload));
    Assertions.assertThat(response.getStatus()).isEqualTo(200);
    String url =
        (String)
            TestUtils.jsonResponseToMap(response.getBodyPayload(), "createCollaborationLink")
                .get("url");
    return url.substring(url.length() - 8);
  }

  private HttpResponse clickInvite(String invitationId, String cookie) {
    return app.send(HttpRequest.of("GET", "/invite/" + invitationId, cookie, null));
  }

  private String headerValue(HttpResponse response, String name) {
    return response.getHeaders().stream()
        .filter(header -> header.getKey().equalsIgnoreCase(name))
        .map(Map.Entry::getValue)
        .findFirst()
        .orElse(null);
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
    HttpResponse response = app.send(HttpRequest.of("POST", "/graphql/", cookie, bodyPayload));
    Assertions.assertThat(response.getStatus()).isEqualTo(200);
    Map<String, Object> share = TestUtils.jsonResponseToMap(response.getBodyPayload(), "getShare");
    return (String) share.get("permission");
  }

  private void deleteNode(String nodeId) {
    app.mocks().storagesBulkDeleteSucceeds(List.of());
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("deleteNodes")
            .withListOfStrings("node_ids", new String[] {nodeId})
            .withWantedResultFormat("")
            .build();
    HttpResponse response =
        app.send(HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload));
    Assertions.assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void givenAValidInvitationAndANonOwnerTheInviteShouldRedirectAndAutoCreateTheShare() {
    // Given
    createFile(NODE_ID, OWNER_ID);
    String invitationId = createCollaborationLink(NODE_ID, SharePermission.READ_AND_SHARE);
    Assertions.assertThat(app.backdoor().shareExists(NODE_ID, INVITEE_ID)).isFalse();

    // When
    HttpResponse httpResponse = clickInvite(invitationId, "ZM_AUTH_TOKEN=fake-token-b");

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(307);
    Assertions.assertThat(headerValue(httpResponse, "location")).isEqualTo(expectedLocation(NODE_ID));
    Assertions.assertThat(headerValue(httpResponse, "content-length")).isEqualTo("0");

    Assertions.assertThat(app.backdoor().shareExists(NODE_ID, INVITEE_ID)).isTrue();
    Assertions.assertThat(getSharePermission("ZM_AUTH_TOKEN=fake-token-b", NODE_ID, INVITEE_ID))
        .isEqualTo("READ_AND_SHARE");
  }

  @Test
  void givenAnExistingShareTheInviteShouldUpdateItInPlace() {
    // Given — a pre-existing direct READ_ONLY share, then a READ_WRITE_AND_SHARE collaboration link
    createFile(NODE_ID, OWNER_ID);
    createShare(NODE_ID, INVITEE_ID, SharePermission.READ_ONLY);
    Assertions.assertThat(getSharePermission("ZM_AUTH_TOKEN=fake-token-b", NODE_ID, INVITEE_ID))
        .isEqualTo("READ_ONLY");
    String invitationId = createCollaborationLink(NODE_ID, SharePermission.READ_WRITE_AND_SHARE);

    // When
    HttpResponse httpResponse = clickInvite(invitationId, "ZM_AUTH_TOKEN=fake-token-b");

    // Then — same share row, but its permission tier is now updated, not duplicated
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(307);
    Assertions.assertThat(headerValue(httpResponse, "location")).isEqualTo(expectedLocation(NODE_ID));
    Assertions.assertThat(app.backdoor().shareExists(NODE_ID, INVITEE_ID)).isTrue();
    Assertions.assertThat(getSharePermission("ZM_AUTH_TOKEN=fake-token-b", NODE_ID, INVITEE_ID))
        .isEqualTo("READ_WRITE_AND_SHARE");
  }

  @Test
  void givenTheOwnerClickingItsOwnLinkTheInviteShouldRedirectWithoutAnyShareMutation() {
    // Given
    createFile(NODE_ID, OWNER_ID);
    String invitationId = createCollaborationLink(NODE_ID, SharePermission.READ_AND_SHARE);
    Assertions.assertThat(app.backdoor().shareExists(NODE_ID, OWNER_ID)).isFalse();

    // When
    HttpResponse httpResponse = clickInvite(invitationId, "ZM_AUTH_TOKEN=fake-token");

    // Then — same 307 success shape as a non-owner, but the owner never gets an explicit share row
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(307);
    Assertions.assertThat(headerValue(httpResponse, "location")).isEqualTo(expectedLocation(NODE_ID));
    Assertions.assertThat(headerValue(httpResponse, "content-length")).isEqualTo("0");
    Assertions.assertThat(app.backdoor().shareExists(NODE_ID, OWNER_ID)).isFalse();
  }

  @Test
  void givenABadInvitationIdTheInviteShouldReturn404() {
    // Given — syntactically valid (8 word chars, matches the routing regex) but not tied to any
    // collaboration link
    String badInvitationId = "aaaaaaaa";

    // When
    HttpResponse httpResponse = clickInvite(badInvitationId, "ZM_AUTH_TOKEN=fake-token-b");

    // Then — NoSuchElementException -> ExceptionsHandler's generic 404 shape
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(404);
    Assertions.assertThat(httpResponse.getBodyPayload()).isEqualTo("404 Not Found");
  }

  @Test
  void givenAnInvitationWhoseNodeWasDeletedTheInviteShouldReturn404() {
    // Given — the collaboration link outlives the node it points to: nothing in this codebase
    // cleans up a CollaborationLink row when its node is hard-deleted
    createFile(NODE_ID, OWNER_ID);
    String invitationId = createCollaborationLink(NODE_ID, SharePermission.READ_AND_SHARE);
    deleteNode(NODE_ID);
    Assertions.assertThat(app.backdoor().nodeExists(NODE_ID)).isFalse();

    // When
    HttpResponse httpResponse = clickInvite(invitationId, "ZM_AUTH_TOKEN=fake-token-b");

    // Then — same generic 404 shape as a bad invitation id (both are NoSuchElementException)
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(404);
    Assertions.assertThat(httpResponse.getBodyPayload()).isEqualTo("404 Not Found");
  }
}
