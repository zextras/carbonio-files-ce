// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zextras.carbonio.files.config.FilesConfig;
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
import com.zextras.carbonio.files.dal.repositories.interfaces.NotificationRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.ShareRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.TombstoneRepository;
import com.zextras.carbonio.files.graphql.errors.ErrorCodes;
import com.zextras.carbonio.files.graphql.errors.FilesGraphQLException;
import com.zextras.carbonio.files.graphql.model.FolderModel;
import com.zextras.carbonio.files.graphql.model.NodeModel;
import com.zextras.carbonio.files.graphql.support.NodeCreationHelper;
import com.zextras.carbonio.files.graphql.support.ShareCascadeHelper;
import com.zextras.carbonio.files.graphql.validation.GraphQLInputValidator;
import com.zextras.carbonio.files.utilities.PermissionsChecker;
import com.zextras.filestore.api.Filestore;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.narayana.jta.TransactionRunnerOptions;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class NodeApiMutationsTest {

  private static final String REQUESTER_ID = "requester-user-id";
  private static final String PARENT_ID = "00000000-0000-0000-0000-000000000000";
  private static final String NODE_ID_1 = "00000000-0000-0000-0000-000000000001";
  private static final String NODE_ID_2 = "00000000-0000-0000-0000-000000000002";

  private NodeRepository nodeRepository;
  private FileVersionRepository fileVersionRepository;
  private PermissionsChecker permissionsChecker;
  private ShareRepository shareRepository;
  private NotificationRepository notificationRepository;
  private TombstoneRepository tombstoneRepository;
  private Filestore fileStore;
  private ShareCascadeHelper shareCascade;
  private NodeCreationHelper nodeCreationHelper;
  private FilesConfig filesConfig;
  private NodeApi nodeApi;

  @BeforeEach
  void setUp() {
    nodeRepository = mock(NodeRepository.class);
    fileVersionRepository = mock(FileVersionRepository.class);
    permissionsChecker = mock(PermissionsChecker.class);
    shareRepository = mock(ShareRepository.class);
    notificationRepository = mock(NotificationRepository.class);
    tombstoneRepository = mock(TombstoneRepository.class);
    fileStore = mock(Filestore.class);
    shareCascade = mock(ShareCascadeHelper.class);
    filesConfig = mock(FilesConfig.class);

    UserMyself requester = mock(UserMyself.class);
    UserId userId = new UserId(REQUESTER_ID);
    when(requester.getId()).thenReturn(userId);

    nodeCreationHelper =
        new NodeCreationHelper(
            nodeRepository,
            shareRepository,
            notificationRepository,
            filesConfig,
            fileVersionRepository,
            tombstoneRepository,
            fileStore,
            permissionsChecker,
            shareCascade);

    nodeApi = new NodeApi();
    nodeApi.nodeRepository = nodeRepository;
    nodeApi.fileVersionRepository = fileVersionRepository;
    nodeApi.permissionsChecker = permissionsChecker;
    nodeApi.shareRepository = shareRepository;
    nodeApi.notificationRepository = notificationRepository;
    nodeApi.tombstoneRepository = tombstoneRepository;
    nodeApi.fileStore = fileStore;
    nodeApi.shareCascade = shareCascade;
    nodeApi.filesConfig = filesConfig;
    nodeApi.nodeCreationHelper = nodeCreationHelper;
    nodeApi.validator = new GraphQLInputValidator();
    nodeApi.requester = requester;
  }

  private Node mockFolderNode(String id, String parentId) {
    Node node = mock(Node.class);
    when(node.getId()).thenReturn(id);
    when(node.getName()).thenReturn("testfolder");
    when(node.getNodeType()).thenReturn(NodeType.FOLDER);
    when(node.getNodeCategory()).thenReturn(NodeCategory.FOLDER);
    when(node.getDescription()).thenReturn(Optional.empty());
    when(node.getParentId()).thenReturn(Optional.of(parentId));
    when(node.getOwnerId()).thenReturn(REQUESTER_ID);
    when(node.getCreatorId()).thenReturn(REQUESTER_ID);
    when(node.getLastEditorId()).thenReturn(Optional.empty());
    when(node.getCreatedAt()).thenReturn(1000L);
    when(node.getUpdatedAt()).thenReturn(2000L);
    when(node.getAncestorsList()).thenReturn(List.of("LOCAL_ROOT"));
    when(node.getAncestorIds()).thenReturn("LOCAL_ROOT");
    when(node.getCustomAttributes()).thenReturn(Collections.emptyList());
    when(node.getFileVersions()).thenReturn(Collections.emptyList());
    return node;
  }

  private Node mockRootNode(String id) {
    Node node = mock(Node.class);
    when(node.getId()).thenReturn(id);
    when(node.getName()).thenReturn("HOME");
    when(node.getNodeType()).thenReturn(NodeType.ROOT);
    when(node.getNodeCategory()).thenReturn(NodeCategory.ROOT);
    when(node.getDescription()).thenReturn(Optional.empty());
    when(node.getParentId()).thenReturn(Optional.empty());
    when(node.getOwnerId()).thenReturn(REQUESTER_ID);
    when(node.getCreatorId()).thenReturn(REQUESTER_ID);
    when(node.getLastEditorId()).thenReturn(Optional.empty());
    when(node.getCreatedAt()).thenReturn(1000L);
    when(node.getUpdatedAt()).thenReturn(2000L);
    when(node.getAncestorsList()).thenReturn(Collections.emptyList());
    when(node.getAncestorIds()).thenReturn("");
    when(node.getCustomAttributes()).thenReturn(Collections.emptyList());
    when(node.getFileVersions()).thenReturn(Collections.emptyList());
    return node;
  }

  private Node mockFileNode(String id, String parentId) {
    Node node = mock(Node.class);
    when(node.getId()).thenReturn(id);
    when(node.getName()).thenReturn("testfile");
    when(node.getNodeType()).thenReturn(NodeType.TEXT);
    when(node.getNodeCategory()).thenReturn(NodeCategory.FILE);
    when(node.getDescription()).thenReturn(Optional.empty());
    when(node.getParentId()).thenReturn(Optional.of(parentId));
    when(node.getOwnerId()).thenReturn(REQUESTER_ID);
    when(node.getCreatorId()).thenReturn(REQUESTER_ID);
    when(node.getLastEditorId()).thenReturn(Optional.of(REQUESTER_ID));
    when(node.getCreatedAt()).thenReturn(1000L);
    when(node.getUpdatedAt()).thenReturn(2000L);
    when(node.getExtension()).thenReturn(Optional.of("txt"));
    when(node.getFullName()).thenReturn("testfile.txt");
    when(node.getCurrentVersion()).thenReturn(1);
    when(node.getAncestorsList()).thenReturn(List.of("LOCAL_ROOT"));
    when(node.getAncestorIds()).thenReturn("LOCAL_ROOT");
    when(node.getCustomAttributes()).thenReturn(Collections.emptyList());

    FileVersion fv = mock(FileVersion.class);
    when(fv.getVersion()).thenReturn(1);
    when(fv.getMimeType()).thenReturn("text/plain");
    when(fv.getSize()).thenReturn(512L);
    when(fv.getDigest()).thenReturn("abc123");
    when(fv.isKeptForever()).thenReturn(false);
    when(fv.getClonedFromVersion()).thenReturn(Optional.empty());
    when(fv.getLastEditorId()).thenReturn(REQUESTER_ID);
    when(fv.getUpdatedAt()).thenReturn(2000L);
    when(node.getFileVersions()).thenReturn(List.of(fv));
    return node;
  }

  private ACL aclWith(boolean readAndWrite) {
    if (readAndWrite) return ACL.decode(SharePermission.READ_AND_WRITE);
    return ACL.decode((short) 0);
  }

  // ─── createFolder ─────────────────────────────────────────────────────────────

  @Test
  void createFolderHappyPathReturnsFolderModel() throws Exception {
    Node parent = mockFolderNode(PARENT_ID, "LOCAL_ROOT");
    when(parent.getNodeType()).thenReturn(NodeType.FOLDER);
    Node created = mockFolderNode(NODE_ID_1, PARENT_ID);
    when(created.getOwnerId()).thenReturn(REQUESTER_ID);

    when(permissionsChecker.getPermissions(PARENT_ID, REQUESTER_ID)).thenReturn(aclWith(true));
    when(nodeRepository.getNode(PARENT_ID)).thenReturn(Optional.of(parent));
    when(nodeRepository.findNodes(
            any(),
            any(),
            any(),
            eq(Optional.of(PARENT_ID)),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any()))
        .thenReturn(org.apache.commons.lang3.tuple.ImmutablePair.of(Collections.emptyList(), null));
    when(nodeRepository.createNewNode(
            any(),
            eq(REQUESTER_ID),
            eq(REQUESTER_ID),
            eq(PARENT_ID),
            anyString(),
            eq(""),
            eq(NodeType.FOLDER),
            any(),
            eq(0L)))
        .thenReturn(created);
    when(shareRepository.getShares(eq(PARENT_ID), eq(Collections.emptyList())))
        .thenReturn(Collections.emptyList());
    when(filesConfig.areNotificationsEnabled()).thenReturn(false);

    NodeModel result = nodeApi.createFolder(PARENT_ID, "TestFolder");

    assertThat(result).isInstanceOf(FolderModel.class);
    assertThat(result.getId()).isEqualTo(NODE_ID_1);
    verify(nodeRepository)
        .createNewNode(
            any(),
            eq(REQUESTER_ID),
            eq(REQUESTER_ID),
            eq(PARENT_ID),
            anyString(),
            eq(""),
            eq(NodeType.FOLDER),
            any(),
            eq(0L));
  }

  @Test
  void createFolderThrowsNodeWriteErrorWhenPermissionDenied() {
    when(permissionsChecker.getPermissions(PARENT_ID, REQUESTER_ID)).thenReturn(aclWith(false));

    assertThatThrownBy(() -> nodeApi.createFolder(PARENT_ID, "Folder"))
        .isInstanceOf(FilesGraphQLException.class)
        .satisfies(
            ex ->
                assertThat(((FilesGraphQLException) ex).getErrorCode())
                    .isEqualTo(ErrorCodes.NODE_WRITE_ERROR));

    verify(nodeRepository, never())
        .createNewNode(any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void createFolderThrowsNodeNotFoundWhenParentDoesNotExist() {
    when(permissionsChecker.getPermissions(PARENT_ID, REQUESTER_ID)).thenReturn(aclWith(true));
    when(nodeRepository.getNode(PARENT_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> nodeApi.createFolder(PARENT_ID, "Folder"))
        .isInstanceOf(FilesGraphQLException.class)
        .satisfies(
            ex ->
                assertThat(((FilesGraphQLException) ex).getErrorCode())
                    .isEqualTo(ErrorCodes.NODE_NOT_FOUND));
  }

  // ─── updateNode ───────────────────────────────────────────────────────────────

  @Test
  void updateNodeHappyPathReturnsMappedNode() throws Exception {
    Node node = mockFolderNode(NODE_ID_1, PARENT_ID);
    when(node.getExtension()).thenReturn(Optional.empty());

    when(permissionsChecker.getPermissions(NODE_ID_1, REQUESTER_ID)).thenReturn(aclWith(true));
    when(nodeRepository.getNode(NODE_ID_1)).thenReturn(Optional.of(node));
    when(nodeRepository.findNodes(
            any(),
            any(),
            any(),
            eq(Optional.of(PARENT_ID)),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any()))
        .thenReturn(org.apache.commons.lang3.tuple.ImmutablePair.of(Collections.emptyList(), null));

    NodeModel result = nodeApi.updateNode(NODE_ID_1, null, null, null);

    assertThat(result).isInstanceOf(FolderModel.class);
    assertThat(result.getId()).isEqualTo(NODE_ID_1);
    verify(nodeRepository, times(2)).getNode(NODE_ID_1);
  }

  @Test
  void updateNodeThrowsNodeDuplicatedWhenNameConflicts() {
    Node node = mockFolderNode(NODE_ID_1, PARENT_ID);
    when(node.getExtension()).thenReturn(Optional.empty());
    Node conflict = mockFolderNode("conflict-id", PARENT_ID);

    when(permissionsChecker.getPermissions(NODE_ID_1, REQUESTER_ID)).thenReturn(aclWith(true));
    when(nodeRepository.getNode(NODE_ID_1)).thenReturn(Optional.of(node));
    when(nodeRepository.getNodeByName("NewName", PARENT_ID, REQUESTER_ID))
        .thenReturn(Optional.of(conflict));

    assertThatThrownBy(() -> nodeApi.updateNode(NODE_ID_1, "NewName", null, null))
        .isInstanceOf(FilesGraphQLException.class)
        .satisfies(
            ex ->
                assertThat(((FilesGraphQLException) ex).getErrorCode())
                    .isEqualTo(ErrorCodes.NODE_DUPLICATED));
  }

  @Test
  void updateNodeThrowsNodeNotFoundWhenPermissionDenied() {
    when(permissionsChecker.getPermissions(NODE_ID_1, REQUESTER_ID)).thenReturn(aclWith(false));

    assertThatThrownBy(() -> nodeApi.updateNode(NODE_ID_1, "Name", null, null))
        .isInstanceOf(FilesGraphQLException.class)
        .satisfies(
            ex ->
                assertThat(((FilesGraphQLException) ex).getErrorCode())
                    .isEqualTo(ErrorCodes.NODE_NOT_FOUND));
  }

  // ─── flagNodes ────────────────────────────────────────────────────────────────

  @Test
  void flagNodesReturnsEmptyListWhenNodeIdsIsNull() throws Exception {
    List<String> result = nodeApi.flagNodes(null, true);

    assertThat(result).isEmpty();
    verify(nodeRepository, never()).getNode(any());
  }

  @Test
  void flagNodesPartialSuccessThrowsFilesGraphQLExceptionWithPartialResults() {
    Node node1 = mockFolderNode(NODE_ID_1, PARENT_ID);
    Node node2 = mockFolderNode(NODE_ID_2, PARENT_ID);

    when(nodeRepository.getNode(NODE_ID_1)).thenReturn(Optional.of(node1));
    when(nodeRepository.getNode(NODE_ID_2)).thenReturn(Optional.of(node2));
    when(permissionsChecker.getPermissions(NODE_ID_1, REQUESTER_ID)).thenReturn(aclWith(true));
    when(permissionsChecker.getPermissions(NODE_ID_2, REQUESTER_ID)).thenReturn(aclWith(false));

    try (MockedStatic<QuarkusTransaction> txMock = Mockito.mockStatic(QuarkusTransaction.class)) {
      TransactionRunnerOptions runOptions = mock(TransactionRunnerOptions.class);
      doAnswer(
              inv -> {
                ((Runnable) inv.getArgument(0)).run();
                return null;
              })
          .when(runOptions)
          .run(any(Runnable.class));
      txMock.when(QuarkusTransaction::requiringNew).thenReturn(runOptions);

      assertThatThrownBy(() -> nodeApi.flagNodes(List.of(NODE_ID_1, NODE_ID_2), true))
          .isInstanceOf(FilesGraphQLException.class)
          .satisfies(
              ex -> {
                FilesGraphQLException fex = (FilesGraphQLException) ex;
                assertThat(fex.getErrorCode()).isEqualTo(ErrorCodes.NODE_WRITE_ERROR);
                assertThat(fex.getPartialResults()).isInstanceOf(List.class);
                @SuppressWarnings("unchecked")
                List<String> partial = (List<String>) fex.getPartialResults();
                assertThat(partial).containsExactly(NODE_ID_1);
              });
    }

    verify(nodeRepository).flagForUser(NODE_ID_1, REQUESTER_ID, true);
    verify(nodeRepository, never()).flagForUser(eq(NODE_ID_2), any(), anyBoolean());
  }

  @Test
  void flagNodesHappyPathReturnsAllFlaggedIds() throws Exception {
    Node node1 = mockFolderNode(NODE_ID_1, PARENT_ID);
    Node node2 = mockFolderNode(NODE_ID_2, PARENT_ID);

    when(nodeRepository.getNode(NODE_ID_1)).thenReturn(Optional.of(node1));
    when(nodeRepository.getNode(NODE_ID_2)).thenReturn(Optional.of(node2));
    when(permissionsChecker.getPermissions(NODE_ID_1, REQUESTER_ID)).thenReturn(aclWith(true));
    when(permissionsChecker.getPermissions(NODE_ID_2, REQUESTER_ID)).thenReturn(aclWith(true));

    try (MockedStatic<QuarkusTransaction> txMock = Mockito.mockStatic(QuarkusTransaction.class)) {
      TransactionRunnerOptions runOptions = mock(TransactionRunnerOptions.class);
      doAnswer(
              inv -> {
                ((Runnable) inv.getArgument(0)).run();
                return null;
              })
          .when(runOptions)
          .run(any(Runnable.class));
      txMock.when(QuarkusTransaction::requiringNew).thenReturn(runOptions);

      List<String> result = nodeApi.flagNodes(List.of(NODE_ID_1, NODE_ID_2), true);

      assertThat(result).containsExactlyInAnyOrder(NODE_ID_1, NODE_ID_2);
    }

    verify(nodeRepository).flagForUser(NODE_ID_1, REQUESTER_ID, true);
    verify(nodeRepository).flagForUser(NODE_ID_2, REQUESTER_ID, true);
  }

  // ─── deleteNodes ──────────────────────────────────────────────────────────────

  @Test
  void deleteNodesReturnsEmptyListWhenNodeIdsIsNull() throws Exception {
    List<String> result = nodeApi.deleteNodes(null);

    assertThat(result).isEmpty();
    verify(nodeRepository, never()).getNode(any());
  }

  @Test
  void deleteNodesThrowsNodeWriteErrorWhenPermissionDeniedForAll() {
    when(nodeRepository.getNodes(eq(List.of(NODE_ID_1)), any())).thenReturn(Stream.of());

    try (MockedStatic<QuarkusTransaction> txMock = Mockito.mockStatic(QuarkusTransaction.class)) {
      TransactionRunnerOptions runOptions = mock(TransactionRunnerOptions.class);
      doAnswer(
              inv -> {
                ((Runnable) inv.getArgument(0)).run();
                return null;
              })
          .when(runOptions)
          .run(any(Runnable.class));
      txMock.when(QuarkusTransaction::requiringNew).thenReturn(runOptions);

      assertThatThrownBy(() -> nodeApi.deleteNodes(List.of(NODE_ID_1)))
          .isInstanceOf(FilesGraphQLException.class)
          .satisfies(
              ex -> {
                FilesGraphQLException fex = (FilesGraphQLException) ex;
                assertThat(fex.getErrorCode()).isEqualTo(ErrorCodes.NODE_NOT_FOUND);
              });
    }
  }
}
