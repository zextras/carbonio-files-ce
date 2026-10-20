// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.FileVersionSort;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NotificationRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.ShareRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.TombstoneRepository;
import com.zextras.carbonio.files.graphql.errors.ErrorCodes;
import com.zextras.carbonio.files.graphql.errors.FilesGraphQLException;
import com.zextras.carbonio.files.graphql.spi.CopyFailureClassifier;
import com.zextras.carbonio.files.graphql.support.NodeCreationHelper;
import com.zextras.carbonio.files.graphql.support.ShareCascadeHelper;
import com.zextras.carbonio.files.graphql.validation.GraphQLInputValidator;
import com.zextras.carbonio.files.utilities.PermissionsChecker;
import com.zextras.filestore.api.Filestore;
import com.zextras.filestore.api.UploadResponse;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.narayana.jta.TransactionRunnerOptions;
import io.smallrye.graphql.api.Context;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class NodeApiMutationsTest {

  private static final String REQUESTER_ID = "requester-user-id";
  private static final String CLONING_USER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
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
  private CopyFailureClassifier copyFailureClassifier;
  private Context gqlContext;
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
    copyFailureClassifier = mock(CopyFailureClassifier.class);
    gqlContext = mock(Context.class);

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
    nodeApi.copyFailureClassifier = copyFailureClassifier;
    nodeApi.gqlContext = gqlContext;
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

  private static MockedStatic<QuarkusTransaction> inlineTransactions() {
    TransactionRunnerOptions runOptions = mock(TransactionRunnerOptions.class);
    doAnswer(
            inv -> {
              ((Runnable) inv.getArgument(0)).run();
              return null;
            })
        .when(runOptions)
        .run(any(Runnable.class));
    MockedStatic<QuarkusTransaction> txMock = Mockito.mockStatic(QuarkusTransaction.class);
    txMock.when(QuarkusTransaction::requiringNew).thenReturn(runOptions);
    return txMock;
  }

  // ─── cloneVersion copy failures ───────────────────────────────────────────────

  private void givenACloneVersionWhoseStorageCopyFails() throws Exception {
    Node node = mockFileNode(NODE_ID_1, PARENT_ID);
    when(nodeApi.requester.getId()).thenReturn(new UserId(CLONING_USER_ID));
    when(permissionsChecker.getPermissions(NODE_ID_1, CLONING_USER_ID)).thenReturn(aclWith(true));
    when(nodeRepository.getNode(NODE_ID_1)).thenReturn(Optional.of(node));
    when(filesConfig.getMaxNumberOfVersions()).thenReturn(10);
    when(fileVersionRepository.getFileVersions(NODE_ID_1, List.of(FileVersionSort.VERSION_DESC)))
        .thenReturn(List.of());
    when(fileVersionRepository.getFileVersion(NODE_ID_1, 1))
        .thenReturn(Optional.of(mock(FileVersion.class)));
    when(fileStore.copy(any(), any(), anyBoolean())).thenThrow(new RuntimeException("boom"));
  }

  @Test
  void cloneVersionCopyFailureClassifiedAsQuotaKeepsTheClassifiedCode() throws Exception {
    givenACloneVersionWhoseStorageCopyFails();
    when(copyFailureClassifier.classify(any())).thenReturn(ErrorCodes.OVER_QUOTA_REACHED);

    assertThatThrownBy(() -> nodeApi.cloneVersion(NODE_ID_1, 1))
        .isInstanceOfSatisfying(
            FilesGraphQLException.class,
            ex -> {
              assertThat(ex.getErrorCode()).isEqualTo(ErrorCodes.OVER_QUOTA_REACHED);
              assertThat(ex.getMessage())
                  .isEqualTo("User quota has been reached for user " + NODE_ID_1);
              assertThat(ex.getExtensions()).isEmpty();
            });
  }

  @Test
  void copyFailureErrorPrefersNodeWriteErrorOverAnOverQuotaFailure() {
    Node unreadable = mockFileNode(NODE_ID_1, PARENT_ID);
    Node quotaFailed = mockFileNode(NODE_ID_2, PARENT_ID);
    Throwable cause = new RuntimeException("quota");
    when(copyFailureClassifier.classify(cause)).thenReturn(ErrorCodes.OVER_QUOTA_REACHED);

    FilesGraphQLException error =
        nodeApi.copyFailureError(
            List.of(unreadable), List.of(new NodeApi.CopyFailure(quotaFailed, cause)));

    assertThat(error.getErrorCode()).isEqualTo(ErrorCodes.NODE_WRITE_ERROR);
    assertThat(error.getMessage())
        .isEqualTo("There was a problem while executing requested operation on node: " + NODE_ID_1);
  }

  @Test
  void cloneVersionFailureAfterTheBlobCopyIsNotReportedAsACopyError() throws Exception {
    givenACloneVersionWhoseStorageCopyFails();
    doReturn(mock(UploadResponse.class)).when(fileStore).copy(any(), any(), anyBoolean());
    when(fileVersionRepository.createNewFileVersion(
            any(), any(), anyInt(), any(), anyLong(), any(), anyBoolean()))
        .thenThrow(new IllegalStateException("db down"));

    try (MockedStatic<QuarkusTransaction> ignored = inlineTransactions()) {
      assertThatThrownBy(() -> nodeApi.cloneVersion(NODE_ID_1, 1))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("db down");
    }
    verify(copyFailureClassifier, never()).classify(any());
  }

  @Test
  void copyFailureErrorReportsTheFirstFailureInProcessingOrderWhateverItsClassification() {
    Node first = mockFileNode(NODE_ID_1, PARENT_ID);
    Node second = mockFileNode(NODE_ID_2, PARENT_ID);
    Throwable generic = new RuntimeException("generic");
    Throwable quota = new RuntimeException("quota");
    when(copyFailureClassifier.classify(generic)).thenReturn(ErrorCodes.NODE_COPY_ERROR);
    when(copyFailureClassifier.classify(quota)).thenReturn(ErrorCodes.OVER_QUOTA_REACHED);

    FilesGraphQLException error =
        nodeApi.copyFailureError(
            List.of(),
            List.of(
                new NodeApi.CopyFailure(first, generic), new NodeApi.CopyFailure(second, quota)));

    assertThat(error.getErrorCode()).isEqualTo(ErrorCodes.NODE_COPY_ERROR);
    assertThat(error.getExtensions()).containsKey("nodeId");
  }
}
