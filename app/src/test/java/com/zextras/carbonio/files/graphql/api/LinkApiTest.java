// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zextras.carbonio.files.dal.dao.UserId;
import com.zextras.carbonio.files.dal.dao.UserMyself;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.dal.dao.ebean.Link;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeCategory;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.repositories.interfaces.LinkRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.graphql.errors.ErrorCodes;
import com.zextras.carbonio.files.graphql.errors.FilesGraphQLException;
import com.zextras.carbonio.files.graphql.model.LinkModel;
import com.zextras.carbonio.files.graphql.model.NodeModel;
import com.zextras.carbonio.files.graphql.validation.GraphQLInputValidator;
import com.zextras.carbonio.files.utilities.PermissionsChecker;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LinkApiTest {

  private static final String REQUESTER_ID = "requester-user-id";
  private static final String DOMAIN = "https://example.com";
  private static final String NODE_ID = "00000000-0000-0000-0000-000000000001";
  private static final String LINK_ID_1 = "00000000-0000-0000-0000-000000000010";
  private static final String LINK_ID_2 = "00000000-0000-0000-0000-000000000020";

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
    when(requester.getDomain()).thenReturn(DOMAIN);

    linkApi = new LinkApi();
    linkApi.linkRepository = linkRepository;
    linkApi.nodeRepository = nodeRepository;
    linkApi.permissionsChecker = permissionsChecker;
    linkApi.validator = new GraphQLInputValidator();
    linkApi.requester = requester;
  }

  private ACL aclWith(ACL.SharePermission perm) {
    return ACL.decode(perm);
  }

  private Node mockFileNode() {
    Node node = mock(Node.class);
    when(node.getNodeType()).thenReturn(NodeType.TEXT);
    return node;
  }

  private Node mockFolderNode() {
    Node node = mock(Node.class);
    when(node.getNodeType()).thenReturn(NodeType.FOLDER);
    return node;
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

  private Node mockFullFolderNode(String id) {
    Node node = mock(Node.class);
    when(node.getId()).thenReturn(id);
    when(node.getName()).thenReturn("folder");
    when(node.getNodeType()).thenReturn(NodeType.FOLDER);
    when(node.getNodeCategory()).thenReturn(NodeCategory.FOLDER);
    when(node.getDescription()).thenReturn(Optional.empty());
    when(node.getParentId()).thenReturn(Optional.of("parent-id"));
    when(node.getOwnerId()).thenReturn("owner-id");
    when(node.getCreatorId()).thenReturn("creator-id");
    when(node.getLastEditorId()).thenReturn(Optional.empty());
    when(node.getCreatedAt()).thenReturn(1000L);
    when(node.getUpdatedAt()).thenReturn(2000L);
    when(node.getAncestorsList()).thenReturn(List.of("LOCAL_ROOT"));
    when(node.getCustomAttributes()).thenReturn(List.of());
    return node;
  }

  // ─── links @Source ────────────────────────────────────────────────────────────

  @Test
  void links_permitted_returnsLinks() {
    NodeModel nodeModel = mock(NodeModel.class);
    when(nodeModel.getId()).thenReturn(NODE_ID);
    Node node = mockFileNode();
    Link link = mockLink(LINK_ID_1, NODE_ID, "pub1234567890123456789012345678901234567890123456");
    when(permissionsChecker.getPermissions(NODE_ID, REQUESTER_ID))
        .thenReturn(ACL.decode(ACL.SharePermission.READ_AND_SHARE));
    when(nodeRepository.getNode(NODE_ID)).thenReturn(Optional.of(node));
    when(linkRepository.getLinksByNodeId(eq(NODE_ID), any())).thenReturn(Stream.of(link));

    List<LinkModel> result = linkApi.links(nodeModel);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).getNodeId()).isEqualTo(NODE_ID);
  }

  @Test
  void links_notPermitted_returnsEmpty() {
    NodeModel nodeModel = mock(NodeModel.class);
    when(nodeModel.getId()).thenReturn(NODE_ID);
    when(permissionsChecker.getPermissions(NODE_ID, REQUESTER_ID))
        .thenReturn(ACL.decode(ACL.SharePermission.READ_ONLY));

    List<LinkModel> result = linkApi.links(nodeModel);

    assertThat(result).isEmpty();
    verify(linkRepository, never()).getLinksByNodeId(any(), any());
  }

  // ─── node @Source (LinkModel → Node) ─────────────────────────────────────────

  @Test
  void node_fromLink_returnsNodeModel() throws Exception {
    LinkModel linkModel = mock(LinkModel.class);
    when(linkModel.getNodeId()).thenReturn(NODE_ID);
    Node node = mockFullFolderNode(NODE_ID);
    when(nodeRepository.getNode(NODE_ID)).thenReturn(Optional.of(node));

    NodeModel result = linkApi.node(linkModel);

    assertThat(result).isNotNull();
    assertThat(result.getId()).isEqualTo(NODE_ID);
  }

  @Test
  void node_fromLink_nodeNotFound_throwsException() {
    LinkModel linkModel = mock(LinkModel.class);
    when(linkModel.getNodeId()).thenReturn(NODE_ID);
    when(nodeRepository.getNode(NODE_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> linkApi.node(linkModel))
        .isInstanceOf(FilesGraphQLException.class)
        .satisfies(
            ex ->
                assertThat(((FilesGraphQLException) ex).getErrorCode())
                    .isEqualTo(ErrorCodes.NODE_NOT_FOUND));
  }

  // ─── getLinks ──────────────────────────────────────────────────────────────────

  @Test
  void getLinksHappyPathReturnsLinkModels() throws FilesGraphQLException {
    Node node = mockFileNode();
    Link link =
        mockLink(LINK_ID_1, NODE_ID, "pub1234567890123456789012345678901234567890123456789");
    when(permissionsChecker.getPermissions(NODE_ID, REQUESTER_ID))
        .thenReturn(aclWith(ACL.SharePermission.READ_AND_SHARE));
    when(nodeRepository.getNode(NODE_ID)).thenReturn(Optional.of(node));
    when(linkRepository.getLinksByNodeId(eq(NODE_ID), any())).thenReturn(Stream.of(link));

    List<LinkModel> result = linkApi.getLinks(NODE_ID);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).getId()).isEqualTo(LINK_ID_1);
    assertThat(result.get(0).getUrl()).contains("/download/");
  }

  @Test
  void getLinksReturnsEmptyListWhenPermissionDenied() throws FilesGraphQLException {
    Node node = mockFileNode();
    when(permissionsChecker.getPermissions(NODE_ID, REQUESTER_ID))
        .thenReturn(aclWith(ACL.SharePermission.READ_ONLY));
    when(nodeRepository.getNode(NODE_ID)).thenReturn(Optional.of(node));

    List<LinkModel> result = linkApi.getLinks(NODE_ID);

    assertThat(result).isEmpty();
    verify(linkRepository, never()).getLinksByNodeId(any(), any());
  }

  @Test
  void getLinksReturnsEmptyListWhenNodeNotFound() throws FilesGraphQLException {
    when(permissionsChecker.getPermissions(NODE_ID, REQUESTER_ID))
        .thenReturn(aclWith(ACL.SharePermission.READ_AND_SHARE));
    when(nodeRepository.getNode(NODE_ID)).thenReturn(Optional.empty());

    List<LinkModel> result = linkApi.getLinks(NODE_ID);

    assertThat(result).isEmpty();
  }

  // ─── createLink ────────────────────────────────────────────────────────────────

  @Test
  void createLinkHappyPathReturnsLinkModel() throws FilesGraphQLException {
    Node node = mockFileNode();
    Link link =
        mockLink(LINK_ID_1, NODE_ID, "pub1234567890123456789012345678901234567890123456789");
    when(permissionsChecker.getPermissions(NODE_ID, REQUESTER_ID))
        .thenReturn(aclWith(ACL.SharePermission.READ_AND_SHARE));
    when(nodeRepository.getNode(NODE_ID)).thenReturn(Optional.of(node));
    when(linkRepository.getLinkCountByNode(node)).thenReturn(0);
    when(linkRepository.createLink(any(), eq(NODE_ID), any(), any(), any(), any()))
        .thenReturn(link);

    LinkModel result = linkApi.createLink(NODE_ID, null, null, null);

    assertThat(result).isNotNull();
    assertThat(result.getId()).isEqualTo(LINK_ID_1);
    assertThat(result.getUrl()).contains("/download/");
  }

  @Test
  void createLinkForFolderUsesAccessUrl() throws FilesGraphQLException {
    Node folder = mockFolderNode();
    Link link =
        mockLink(LINK_ID_1, NODE_ID, "pub1234567890123456789012345678901234567890123456789");
    when(permissionsChecker.getPermissions(NODE_ID, REQUESTER_ID))
        .thenReturn(aclWith(ACL.SharePermission.READ_AND_SHARE));
    when(nodeRepository.getNode(NODE_ID)).thenReturn(Optional.of(folder));
    when(linkRepository.getLinkCountByNode(folder)).thenReturn(0);
    when(linkRepository.createLink(any(), eq(NODE_ID), any(), any(), any(), any()))
        .thenReturn(link);

    LinkModel result = linkApi.createLink(NODE_ID, null, null, null);

    assertThat(result.getUrl()).contains("/access/");
  }

  @Test
  void createLinkThrowsNodeWriteErrorWhenPermissionDenied() {
    Node node = mockFileNode();
    when(permissionsChecker.getPermissions(NODE_ID, REQUESTER_ID))
        .thenReturn(aclWith(ACL.SharePermission.READ_ONLY));
    when(nodeRepository.getNode(NODE_ID)).thenReturn(Optional.of(node));

    assertThatThrownBy(() -> linkApi.createLink(NODE_ID, null, null, null))
        .isInstanceOf(FilesGraphQLException.class)
        .satisfies(
            ex ->
                assertThat(((FilesGraphQLException) ex).getErrorCode())
                    .isEqualTo(ErrorCodes.NODE_WRITE_ERROR));
  }

  @Test
  void createLinkThrowsLinkLimitExceededWhenAtCap() {
    Node node = mockFileNode();
    when(permissionsChecker.getPermissions(NODE_ID, REQUESTER_ID))
        .thenReturn(aclWith(ACL.SharePermission.READ_AND_SHARE));
    when(nodeRepository.getNode(NODE_ID)).thenReturn(Optional.of(node));
    when(linkRepository.getLinkCountByNode(node)).thenReturn(50);

    assertThatThrownBy(() -> linkApi.createLink(NODE_ID, null, null, null))
        .isInstanceOf(FilesGraphQLException.class)
        .satisfies(
            ex ->
                assertThat(((FilesGraphQLException) ex).getErrorCode())
                    .isEqualTo(ErrorCodes.LINK_LIMIT_EXCEEDED));
  }

  // ─── updateLink ────────────────────────────────────────────────────────────────

  @Test
  void updateLinkHappyPathReturnsUpdatedModel() throws FilesGraphQLException {
    Link link =
        mockLink(LINK_ID_1, NODE_ID, "pub1234567890123456789012345678901234567890123456789");
    Node node = mockFileNode();
    when(linkRepository.getLinkById(LINK_ID_1)).thenReturn(Optional.of(link));
    when(permissionsChecker.getPermissions(NODE_ID, REQUESTER_ID))
        .thenReturn(aclWith(ACL.SharePermission.READ_AND_SHARE));
    when(nodeRepository.getNode(NODE_ID)).thenReturn(Optional.of(node));
    when(linkRepository.updateLink(link)).thenReturn(link);

    LinkModel result = linkApi.updateLink(LINK_ID_1, null, null, null);

    assertThat(result).isNotNull();
    assertThat(result.getId()).isEqualTo(LINK_ID_1);
  }

  @Test
  void updateLinkThrowsLinkNotFoundWhenMissing() {
    when(linkRepository.getLinkById(LINK_ID_1)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> linkApi.updateLink(LINK_ID_1, null, null, null))
        .isInstanceOf(FilesGraphQLException.class)
        .satisfies(
            ex ->
                assertThat(((FilesGraphQLException) ex).getErrorCode())
                    .isEqualTo(ErrorCodes.LINK_NOT_FOUND));
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

  // ─── deleteLinks ───────────────────────────────────────────────────────────────

  @Test
  void deleteLinksHappyPathReturnsDeletedIds() throws FilesGraphQLException {
    Link link1 = mockLink(LINK_ID_1, NODE_ID, "pub1");
    Link link2 = mockLink(LINK_ID_2, NODE_ID, "pub2");
    when(linkRepository.getLinkById(LINK_ID_1)).thenReturn(Optional.of(link1));
    when(linkRepository.getLinkById(LINK_ID_2)).thenReturn(Optional.of(link2));
    when(permissionsChecker.getPermissions(NODE_ID, REQUESTER_ID))
        .thenReturn(aclWith(ACL.SharePermission.READ_AND_SHARE));

    List<String> result = linkApi.deleteLinks(List.of(LINK_ID_1, LINK_ID_2));

    assertThat(result).containsExactlyInAnyOrder(LINK_ID_1, LINK_ID_2);
    verify(linkRepository).deleteLinksBulk(List.of(LINK_ID_1, LINK_ID_2));
  }

  @Test
  void deleteLinksPartialSuccessThrowsWithSuccessfulIdsAsPartialResult() {
    Link link1 = mockLink(LINK_ID_1, NODE_ID, "pub1");
    when(linkRepository.getLinkById(LINK_ID_1)).thenReturn(Optional.of(link1));
    when(linkRepository.getLinkById(LINK_ID_2)).thenReturn(Optional.empty());
    when(permissionsChecker.getPermissions(NODE_ID, REQUESTER_ID))
        .thenReturn(aclWith(ACL.SharePermission.READ_AND_SHARE));

    assertThatThrownBy(() -> linkApi.deleteLinks(List.of(LINK_ID_1, LINK_ID_2)))
        .isInstanceOf(FilesGraphQLException.class)
        .satisfies(
            ex -> {
              FilesGraphQLException fex = (FilesGraphQLException) ex;
              assertThat(fex.getErrorCode()).isEqualTo(ErrorCodes.LINK_NOT_FOUND);
              assertThat(fex.getPartialResults()).isInstanceOf(List.class);
              @SuppressWarnings("unchecked")
              List<String> partial = (List<String>) fex.getPartialResults();
              assertThat(partial).containsExactly(LINK_ID_1);
            });
  }
}
