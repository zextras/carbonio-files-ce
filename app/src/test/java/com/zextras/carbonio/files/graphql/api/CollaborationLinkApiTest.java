// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zextras.carbonio.files.config.SharingPolicy;
import com.zextras.carbonio.files.dal.dao.UserId;
import com.zextras.carbonio.files.dal.dao.UserMyself;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.dal.dao.ebean.CollaborationLink;
import com.zextras.carbonio.files.dal.repositories.interfaces.CollaborationLinkRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.graphql.errors.FilesGraphQLException;
import com.zextras.carbonio.files.graphql.model.CollaborationLinkModel;
import com.zextras.carbonio.files.graphql.model.SharePermission;
import com.zextras.carbonio.files.graphql.validation.GraphQLInputValidator;
import com.zextras.carbonio.files.utilities.PermissionsChecker;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CollaborationLinkApiTest {

  private static final String REQUESTER_ID = "requester-user-id";
  private static final String NODE_ID = "00000000-0000-0000-0000-000000000001";
  private static final UUID COLLAB_UUID_1 = UUID.fromString("00000000-0000-0000-0000-000000000010");

  private CollaborationLinkRepository collaborationLinkRepository;
  private NodeRepository nodeRepository;
  private PermissionsChecker permissionsChecker;
  private CollaborationLinkApi collaborationLinkApi;

  @BeforeEach
  void setUp() {
    collaborationLinkRepository = mock(CollaborationLinkRepository.class);
    nodeRepository = mock(NodeRepository.class);
    permissionsChecker = mock(PermissionsChecker.class);

    UserMyself requester = mock(UserMyself.class);
    when(requester.getId()).thenReturn(new UserId(REQUESTER_ID));

    collaborationLinkApi = new CollaborationLinkApi();
    collaborationLinkApi.collaborationLinkRepository = collaborationLinkRepository;
    collaborationLinkApi.nodeRepository = nodeRepository;
    collaborationLinkApi.permissionsChecker = permissionsChecker;
    collaborationLinkApi.validator = new GraphQLInputValidator();
    collaborationLinkApi.requester = requester;
    SharingPolicy sharingPolicy = mock(SharingPolicy.class);
    when(sharingPolicy.isEnabled(any(), any())).thenReturn(true);
    when(sharingPolicy.allowsGrant(any(), any())).thenReturn(true);
    collaborationLinkApi.sharingPolicy = sharingPolicy;
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
}
