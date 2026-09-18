// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zextras.carbonio.files.dal.dao.UserId;
import com.zextras.carbonio.files.dal.dao.UserMyself;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.dal.dao.ebean.CollaborationLink;
import com.zextras.carbonio.files.dal.repositories.interfaces.CollaborationLinkRepository;
import com.zextras.carbonio.files.graphql.errors.ErrorCodes;
import com.zextras.carbonio.files.graphql.errors.FilesGraphQLException;
import com.zextras.carbonio.files.graphql.model.CollaborationLinkModel;
import com.zextras.carbonio.files.graphql.model.SharePermission;
import com.zextras.carbonio.files.graphql.validation.GraphQLInputValidator;
import com.zextras.carbonio.files.utilities.PermissionsChecker;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CollaborationLinkApiTest {

  private static final String REQUESTER_ID = "requester-user-id";
  private static final String DOMAIN = "https://example.com";
  private static final String NODE_ID = "00000000-0000-0000-0000-000000000001";
  private static final UUID COLLAB_UUID_1 = UUID.fromString("00000000-0000-0000-0000-000000000010");
  private static final UUID COLLAB_UUID_2 = UUID.fromString("00000000-0000-0000-0000-000000000020");

  private CollaborationLinkRepository collaborationLinkRepository;
  private PermissionsChecker permissionsChecker;
  private CollaborationLinkApi collaborationLinkApi;

  @BeforeEach
  void setUp() {
    collaborationLinkRepository = mock(CollaborationLinkRepository.class);
    permissionsChecker = mock(PermissionsChecker.class);

    UserMyself requester = mock(UserMyself.class);
    when(requester.getId()).thenReturn(new UserId(REQUESTER_ID));
    when(requester.getDomain()).thenReturn(DOMAIN);

    collaborationLinkApi = new CollaborationLinkApi();
    collaborationLinkApi.collaborationLinkRepository = collaborationLinkRepository;
    collaborationLinkApi.permissionsChecker = permissionsChecker;
    collaborationLinkApi.validator = new GraphQLInputValidator();
    collaborationLinkApi.requester = requester;
  }

  private ACL aclWith(ACL.SharePermission perm) {
    return ACL.decode(perm);
  }

  private CollaborationLink mockCollaborationLink(
      UUID id, String nodeId, ACL.SharePermission perm) {
    CollaborationLink link = mock(CollaborationLink.class);
    when(link.getId()).thenReturn(id);
    when(link.getNodeId()).thenReturn(nodeId);
    when(link.getPermissions()).thenReturn(perm);
    when(link.getInvitationId()).thenReturn("invite01");
    when(link.getCreatedAt()).thenReturn(Instant.ofEpochMilli(1000L));
    return link;
  }

  // ─── getCollaborationLinks ─────────────────────────────────────────────────────

  @Test
  void getCollaborationLinksHappyPathReturnsModels() throws FilesGraphQLException {
    CollaborationLink link =
        mockCollaborationLink(COLLAB_UUID_1, NODE_ID, ACL.SharePermission.READ_AND_SHARE);
    when(permissionsChecker.getPermissions(NODE_ID, REQUESTER_ID))
        .thenReturn(aclWith(ACL.SharePermission.READ_AND_SHARE));
    when(collaborationLinkRepository.getLinksByNodeId(NODE_ID)).thenReturn(Stream.of(link));

    List<CollaborationLinkModel> result = collaborationLinkApi.getCollaborationLinks(NODE_ID);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).getId()).isEqualTo(COLLAB_UUID_1.toString());
    assertThat(result.get(0).getPermission()).isEqualTo(SharePermission.READ_AND_SHARE);
  }

  @Test
  void getCollaborationLinksReturnsEmptyListWhenPermissionDenied() throws FilesGraphQLException {
    when(permissionsChecker.getPermissions(NODE_ID, REQUESTER_ID))
        .thenReturn(aclWith(ACL.SharePermission.READ_ONLY));

    List<CollaborationLinkModel> result = collaborationLinkApi.getCollaborationLinks(NODE_ID);

    assertThat(result).isEmpty();
    verify(collaborationLinkRepository, never()).getLinksByNodeId(any());
  }

  @Test
  void getCollaborationLinksFiltersOutHigherPermission() throws FilesGraphQLException {
    CollaborationLink readShareLink =
        mockCollaborationLink(COLLAB_UUID_1, NODE_ID, ACL.SharePermission.READ_AND_SHARE);
    CollaborationLink readWriteShareLink =
        mockCollaborationLink(COLLAB_UUID_2, NODE_ID, ACL.SharePermission.READ_WRITE_AND_SHARE);
    when(permissionsChecker.getPermissions(NODE_ID, REQUESTER_ID))
        .thenReturn(aclWith(ACL.SharePermission.READ_AND_SHARE));
    when(collaborationLinkRepository.getLinksByNodeId(NODE_ID))
        .thenReturn(Stream.of(readShareLink, readWriteShareLink));

    List<CollaborationLinkModel> result = collaborationLinkApi.getCollaborationLinks(NODE_ID);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).getPermission()).isEqualTo(SharePermission.READ_AND_SHARE);
  }

  // ─── createCollaborationLink ───────────────────────────────────────────────────

  @Test
  void createCollaborationLinkCreatesNewWhenNoneExists() throws FilesGraphQLException {
    CollaborationLink newLink =
        mockCollaborationLink(COLLAB_UUID_1, NODE_ID, ACL.SharePermission.READ_AND_SHARE);
    when(permissionsChecker.getPermissions(NODE_ID, REQUESTER_ID))
        .thenReturn(aclWith(ACL.SharePermission.READ_AND_SHARE));
    when(collaborationLinkRepository.getLinksByNodeId(NODE_ID)).thenReturn(Stream.empty());
    when(collaborationLinkRepository.createLink(any(), any(), any(), any())).thenReturn(newLink);

    CollaborationLinkModel result =
        collaborationLinkApi.createCollaborationLink(NODE_ID, SharePermission.READ_AND_SHARE);

    assertThat(result).isNotNull();
    assertThat(result.getId()).isEqualTo(COLLAB_UUID_1.toString());
    assertThat(result.getPermission()).isEqualTo(SharePermission.READ_AND_SHARE);
  }

  @Test
  void createCollaborationLinkReturnsExistingWhenSamePermissionExists()
      throws FilesGraphQLException {
    CollaborationLink existing =
        mockCollaborationLink(COLLAB_UUID_1, NODE_ID, ACL.SharePermission.READ_AND_SHARE);
    when(permissionsChecker.getPermissions(NODE_ID, REQUESTER_ID))
        .thenReturn(aclWith(ACL.SharePermission.READ_AND_SHARE));
    when(collaborationLinkRepository.getLinksByNodeId(NODE_ID)).thenReturn(Stream.of(existing));

    CollaborationLinkModel result =
        collaborationLinkApi.createCollaborationLink(NODE_ID, SharePermission.READ_AND_SHARE);

    assertThat(result.getId()).isEqualTo(COLLAB_UUID_1.toString());
    verify(collaborationLinkRepository, never()).createLink(any(), any(), any(), any());
  }

  @Test
  void createCollaborationLinkThrowsNodeWriteErrorWhenPermissionDenied() {
    when(permissionsChecker.getPermissions(NODE_ID, REQUESTER_ID))
        .thenReturn(aclWith(ACL.SharePermission.READ_ONLY));

    assertThatThrownBy(
            () ->
                collaborationLinkApi.createCollaborationLink(
                    NODE_ID, SharePermission.READ_AND_SHARE))
        .isInstanceOf(FilesGraphQLException.class)
        .satisfies(
            ex ->
                assertThat(((FilesGraphQLException) ex).getErrorCode())
                    .isEqualTo(ErrorCodes.NODE_WRITE_ERROR));
  }

  // ─── deleteCollaborationLinks ──────────────────────────────────────────────────

  @Test
  void deleteCollaborationLinksHappyPathReturnsDeletedIds() throws FilesGraphQLException {
    CollaborationLink link1 =
        mockCollaborationLink(COLLAB_UUID_1, NODE_ID, ACL.SharePermission.READ_AND_SHARE);
    CollaborationLink link2 =
        mockCollaborationLink(COLLAB_UUID_2, NODE_ID, ACL.SharePermission.READ_WRITE_AND_SHARE);
    when(collaborationLinkRepository.getLinkById(COLLAB_UUID_1)).thenReturn(Optional.of(link1));
    when(collaborationLinkRepository.getLinkById(COLLAB_UUID_2)).thenReturn(Optional.of(link2));
    when(permissionsChecker.getPermissions(NODE_ID, REQUESTER_ID))
        .thenReturn(aclWith(ACL.SharePermission.READ_WRITE_AND_SHARE));

    List<String> result =
        collaborationLinkApi.deleteCollaborationLinks(
            List.of(COLLAB_UUID_1.toString(), COLLAB_UUID_2.toString()));

    assertThat(result)
        .containsExactlyInAnyOrder(COLLAB_UUID_1.toString(), COLLAB_UUID_2.toString());
  }

  @Test
  void deleteCollaborationLinksPartialSuccessThrowsMissingField() {
    CollaborationLink link1 =
        mockCollaborationLink(COLLAB_UUID_1, NODE_ID, ACL.SharePermission.READ_AND_SHARE);
    when(collaborationLinkRepository.getLinkById(COLLAB_UUID_1)).thenReturn(Optional.of(link1));
    when(collaborationLinkRepository.getLinkById(COLLAB_UUID_2)).thenReturn(Optional.empty());
    when(permissionsChecker.getPermissions(NODE_ID, REQUESTER_ID))
        .thenReturn(aclWith(ACL.SharePermission.READ_AND_SHARE));

    assertThatThrownBy(
            () ->
                collaborationLinkApi.deleteCollaborationLinks(
                    List.of(COLLAB_UUID_1.toString(), COLLAB_UUID_2.toString())))
        .isInstanceOf(FilesGraphQLException.class)
        .satisfies(
            ex -> {
              FilesGraphQLException fex = (FilesGraphQLException) ex;
              assertThat(fex.getErrorCode()).isEqualTo(ErrorCodes.MISSING_FIELD);
              assertThat(fex.getPartialResults()).isInstanceOf(List.class);
              @SuppressWarnings("unchecked")
              List<String> partial = (List<String>) fex.getPartialResults();
              assertThat(partial).containsExactly(COLLAB_UUID_1.toString());
            });
  }
}
