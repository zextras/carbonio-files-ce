// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zextras.carbonio.files.dal.dao.ebean.FileVersion;
import com.zextras.carbonio.files.dal.dao.ebean.Link;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeCategory;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.dao.ebean.TrashedNode;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.FileVersionSort;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.LinkRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.graphql.errors.ErrorCodes;
import com.zextras.carbonio.files.graphql.errors.FilesGraphQLException;
import com.zextras.carbonio.files.graphql.model.PublicFileModel;
import com.zextras.carbonio.files.graphql.model.PublicFolderModel;
import com.zextras.carbonio.files.graphql.model.PublicNodeModel;
import com.zextras.carbonio.files.graphql.model.PublicNodePageModel;
import java.util.List;
import java.util.Optional;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PublicApiTest {

  private static final String NODE_ID = "00000000-0000-0000-0000-000000000001";
  private static final String LINK_PUBLIC_ID = "abcdef0123456789abcdef0123456789";
  private static final String ACCESS_CODE = "secret";

  private LinkRepository linkRepository;
  private NodeRepository nodeRepository;
  private FileVersionRepository fileVersionRepository;
  private PublicApi publicApi;

  @BeforeEach
  void setUp() {
    linkRepository = mock(LinkRepository.class);
    nodeRepository = mock(NodeRepository.class);
    fileVersionRepository = mock(FileVersionRepository.class);

    publicApi = new PublicApi();
    publicApi.linkRepository = linkRepository;
    publicApi.nodeRepository = nodeRepository;
    publicApi.fileVersionRepository = fileVersionRepository;
  }

  private Link mockLink(String nodeId, Optional<String> accessCode) {
    Link link = mock(Link.class);
    when(link.getNodeId()).thenReturn(nodeId);
    when(link.getAccessCode()).thenReturn(accessCode);
    return link;
  }

  private Node mockFolderNode(String id) {
    Node node = mock(Node.class);
    when(node.getId()).thenReturn(id);
    when(node.getCreatedAt()).thenReturn(1000L);
    when(node.getUpdatedAt()).thenReturn(2000L);
    when(node.getName()).thenReturn("folder");
    when(node.getNodeCategory()).thenReturn(NodeCategory.FOLDER);
    when(node.getNodeType()).thenReturn(NodeType.FOLDER);
    return node;
  }

  private Node mockFileNode(String id) {
    FileVersion fv = mock(FileVersion.class);
    when(fv.getMimeType()).thenReturn("text/plain");

    Node node = mock(Node.class);
    when(node.getId()).thenReturn(id);
    when(node.getCreatedAt()).thenReturn(1000L);
    when(node.getUpdatedAt()).thenReturn(2000L);
    when(node.getName()).thenReturn("file.txt");
    when(node.getNodeCategory()).thenReturn(NodeCategory.FILE);
    when(node.getNodeType()).thenReturn(NodeType.TEXT);
    when(node.getExtension()).thenReturn(Optional.of("txt"));
    when(node.getSize()).thenReturn(512L);
    when(fileVersionRepository.getFileVersions(eq(id), eq(List.of(FileVersionSort.VERSION_DESC))))
        .thenReturn(List.of(fv));
    return node;
  }

  // ─── getPublicNode ─────────────────────────────────────────────────────────

  @Test
  void getPublicNode_happyPath_file_returnsPublicFileModel() throws FilesGraphQLException {
    Link link = mockLink(NODE_ID, Optional.empty());
    when(linkRepository.getLinkByNotExpiredPublicId(LINK_PUBLIC_ID)).thenReturn(Optional.of(link));
    Node node = mockFileNode(NODE_ID);
    when(nodeRepository.getNode(NODE_ID)).thenReturn(Optional.of(node));
    when(nodeRepository.getTrashedNode(NODE_ID)).thenReturn(Optional.empty());

    PublicNodeModel result = publicApi.getPublicNode(LINK_PUBLIC_ID, null);

    assertThat(result).isInstanceOf(PublicFileModel.class);
    assertThat(result.getId()).isEqualTo(NODE_ID);
  }

  @Test
  void getPublicNode_happyPath_folder_returnsPublicFolderModel() throws FilesGraphQLException {
    Link link = mockLink(NODE_ID, Optional.empty());
    when(linkRepository.getLinkByNotExpiredPublicId(LINK_PUBLIC_ID)).thenReturn(Optional.of(link));
    Node node = mockFolderNode(NODE_ID);
    when(nodeRepository.getNode(NODE_ID)).thenReturn(Optional.of(node));
    when(nodeRepository.getTrashedNode(NODE_ID)).thenReturn(Optional.empty());

    PublicNodeModel result = publicApi.getPublicNode(LINK_PUBLIC_ID, null);

    assertThat(result).isInstanceOf(PublicFolderModel.class);
    assertThat(result.getId()).isEqualTo(NODE_ID);
  }

  @Test
  void getPublicNode_linkNotFound_throwsLinkNotFound() {
    when(linkRepository.getLinkByNotExpiredPublicId(LINK_PUBLIC_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> publicApi.getPublicNode(LINK_PUBLIC_ID, null))
        .isInstanceOf(FilesGraphQLException.class)
        .extracting(e -> ((FilesGraphQLException) e).getErrorCode())
        .isEqualTo(ErrorCodes.LINK_NOT_FOUND);
  }

  @Test
  void getPublicNode_nodeTrashed_throwsNodeNotFound() {
    Link link = mockLink(NODE_ID, Optional.empty());
    when(linkRepository.getLinkByNotExpiredPublicId(LINK_PUBLIC_ID)).thenReturn(Optional.of(link));
    Node node = mockFolderNode(NODE_ID);
    when(nodeRepository.getNode(NODE_ID)).thenReturn(Optional.of(node));
    TrashedNode trashed = mock(TrashedNode.class);
    when(nodeRepository.getTrashedNode(NODE_ID)).thenReturn(Optional.of(trashed));

    assertThatThrownBy(() -> publicApi.getPublicNode(LINK_PUBLIC_ID, null))
        .isInstanceOf(FilesGraphQLException.class)
        .extracting(e -> ((FilesGraphQLException) e).getErrorCode())
        .isEqualTo(ErrorCodes.NODE_NOT_FOUND);
  }

  @Test
  void getPublicNode_accessCodeRequiredButNull_throwsAccessCodeRequired() {
    Link link = mockLink(NODE_ID, Optional.of(ACCESS_CODE));
    when(linkRepository.getLinkByNotExpiredPublicId(LINK_PUBLIC_ID)).thenReturn(Optional.of(link));
    Node node = mockFolderNode(NODE_ID);
    when(nodeRepository.getNode(NODE_ID)).thenReturn(Optional.of(node));
    when(nodeRepository.getTrashedNode(NODE_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> publicApi.getPublicNode(LINK_PUBLIC_ID, null))
        .isInstanceOf(FilesGraphQLException.class)
        .extracting(e -> ((FilesGraphQLException) e).getErrorCode())
        .isEqualTo(ErrorCodes.ACCESS_CODE_REQUIRED);
  }

  @Test
  void getPublicNode_wrongAccessCode_throwsWrongAccessCode() {
    Link link = mockLink(NODE_ID, Optional.of(ACCESS_CODE));
    when(linkRepository.getLinkByNotExpiredPublicId(LINK_PUBLIC_ID)).thenReturn(Optional.of(link));
    Node node = mockFolderNode(NODE_ID);
    when(nodeRepository.getNode(NODE_ID)).thenReturn(Optional.of(node));
    when(nodeRepository.getTrashedNode(NODE_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> publicApi.getPublicNode(LINK_PUBLIC_ID, "wrong"))
        .isInstanceOf(FilesGraphQLException.class)
        .extracting(e -> ((FilesGraphQLException) e).getErrorCode())
        .isEqualTo(ErrorCodes.WRONG_ACCESS_CODE);
  }

  @Test
  void getPublicNode_nodeNotFoundInRepo_throwsNodeNotFound() {
    Link link = mockLink(NODE_ID, Optional.empty());
    when(linkRepository.getLinkByNotExpiredPublicId(LINK_PUBLIC_ID)).thenReturn(Optional.of(link));
    when(nodeRepository.getNode(NODE_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> publicApi.getPublicNode(LINK_PUBLIC_ID, null))
        .isInstanceOf(FilesGraphQLException.class)
        .extracting(e -> ((FilesGraphQLException) e).getErrorCode())
        .isEqualTo(ErrorCodes.NODE_NOT_FOUND);
  }

  // ─── findPublicNodes ────────────────────────────────────────────────────────

  @Test
  void findPublicNodes_happyPath_mapsNodesAndReturnsPage() throws FilesGraphQLException {
    Node folder = mockFolderNode(NODE_ID);
    when(nodeRepository.getNode(NODE_ID)).thenReturn(Optional.of(folder));
    when(linkRepository.isLinkValidForNode(LINK_PUBLIC_ID, folder)).thenReturn(true);

    Link link = mockLink(NODE_ID, Optional.empty());
    when(linkRepository.getLinkByNotExpiredPublicId(LINK_PUBLIC_ID)).thenReturn(Optional.of(link));

    Node childFile = mockFileNode("child-id");
    when(nodeRepository.publicFindNodes(NODE_ID, 10, null))
        .thenReturn(ImmutablePair.of(List.of(childFile), "next-token"));

    PublicNodePageModel result = publicApi.findPublicNodes(NODE_ID, 10, LINK_PUBLIC_ID, null, null);

    assertThat(result.getNodes()).hasSize(1);
    assertThat(result.getNodes().get(0)).isInstanceOf(PublicFileModel.class);
    assertThat(result.getPageToken()).isEqualTo("next-token");
  }

  @Test
  void findPublicNodes_folderNotFound_throwsNodeNotFound() {
    when(nodeRepository.getNode(NODE_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> publicApi.findPublicNodes(NODE_ID, null, LINK_PUBLIC_ID, null, null))
        .isInstanceOf(FilesGraphQLException.class)
        .extracting(e -> ((FilesGraphQLException) e).getErrorCode())
        .isEqualTo(ErrorCodes.NODE_NOT_FOUND);
  }

  @Test
  void findPublicNodes_linkNotValidForNode_throwsNodeNotFound() {
    Node folder = mockFolderNode(NODE_ID);
    when(nodeRepository.getNode(NODE_ID)).thenReturn(Optional.of(folder));
    when(linkRepository.isLinkValidForNode(LINK_PUBLIC_ID, folder)).thenReturn(false);

    assertThatThrownBy(() -> publicApi.findPublicNodes(NODE_ID, null, LINK_PUBLIC_ID, null, null))
        .isInstanceOf(FilesGraphQLException.class)
        .extracting(e -> ((FilesGraphQLException) e).getErrorCode())
        .isEqualTo(ErrorCodes.NODE_NOT_FOUND);
  }

  @Test
  void findPublicNodes_accessCodeMismatch_throwsAccessCodeRequired() {
    Node folder = mockFolderNode(NODE_ID);
    when(nodeRepository.getNode(NODE_ID)).thenReturn(Optional.of(folder));
    when(linkRepository.isLinkValidForNode(LINK_PUBLIC_ID, folder)).thenReturn(true);

    Link link = mockLink(NODE_ID, Optional.of(ACCESS_CODE));
    when(linkRepository.getLinkByNotExpiredPublicId(LINK_PUBLIC_ID)).thenReturn(Optional.of(link));

    assertThatThrownBy(
            () -> publicApi.findPublicNodes(NODE_ID, null, LINK_PUBLIC_ID, "wrong", null))
        .isInstanceOf(FilesGraphQLException.class)
        .extracting(e -> ((FilesGraphQLException) e).getErrorCode())
        .isEqualTo(ErrorCodes.ACCESS_CODE_REQUIRED);
  }

  @Test
  void findPublicNodes_accessCodeNullWhenRequired_throwsAccessCodeRequired()
      throws FilesGraphQLException {
    Node folder = mockFolderNode(NODE_ID);
    when(nodeRepository.getNode(NODE_ID)).thenReturn(Optional.of(folder));
    when(linkRepository.isLinkValidForNode(LINK_PUBLIC_ID, folder)).thenReturn(true);

    Link link = mockLink(NODE_ID, Optional.of(ACCESS_CODE));
    when(linkRepository.getLinkByNotExpiredPublicId(LINK_PUBLIC_ID)).thenReturn(Optional.of(link));

    assertThatThrownBy(() -> publicApi.findPublicNodes(NODE_ID, null, LINK_PUBLIC_ID, null, null))
        .isInstanceOf(FilesGraphQLException.class)
        .extracting(e -> ((FilesGraphQLException) e).getErrorCode())
        .isEqualTo(ErrorCodes.ACCESS_CODE_REQUIRED);
  }
}
