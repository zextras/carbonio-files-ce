// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.dao.ebean.Share;
import com.zextras.carbonio.files.dal.repositories.interfaces.CollaborationLinkRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.ShareRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end integration test for the P4b collaboration-link invitation endpoint ({@code GET
 * /invite/{invitationId}}) on Quarkus/RESTEasy Reactive: proves the full stack (auth ->
 * CollaborationLinkResource -> {@link
 * com.zextras.carbonio.files.rest.services.CollaborationLinkService} -> Panache DAL +
 * ShareDataFetcher) resolves the link, creates a direct share for the requester with the link's
 * ACL, and issues the same 307 redirect the legacy Netty controller produced.
 *
 * <p>Uses {@code @QuarkusTest} + {@link FilesStackTestResource} (real Postgres testcontainer,
 * Consul WireMock, in-process user-management gRPC stub).
 */
@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class CollaborationLinkResourceIT {

  @Inject NodeRepository nodeRepository;
  @Inject CollaborationLinkRepository collaborationLinkRepository;
  @Inject ShareRepository shareRepository;

  private String otherOwnerNodeId;

  @BeforeEach
  void seed() {
    // A folder owned by ANOTHER user (not the stubbed test requester), so clicking the
    // invitation actually creates a share (the legacy service no-ops when owner == requester).
    otherOwnerNodeId = UUID.randomUUID().toString();
    nodeRepository.createNewNode(
        otherOwnerNodeId,
        "other-owner",
        "other-owner",
        "LOCAL_ROOT",
        "collab-node-" + otherOwnerNodeId,
        "desc",
        NodeType.FOLDER,
        "LOCAL_ROOT",
        0L);
  }

  @Test
  void inviteCreatesShareAndRedirects() {
    String invitationId = "inv12345";
    collaborationLinkRepository.createLink(
        UUID.randomUUID(), otherOwnerNodeId, invitationId, SharePermission.READ_AND_WRITE);

    given()
        .cookie("ZM_AUTH_TOKEN", FilesStackTestResource.AUTH_TOKEN)
        .redirects()
        .follow(false)
        .when()
        .get("/invite/" + invitationId)
        .then()
        .statusCode(307)
        .header(
            "Location",
            "example.com/carbonio/files/?file="
                + otherOwnerNodeId
                + "&node="
                + otherOwnerNodeId
                + "&tab=sharing");

    Optional<Share> share =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    shareRepository.getShare(
                        otherOwnerNodeId, FilesStackTestResource.TEST_USER_ID));
    assertThat(share).isPresent();
    assertThat(share.get().getPermissions().getSharePermission())
        .isEqualTo(SharePermission.READ_AND_WRITE);
    assertThat(share.get().isDirect()).isTrue();
    assertThat(share.get().isCreatedViaLink()).isTrue();
  }

  @Test
  void inviteWithUnknownInvitationIdReturns404() {
    given()
        .cookie("ZM_AUTH_TOKEN", FilesStackTestResource.AUTH_TOKEN)
        .when()
        .get("/invite/zzzzzzzz")
        .then()
        .statusCode(404);
  }

  @Test
  void inviteWithoutCookieReturns401() {
    String invitationId = "auth0001";
    collaborationLinkRepository.createLink(
        UUID.randomUUID(), otherOwnerNodeId, invitationId, SharePermission.READ_ONLY);

    given().when().get("/invite/" + invitationId).then().statusCode(401);
  }

  @Test
  void inviteClickedByOwnerCreatesNoShare() {
    String invitationId = "owner001";
    // A folder owned by the requester itself.
    String ownNodeId = UUID.randomUUID().toString();
    nodeRepository.createNewNode(
        ownNodeId,
        FilesStackTestResource.TEST_USER_ID,
        FilesStackTestResource.TEST_USER_ID,
        "LOCAL_ROOT",
        "own-node-" + ownNodeId,
        "desc",
        NodeType.FOLDER,
        "LOCAL_ROOT",
        0L);
    collaborationLinkRepository.createLink(
        UUID.randomUUID(), ownNodeId, invitationId, SharePermission.READ_ONLY);

    given()
        .cookie("ZM_AUTH_TOKEN", FilesStackTestResource.AUTH_TOKEN)
        .redirects()
        .follow(false)
        .when()
        .get("/invite/" + invitationId)
        .then()
        .statusCode(307);

    Optional<Share> share =
        QuarkusTransaction.requiringNew()
            .call(() -> shareRepository.getShare(ownNodeId, FilesStackTestResource.TEST_USER_ID));
    assertThat(share).isEmpty();
  }
}
