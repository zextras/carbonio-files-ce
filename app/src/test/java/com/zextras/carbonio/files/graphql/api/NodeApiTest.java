// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zextras.carbonio.files.dal.dao.UserId;
import com.zextras.carbonio.files.dal.dao.UserMyself;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import com.zextras.carbonio.files.dal.dao.ebean.FileVersion;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeCategory;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.ShareRepository;
import com.zextras.carbonio.files.graphql.errors.ErrorCodes;
import com.zextras.carbonio.files.graphql.errors.FilesGraphQLException;
import com.zextras.carbonio.files.graphql.model.FileModel;
import com.zextras.carbonio.files.graphql.model.FolderModel;
import com.zextras.carbonio.files.graphql.model.NodeModel;
import com.zextras.carbonio.files.graphql.model.NodePageModel;
import com.zextras.carbonio.files.graphql.model.RootModel;
import com.zextras.carbonio.files.graphql.validation.GraphQLInputValidator;
import com.zextras.carbonio.files.utilities.PermissionsChecker;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NodeApiTest {

  private static final String REQUESTER_ID = "requester-user-id";
  private static final String NODE_ID = "00000000-0000-0000-0000-000000000001";

  private NodeRepository nodeRepository;
  private FileVersionRepository fileVersionRepository;
  private PermissionsChecker permissionsChecker;
  private ShareRepository shareRepository;
  private GraphQLInputValidator validator;
  private NodeApi nodeApi;

  @BeforeEach
  void setUp() {
    nodeRepository = mock(NodeRepository.class);
    fileVersionRepository = mock(FileVersionRepository.class);
    permissionsChecker = mock(PermissionsChecker.class);
    shareRepository = mock(ShareRepository.class);
    validator = new GraphQLInputValidator();

    UserMyself requester = mock(UserMyself.class);
    UserId userId = new UserId(REQUESTER_ID);
    when(requester.getId()).thenReturn(userId);

    nodeApi = new NodeApi();
    nodeApi.nodeRepository = nodeRepository;
    nodeApi.fileVersionRepository = fileVersionRepository;
    nodeApi.permissionsChecker = permissionsChecker;
    nodeApi.shareRepository = shareRepository;
    nodeApi.validator = validator;
    nodeApi.requester = requester;
  }

  private Node mockFileNode(String id) {
    Node node = mock(Node.class);
    when(node.getId()).thenReturn(id);
    when(node.getName()).thenReturn("testfile");
    when(node.getNodeType()).thenReturn(NodeType.TEXT);
    when(node.getNodeCategory()).thenReturn(NodeCategory.FILE);
    when(node.getDescription()).thenReturn(Optional.empty());
    when(node.getParentId()).thenReturn(Optional.of("parent-id"));
    when(node.getOwnerId()).thenReturn("owner-id");
    when(node.getCreatorId()).thenReturn("creator-id");
    when(node.getLastEditorId()).thenReturn(Optional.of("editor-id"));
    when(node.getCreatedAt()).thenReturn(1000L);
    when(node.getUpdatedAt()).thenReturn(2000L);
    when(node.getExtension()).thenReturn(Optional.of("txt"));
    when(node.getCurrentVersion()).thenReturn(1);
    when(node.getAncestorsList()).thenReturn(List.of("LOCAL_ROOT"));
    when(node.getCustomAttributes()).thenReturn(Collections.emptyList());

    FileVersion fv = mock(FileVersion.class);
    when(fv.getVersion()).thenReturn(1);
    when(fv.getMimeType()).thenReturn("text/plain");
    when(fv.getSize()).thenReturn(512L);
    when(fv.getDigest()).thenReturn("abc123");
    when(fv.isKeptForever()).thenReturn(false);
    when(fv.getClonedFromVersion()).thenReturn(Optional.empty());
    when(fv.getLastEditorId()).thenReturn("editor-id");
    when(fv.getUpdatedAt()).thenReturn(2000L);

    when(node.getFileVersions()).thenReturn(List.of(fv));
    return node;
  }

  private Node mockFolderNode(String id) {
    Node node = mock(Node.class);
    when(node.getId()).thenReturn(id);
    when(node.getName()).thenReturn("testfolder");
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
    when(node.getCustomAttributes()).thenReturn(Collections.emptyList());
    return node;
  }

  private ACL aclWith(boolean readOnly) {
    return readOnly ? ACL.decode(SharePermission.READ_ONLY) : ACL.decode((short) 0);
  }

  // --- getNode ---

  @Test
  void getNodeReturnsFileModelForFileNode() throws Exception {
    Node node = mockFileNode(NODE_ID);
    when(permissionsChecker.getPermissions(NODE_ID, REQUESTER_ID)).thenReturn(aclWith(true));
    when(nodeRepository.getNode(NODE_ID)).thenReturn(Optional.of(node));

    NodeModel result = nodeApi.getNode(NODE_ID, null);

    assertThat(result).isInstanceOf(FileModel.class);
    FileModel fileModel = (FileModel) result;
    assertThat(fileModel.getId()).isEqualTo(NODE_ID);
    assertThat(fileModel.getName()).isEqualTo("testfile");
    assertThat(fileModel.getMimeType()).isEqualTo("text/plain");
    assertThat(fileModel.getVersion()).isEqualTo(1);
  }

  @Test
  void getNodeReturnsFolderModelForFolderNode() throws Exception {
    Node node = mockFolderNode(NODE_ID);
    when(permissionsChecker.getPermissions(NODE_ID, REQUESTER_ID)).thenReturn(aclWith(true));
    when(nodeRepository.getNode(NODE_ID)).thenReturn(Optional.of(node));

    NodeModel result = nodeApi.getNode(NODE_ID, null);

    assertThat(result).isInstanceOf(FolderModel.class);
    FolderModel folderModel = (FolderModel) result;
    assertThat(folderModel.getId()).isEqualTo(NODE_ID);
    assertThat(folderModel.getName()).isEqualTo("testfolder");
  }

  @Test
  void getNodeThrowsNodeNotFoundWhenPermissionDenied() throws Exception {
    when(permissionsChecker.getPermissions(NODE_ID, REQUESTER_ID)).thenReturn(aclWith(false));

    assertThatThrownBy(() -> nodeApi.getNode(NODE_ID, null))
        .isInstanceOf(FilesGraphQLException.class)
        .satisfies(
            ex ->
                assertThat(((FilesGraphQLException) ex).getErrorCode())
                    .isEqualTo(ErrorCodes.NODE_NOT_FOUND));
  }

  @Test
  void getNodeThrowsNodeNotFoundWhenNodeDoesNotExist() throws Exception {
    when(permissionsChecker.getPermissions(NODE_ID, REQUESTER_ID)).thenReturn(aclWith(true));
    when(nodeRepository.getNode(NODE_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> nodeApi.getNode(NODE_ID, null))
        .isInstanceOf(FilesGraphQLException.class)
        .satisfies(
            ex ->
                assertThat(((FilesGraphQLException) ex).getErrorCode())
                    .isEqualTo(ErrorCodes.NODE_NOT_FOUND));
  }

  @Test
  void getNodeUsesSpecifiedVersionWhenProvided() throws Exception {
    Node node = mockFileNode(NODE_ID);
    FileVersion fv1 = node.getFileVersions().get(0);

    FileVersion fv2 = mock(FileVersion.class);
    when(fv2.getVersion()).thenReturn(2);
    when(fv2.getMimeType()).thenReturn("text/plain");
    when(fv2.getSize()).thenReturn(1024L);
    when(fv2.getDigest()).thenReturn("def456");
    when(fv2.isKeptForever()).thenReturn(false);
    when(fv2.getClonedFromVersion()).thenReturn(Optional.empty());
    when(fv2.getLastEditorId()).thenReturn("editor-id");
    when(fv2.getUpdatedAt()).thenReturn(3000L);

    when(node.getFileVersions()).thenReturn(List.of(fv1, fv2));
    when(node.getCurrentVersion()).thenReturn(2);

    when(permissionsChecker.getPermissions(NODE_ID, REQUESTER_ID)).thenReturn(aclWith(true));
    when(nodeRepository.getNode(NODE_ID)).thenReturn(Optional.of(node));

    NodeModel result = nodeApi.getNode(NODE_ID, 2);

    assertThat(result).isInstanceOf(FileModel.class);
    assertThat(((FileModel) result).getVersion()).isEqualTo(2);
    assertThat(((FileModel) result).getSize()).isEqualTo(1024.0);
  }

  // --- findNodes ---

  @Test
  void findNodesReturnsMappedNodePage() throws Exception {
    Node node = mockFileNode(NODE_ID);
    when(nodeRepository.findNodes(
            eq(REQUESTER_ID),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any()))
        .thenReturn(ImmutablePair.of(List.of(node), "next-page-token"));

    NodePageModel result =
        nodeApi.findNodes(null, null, null, null, null, null, null, null, null, null, null, null);

    assertThat(result.getNodes()).hasSize(1);
    assertThat(result.getNodes().get(0)).isInstanceOf(FileModel.class);
    assertThat(result.getPageToken()).isEqualTo("next-page-token");
  }

  @Test
  void findNodesPassesEmptyKeywordsListWhenKeywordsNull() throws Exception {
    when(nodeRepository.findNodes(
            eq(REQUESTER_ID),
            eq(Optional.empty()),
            eq(Optional.empty()),
            eq(Optional.empty()),
            eq(Optional.empty()),
            eq(Optional.empty()),
            eq(Optional.empty()),
            eq(Optional.empty()),
            eq(Optional.empty()),
            eq(Optional.empty()),
            eq(Optional.empty()),
            eq(Collections.emptyList()),
            eq(Optional.empty())))
        .thenReturn(ImmutablePair.of(Collections.emptyList(), null));

    NodePageModel result =
        nodeApi.findNodes(null, null, null, null, null, null, null, null, null, null, null, null);

    assertThat(result.getNodes()).isEmpty();
    verify(nodeRepository)
        .findNodes(
            eq(REQUESTER_ID),
            eq(Optional.empty()),
            eq(Optional.empty()),
            eq(Optional.empty()),
            eq(Optional.empty()),
            eq(Optional.empty()),
            eq(Optional.empty()),
            eq(Optional.empty()),
            eq(Optional.empty()),
            eq(Optional.empty()),
            eq(Optional.empty()),
            eq(Collections.emptyList()),
            eq(Optional.empty()));
  }

  // --- getRootsList ---

  @Test
  void getRootsListReturnsMappedRoots() throws Exception {
    Node root1 = mock(Node.class);
    when(root1.getId()).thenReturn("LOCAL_ROOT");
    when(root1.getName()).thenReturn("HOME");

    Node root2 = mock(Node.class);
    when(root2.getId()).thenReturn("TRASH_ROOT");
    when(root2.getName()).thenReturn("TRASH");

    when(nodeRepository.getRootsList()).thenReturn(List.of(root1, root2));

    List<RootModel> result = nodeApi.getRootsList();

    assertThat(result).hasSize(2);
    assertThat(result.get(0).getId()).isEqualTo("LOCAL_ROOT");
    assertThat(result.get(0).getName()).isEqualTo("HOME");
    assertThat(result.get(1).getId()).isEqualTo("TRASH_ROOT");
    assertThat(result.get(1).getName()).isEqualTo("TRASH");
  }

  @Test
  void getRootsListReturnsEmptyListWhenNoRoots() throws Exception {
    when(nodeRepository.getRootsList()).thenReturn(Collections.emptyList());

    List<RootModel> result = nodeApi.getRootsList();

    assertThat(result).isEmpty();
  }

  // --- getVersions ---

  @Test
  void getVersionsThrowsNodeNotFoundWhenPermissionDenied() throws Exception {
    when(permissionsChecker.getPermissions(NODE_ID, REQUESTER_ID)).thenReturn(aclWith(false));

    assertThatThrownBy(() -> nodeApi.getVersions(NODE_ID, null))
        .isInstanceOf(FilesGraphQLException.class)
        .satisfies(
            ex ->
                assertThat(((FilesGraphQLException) ex).getErrorCode())
                    .isEqualTo(ErrorCodes.NODE_NOT_FOUND));
  }

  @Test
  void getVersionsReturnsAllVersionsWhenVersionsParamIsNull() throws Exception {
    Node node = mockFileNode(NODE_ID);

    FileVersion fv1 = mock(FileVersion.class);
    when(fv1.getVersion()).thenReturn(1);
    when(fv1.getMimeType()).thenReturn("text/plain");
    when(fv1.getSize()).thenReturn(512L);
    when(fv1.getDigest()).thenReturn("abc");
    when(fv1.isKeptForever()).thenReturn(false);
    when(fv1.getClonedFromVersion()).thenReturn(Optional.empty());
    when(fv1.getLastEditorId()).thenReturn("editor-id");
    when(fv1.getUpdatedAt()).thenReturn(2000L);

    when(node.getFileVersions()).thenReturn(List.of(fv1));

    when(permissionsChecker.getPermissions(NODE_ID, REQUESTER_ID)).thenReturn(aclWith(true));
    when(fileVersionRepository.getFileVersions(
            eq(NODE_ID),
            eq(
                List.of(
                    com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.FileVersionSort
                        .VERSION_DESC))))
        .thenReturn(List.of(fv1));
    when(nodeRepository.getNode(NODE_ID)).thenReturn(Optional.of(node));

    List<FileModel> result = nodeApi.getVersions(NODE_ID, null);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).getVersion()).isEqualTo(1);
  }

  // --- getPath ---

  @Test
  void getPathThrowsNodeNotFoundWhenPermissionDenied() throws Exception {
    when(permissionsChecker.getPermissions(NODE_ID, REQUESTER_ID)).thenReturn(aclWith(false));

    assertThatThrownBy(() -> nodeApi.getPath(NODE_ID))
        .isInstanceOf(FilesGraphQLException.class)
        .satisfies(
            ex ->
                assertThat(((FilesGraphQLException) ex).getErrorCode())
                    .isEqualTo(ErrorCodes.NODE_NOT_FOUND));
  }

  @Test
  void getPathReturnsFullPathForOwner() throws Exception {
    Node root = mock(Node.class);
    when(root.getId()).thenReturn("LOCAL_ROOT");
    when(root.getName()).thenReturn("HOME");
    when(root.getNodeType()).thenReturn(NodeType.ROOT);
    when(root.getNodeCategory()).thenReturn(NodeCategory.ROOT);
    when(root.getDescription()).thenReturn(Optional.empty());
    when(root.getOwnerId()).thenReturn(REQUESTER_ID);
    when(root.getParentId()).thenReturn(Optional.empty());
    when(root.getCreatorId()).thenReturn(REQUESTER_ID);
    when(root.getLastEditorId()).thenReturn(Optional.empty());
    when(root.getCreatedAt()).thenReturn(1000L);
    when(root.getUpdatedAt()).thenReturn(1000L);
    when(root.getAncestorsList()).thenReturn(Collections.emptyList());
    when(root.getCustomAttributes()).thenReturn(Collections.emptyList());

    Node fileNode = mockFileNode(NODE_ID);
    when(fileNode.getOwnerId()).thenReturn(REQUESTER_ID);
    when(fileNode.getAncestorsList()).thenReturn(List.of("LOCAL_ROOT"));

    when(permissionsChecker.getPermissions(NODE_ID, REQUESTER_ID)).thenReturn(aclWith(true));
    when(nodeRepository.getNode(NODE_ID)).thenReturn(Optional.of(fileNode));
    when(nodeRepository.getNodes(eq(List.of("LOCAL_ROOT", NODE_ID)), any()))
        .thenReturn(List.of(root, fileNode).stream());

    List<NodeModel> path = nodeApi.getPath(NODE_ID);

    assertThat(path).hasSize(2);
    assertThat(path.get(0).getId()).isEqualTo("LOCAL_ROOT");
    assertThat(path.get(1).getId()).isEqualTo(NODE_ID);
  }

  // ─── @Source batch: parents ───────────────────────────────────────────────

  private NodeModel makeFolderModel(String id, String parentId) {
    return new FolderModel(
        id,
        parentId,
        "owner-id",
        "creator-id",
        null,
        0L,
        0L,
        "name",
        "",
        com.zextras.carbonio.files.graphql.model.NodeType.FOLDER,
        false,
        "root-id");
  }

  @Test
  void parents_positionalAlignmentAndNullParentId() {
    NodeModel n1 = makeFolderModel("node-1", "parent-A");
    NodeModel n2 = makeFolderModel("node-2", null);
    NodeModel n3 = makeFolderModel("node-3", "parent-A");

    Node parentA = mockFolderNode("parent-A");

    when(nodeRepository.getNodes(eq(List.of("parent-A")), any()))
        .thenReturn(List.of(parentA).stream());

    List<NodeModel> result = nodeApi.parents(List.of(n1, n2, n3));

    assertThat(result).hasSize(3);
    assertThat(result.get(0)).isNotNull();
    assertThat(result.get(0).getId()).isEqualTo("parent-A");
    assertThat(result.get(1)).isNull();
    assertThat(result.get(2)).isNotNull();
    assertThat(result.get(2).getId()).isEqualTo("parent-A");
  }

  @Test
  void parents_allNullParentIds_returnsAllNulls() {
    NodeModel n1 = makeFolderModel("node-1", null);
    NodeModel n2 = makeFolderModel("node-2", null);

    when(nodeRepository.getNodes(eq(List.of()), any())).thenReturn(List.<Node>of().stream());

    List<NodeModel> result = nodeApi.parents(List.of(n1, n2));

    assertThat(result).hasSize(2);
    assertThat(result.get(0)).isNull();
    assertThat(result.get(1)).isNull();
  }
}
