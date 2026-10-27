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

import com.zextras.carbonio.files.config.SharingPolicy;
import com.zextras.carbonio.files.dal.dao.UserId;
import com.zextras.carbonio.files.dal.dao.UserMyself;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.dal.dao.ebean.Link;
import com.zextras.carbonio.files.dal.repositories.interfaces.LinkRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.graphql.errors.ErrorCodes;
import com.zextras.carbonio.files.graphql.errors.FilesGraphQLException;
import com.zextras.carbonio.files.graphql.validation.GraphQLInputValidator;
import com.zextras.carbonio.files.utilities.PermissionsChecker;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LinkApiTest {

  private static final String REQUESTER_ID = "requester-user-id";
  private static final String NODE_ID = "00000000-0000-0000-0000-000000000001";
  private static final String LINK_ID_1 = "00000000-0000-0000-0000-000000000010";

  private LinkRepository linkRepository;
  private NodeRepository nodeRepository;
  private PermissionsChecker permissionsChecker;
  private LinkApi linkApi;

  @BeforeEach
  void setUp() {
    linkRepository = mock(LinkRepository.class);
    nodeRepository = mock(NodeRepository.class);
    permissionsChecker = mock(PermissionsChecker.class);

    UserMyself requester = mock(UserMyself.class);
    when(requester.getId()).thenReturn(new UserId(REQUESTER_ID));

    linkApi = new LinkApi();
    linkApi.linkRepository = linkRepository;
    linkApi.nodeRepository = nodeRepository;
    linkApi.permissionsChecker = permissionsChecker;
    linkApi.validator = new GraphQLInputValidator();
    linkApi.requester = requester;
    SharingPolicy sharingPolicy = mock(SharingPolicy.class);
    when(sharingPolicy.isEnabled(any(), any())).thenReturn(true);
    linkApi.sharingPolicy = sharingPolicy;
  }

  private ACL aclWith(ACL.SharePermission perm) {
    return ACL.decode(perm);
  }

  private Link mockLink(String linkId, String nodeId, String publicId) {
    Link link = mock(Link.class);
    when(link.getLinkId()).thenReturn(linkId);
    when(link.getNodeId()).thenReturn(nodeId);
    when(link.getPublicId()).thenReturn(publicId);
    when(link.getCreatedAt()).thenReturn(1000L);
    when(link.getExpiresAt()).thenReturn(Optional.empty());
    when(link.getDescription()).thenReturn(Optional.empty());
    when(link.getAccessCode()).thenReturn(Optional.empty());
    return link;
  }

  @Test
  void updateLinkThrowsNodeNotFoundWhenTheLinkedNodeIsMissing() {
    Link link =
        mockLink(LINK_ID_1, NODE_ID, "pub1234567890123456789012345678901234567890123456789");
    when(linkRepository.getLinkById(LINK_ID_1)).thenReturn(Optional.of(link));
    when(permissionsChecker.getPermissions(NODE_ID, REQUESTER_ID))
        .thenReturn(aclWith(ACL.SharePermission.READ_AND_SHARE));
    when(nodeRepository.getNode(NODE_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> linkApi.updateLink(LINK_ID_1, null, null, null))
        .isInstanceOf(FilesGraphQLException.class)
        .satisfies(
            ex -> {
              FilesGraphQLException fex = (FilesGraphQLException) ex;
              assertThat(fex.getErrorCode()).isEqualTo(ErrorCodes.NODE_NOT_FOUND);
              assertThat(fex.getMessage()).isEqualTo("Could not find node with id " + NODE_ID);
            });
    verify(linkRepository, never()).updateLink(any());
  }

  @Test
  void updateLinkThrowsLinkNotFoundWhenPermissionDenied() {
    Link link =
        mockLink(LINK_ID_1, NODE_ID, "pub1234567890123456789012345678901234567890123456789");
    when(linkRepository.getLinkById(LINK_ID_1)).thenReturn(Optional.of(link));
    when(permissionsChecker.getPermissions(NODE_ID, REQUESTER_ID))
        .thenReturn(aclWith(ACL.SharePermission.READ_ONLY));

    assertThatThrownBy(() -> linkApi.updateLink(LINK_ID_1, null, null, null))
        .isInstanceOf(FilesGraphQLException.class)
        .satisfies(
            ex ->
                assertThat(((FilesGraphQLException) ex).getErrorCode())
                    .isEqualTo(ErrorCodes.LINK_NOT_FOUND));
  }
}
