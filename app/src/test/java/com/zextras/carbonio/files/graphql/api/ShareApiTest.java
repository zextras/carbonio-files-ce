// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zextras.carbonio.files.config.FilesConfig;
import com.zextras.carbonio.files.dal.dao.UserId;
import com.zextras.carbonio.files.dal.dao.UserMyself;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.dao.ebean.Share;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NotificationRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.ShareRepository;
import com.zextras.carbonio.files.graphql.errors.ErrorCodes;
import com.zextras.carbonio.files.graphql.errors.FilesGraphQLException;
import com.zextras.carbonio.files.graphql.model.FolderModel;
import com.zextras.carbonio.files.graphql.model.NodeModel;
import com.zextras.carbonio.files.graphql.model.ShareModel;
import com.zextras.carbonio.files.graphql.model.SharePermission;
import com.zextras.carbonio.files.graphql.support.ShareCascadeHelper;
import com.zextras.carbonio.files.graphql.validation.GraphQLInputValidator;
import com.zextras.carbonio.files.utilities.PermissionsChecker;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.narayana.jta.TransactionRunnerOptions;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class ShareApiTest {

  private static final String REQUESTER_ID = "requester-user-id";
  private static final String NODE_ID = "00000000-0000-0000-0000-000000000001";
  private static final String TARGET_ID_1 = "target-user-1";
  private static final String TARGET_ID_2 = "target-user-2";

  private NodeRepository nodeRepository;
  private ShareRepository shareRepository;
  private PermissionsChecker permissionsChecker;
  private ShareCascadeHelper shareCascade;
  private NotificationRepository notificationRepository;
  private FilesConfig filesConfig;
  private ShareApi shareApi;

  @BeforeEach
  void setUp() {
    nodeRepository = mock(NodeRepository.class);
    shareRepository = mock(ShareRepository.class);
    permissionsChecker = mock(PermissionsChecker.class);
    shareCascade = mock(ShareCascadeHelper.class);
    notificationRepository = mock(NotificationRepository.class);
    filesConfig = mock(FilesConfig.class);

    UserMyself requester = mock(UserMyself.class);
    UserId userId = new UserId(REQUESTER_ID);
    when(requester.getId()).thenReturn(userId);

    shareApi = new ShareApi();
    shareApi.nodeRepository = nodeRepository;
    shareApi.shareRepository = shareRepository;
    shareApi.permissionsChecker = permissionsChecker;
    shareApi.shareCascade = shareCascade;
    shareApi.notificationRepository = notificationRepository;
    shareApi.filesConfig = filesConfig;
    shareApi.validator = new GraphQLInputValidator();
    shareApi.requester = requester;
  }

  private ACL aclWith(ACL.SharePermission perm) {
    return ACL.decode(perm);
  }

  private Share mockShare(String nodeId, String targetId) {
    Share share = mock(Share.class);
    when(share.getNodeId()).thenReturn(nodeId);
    when(share.getTargetUserId()).thenReturn(targetId);
    when(share.getCreatedAt()).thenReturn(1000L);
    when(share.getPermissions()).thenReturn(aclWith(ACL.SharePermission.READ_ONLY));
    when(share.getExpiredAt()).thenReturn(Optional.empty());
    when(share.isDirect()).thenReturn(true);
    return share;
  }

  private Node mockFileNode(String id, String ownerId) {
    Node node = mock(Node.class);
    when(node.getId()).thenReturn(id);
    when(node.getOwnerId()).thenReturn(ownerId);
    when(node.getNodeType()).thenReturn(NodeType.TEXT);
    return node;
  }

  private Node mockFolderNode(String id, String ownerId) {
    Node node = mock(Node.class);
    when(node.getId()).thenReturn(id);
    when(node.getOwnerId()).thenReturn(ownerId);
    when(node.getNodeType()).thenReturn(NodeType.FOLDER);
    return node;
  }

  // ─── getShare ──────────────────────────────────────────────────────────────────

  @Test
  void getShareHappyPathReturnsShareModel() throws FilesGraphQLException {
    Share share = mockShare(NODE_ID, TARGET_ID_1);
    when(permissionsChecker.getPermissions(NODE_ID, REQUESTER_ID))
        .thenReturn(aclWith(ACL.SharePermission.READ_ONLY));
    when(shareRepository.getShare(NODE_ID, TARGET_ID_1)).thenReturn(Optional.of(share));

    ShareModel result = shareApi.getShare(NODE_ID, TARGET_ID_1);

    assertThat(result).isNotNull();
    assertThat(result.getCreatedAt()).isEqualTo(1000L);
    assertThat(result.getPermission()).isEqualTo(SharePermission.READ_ONLY);
    assertThat(result.getNodeId()).isEqualTo(NODE_ID);
    assertThat(result.getShareTargetId()).isEqualTo(TARGET_ID_1);
  }

  @Test
  void getShareThrowsShareNotFoundWhenPermissionDenied() {
    when(permissionsChecker.getPermissions(NODE_ID, REQUESTER_ID))
        .thenReturn(aclWith(ACL.SharePermission.NONE));

    assertThatThrownBy(() -> shareApi.getShare(NODE_ID, TARGET_ID_1))
        .isInstanceOf(FilesGraphQLException.class)
        .satisfies(
            ex ->
                assertThat(((FilesGraphQLException) ex).getErrorCode())
                    .isEqualTo(ErrorCodes.SHARE_NOT_FOUND));
  }

  @Test
  void getShareThrowsShareNotFoundWhenShareMissing() {
    when(permissionsChecker.getPermissions(NODE_ID, REQUESTER_ID))
        .thenReturn(aclWith(ACL.SharePermission.READ_ONLY));
    when(shareRepository.getShare(NODE_ID, TARGET_ID_1)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> shareApi.getShare(NODE_ID, TARGET_ID_1))
        .isInstanceOf(FilesGraphQLException.class)
        .satisfies(
            ex ->
                assertThat(((FilesGraphQLException) ex).getErrorCode())
                    .isEqualTo(ErrorCodes.SHARE_NOT_FOUND));
  }

  // ─── createShare ──────────────────────────────────────────────────────────────

  @Test
  @SuppressWarnings("unchecked")
  void createShareHappyPathReturnsShareModel() throws Exception {
    Node node = mockFileNode(NODE_ID, "owner-id");
    Share share = mockShare(NODE_ID, TARGET_ID_1);

    when(permissionsChecker.getPermissions(NODE_ID, REQUESTER_ID))
        .thenReturn(aclWith(ACL.SharePermission.READ_AND_SHARE));
    when(nodeRepository.getNode(NODE_ID)).thenReturn(Optional.of(node));
    when(filesConfig.areNotificationsEnabled()).thenReturn(false);

    try (MockedStatic<QuarkusTransaction> txMock = Mockito.mockStatic(QuarkusTransaction.class)) {
      TransactionRunnerOptions runOptions = mock(TransactionRunnerOptions.class);
      doAnswer(
              inv -> {
                Callable<?> callable = inv.getArgument(0);
                callable.call();
                return Optional.of(share);
              })
          .when(runOptions)
          .call(any(Callable.class));
      txMock.when(QuarkusTransaction::requiringNew).thenReturn(runOptions);

      when(shareRepository.upsertShare(
              eq(NODE_ID), eq(TARGET_ID_1), any(), eq(true), eq(false), any()))
          .thenReturn(Optional.of(share));

      ShareModel result =
          shareApi.createShare(NODE_ID, TARGET_ID_1, SharePermission.READ_ONLY, null, null);

      assertThat(result).isNotNull();
      assertThat(result.getPermission()).isEqualTo(SharePermission.READ_ONLY);
    }
  }

  @Test
  void createShareThrowsShareCreationErrorWhenPermissionDenied() {
    when(permissionsChecker.getPermissions(NODE_ID, REQUESTER_ID))
        .thenReturn(aclWith(ACL.SharePermission.READ_ONLY));

    assertThatThrownBy(
            () -> shareApi.createShare(NODE_ID, TARGET_ID_1, SharePermission.READ_ONLY, null, null))
        .isInstanceOf(FilesGraphQLException.class)
        .satisfies(
            ex ->
                assertThat(((FilesGraphQLException) ex).getErrorCode())
                    .isEqualTo(ErrorCodes.SHARE_CREATION_ERROR));
  }

  @Test
  void createShareThrowsShareCreationErrorWhenTargetIsOwner() {
    Node node = mockFileNode(NODE_ID, TARGET_ID_1);
    when(permissionsChecker.getPermissions(NODE_ID, REQUESTER_ID))
        .thenReturn(aclWith(ACL.SharePermission.READ_AND_SHARE));
    when(nodeRepository.getNode(NODE_ID)).thenReturn(Optional.of(node));

    assertThatThrownBy(
            () -> shareApi.createShare(NODE_ID, TARGET_ID_1, SharePermission.READ_ONLY, null, null))
        .isInstanceOf(FilesGraphQLException.class)
        .satisfies(
            ex ->
                assertThat(((FilesGraphQLException) ex).getErrorCode())
                    .isEqualTo(ErrorCodes.SHARE_CREATION_ERROR));
  }

  // ─── updateShares ─────────────────────────────────────────────────────────────

  @Test
  void updateSharesHappyPathReturnsUpdatedModels() throws Exception {
    Share share1 = mockShare(NODE_ID, TARGET_ID_1);
    Share share2 = mockShare(NODE_ID, TARGET_ID_2);

    when(permissionsChecker.getPermissions(NODE_ID, REQUESTER_ID))
        .thenReturn(aclWith(ACL.SharePermission.READ_AND_SHARE));
    when(shareRepository.getShare(NODE_ID, TARGET_ID_1)).thenReturn(Optional.of(share1));
    when(shareRepository.getShare(NODE_ID, TARGET_ID_2)).thenReturn(Optional.of(share2));
    when(shareRepository.updateShare(share1)).thenReturn(share1);
    when(shareRepository.updateShare(share2)).thenReturn(share2);

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

      List<ShareModel> result =
          shareApi.updateShares(NODE_ID, List.of(TARGET_ID_1, TARGET_ID_2), null, null);

      assertThat(result).hasSize(2);
    }
  }

  @Test
  void updateSharesPartialSuccessThrowsShareNotFoundWithPartialResults() {
    Share share1 = mockShare(NODE_ID, TARGET_ID_1);

    when(permissionsChecker.getPermissions(NODE_ID, REQUESTER_ID))
        .thenReturn(aclWith(ACL.SharePermission.READ_AND_SHARE));
    when(shareRepository.getShare(NODE_ID, TARGET_ID_1)).thenReturn(Optional.of(share1));
    when(shareRepository.getShare(NODE_ID, TARGET_ID_2)).thenReturn(Optional.empty());
    when(shareRepository.updateShare(share1)).thenReturn(share1);

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

      assertThatThrownBy(
              () -> shareApi.updateShares(NODE_ID, List.of(TARGET_ID_1, TARGET_ID_2), null, null))
          .isInstanceOf(FilesGraphQLException.class)
          .satisfies(
              ex -> {
                FilesGraphQLException fex = (FilesGraphQLException) ex;
                assertThat(fex.getErrorCode()).isEqualTo(ErrorCodes.SHARE_NOT_FOUND);
                assertThat(fex.getPartialResults()).isInstanceOf(List.class);
                @SuppressWarnings("unchecked")
                List<ShareModel> partial = (List<ShareModel>) fex.getPartialResults();
                assertThat(partial).hasSize(1);
              });
    }
  }

  // ─── deleteShares ─────────────────────────────────────────────────────────────

  @Test
  void deleteSharesHappyPathReturnsDeletedIds() throws Exception {
    Share share1 = mockShare(NODE_ID, TARGET_ID_1);
    Node node = mockFileNode(NODE_ID, "owner-id");

    when(permissionsChecker.getPermissions(NODE_ID, REQUESTER_ID))
        .thenReturn(aclWith(ACL.SharePermission.READ_AND_SHARE));
    when(shareRepository.getShare(NODE_ID, TARGET_ID_1)).thenReturn(Optional.of(share1));
    when(nodeRepository.getNode(NODE_ID)).thenReturn(Optional.of(node));

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

      List<String> result = shareApi.deleteShares(NODE_ID, List.of(TARGET_ID_1));

      assertThat(result).containsExactly(TARGET_ID_1);
      verify(shareRepository).deleteShare(NODE_ID, TARGET_ID_1);
      verify(shareCascade, never()).cascadeDeleteShare(any(), any());
    }
  }

  @Test
  void deleteSharesThrowsShareNotFoundWithPartialResultsOnMixedOutcomes() {
    Share share1 = mockShare(NODE_ID, TARGET_ID_1);
    Node node = mockFileNode(NODE_ID, "owner-id");

    when(permissionsChecker.getPermissions(NODE_ID, REQUESTER_ID))
        .thenReturn(aclWith(ACL.SharePermission.READ_AND_SHARE));
    when(shareRepository.getShare(NODE_ID, TARGET_ID_1)).thenReturn(Optional.of(share1));
    when(shareRepository.getShare(NODE_ID, TARGET_ID_2)).thenReturn(Optional.empty());
    when(nodeRepository.getNode(NODE_ID)).thenReturn(Optional.of(node));

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

      assertThatThrownBy(() -> shareApi.deleteShares(NODE_ID, List.of(TARGET_ID_1, TARGET_ID_2)))
          .isInstanceOf(FilesGraphQLException.class)
          .satisfies(
              ex -> {
                FilesGraphQLException fex = (FilesGraphQLException) ex;
                assertThat(fex.getErrorCode()).isEqualTo(ErrorCodes.SHARE_NOT_FOUND);
                assertThat(fex.getPartialResults()).isInstanceOf(List.class);
                @SuppressWarnings("unchecked")
                List<String> partial = (List<String>) fex.getPartialResults();
                assertThat(partial).containsExactly(TARGET_ID_1);
              });
    }
  }

  @Test
  void deleteSharesThrowsShareNotFoundWhenPermissionDenied() {
    when(permissionsChecker.getPermissions(NODE_ID, REQUESTER_ID))
        .thenReturn(aclWith(ACL.SharePermission.READ_ONLY));

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

      assertThatThrownBy(() -> shareApi.deleteShares(NODE_ID, List.of(TARGET_ID_1)))
          .isInstanceOf(FilesGraphQLException.class)
          .satisfies(
              ex ->
                  assertThat(((FilesGraphQLException) ex).getErrorCode())
                      .isEqualTo(ErrorCodes.SHARE_NOT_FOUND));
    }
  }

  // ─── @Source: Node.shares ─────────────────────────────────────────────────────

  private NodeModel makeNodeModel(String id) {
    return new FolderModel(
        id,
        null,
        "owner",
        "creator",
        null,
        0L,
        0L,
        "folder",
        "",
        com.zextras.carbonio.files.graphql.model.NodeType.FOLDER,
        false,
        "root");
  }

  @Test
  void shares_returnsLimitedSharesForNode() {
    NodeModel node = makeNodeModel(NODE_ID);
    Share s1 = mockShare(NODE_ID, TARGET_ID_1);
    Share s2 = mockShare(NODE_ID, TARGET_ID_2);
    when(shareRepository.getShares(NODE_ID, List.of())).thenReturn(List.of(s1, s2));

    List<ShareModel> result = shareApi.shares(node, 1, null, null);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).getShareTargetId()).isEqualTo(TARGET_ID_1);
  }

  @Test
  void shares_cursorSkipsPastCursorTarget() {
    NodeModel node = makeNodeModel(NODE_ID);
    Share s1 = mockShare(NODE_ID, TARGET_ID_1);
    Share s2 = mockShare(NODE_ID, TARGET_ID_2);
    when(shareRepository.getShares(NODE_ID, List.of())).thenReturn(List.of(s1, s2));

    List<ShareModel> result = shareApi.shares(node, 10, TARGET_ID_1, null);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).getShareTargetId()).isEqualTo(TARGET_ID_2);
  }

  @Test
  void shares_unknownCursorReturnsFromBeginning() {
    NodeModel node = makeNodeModel(NODE_ID);
    Share s1 = mockShare(NODE_ID, TARGET_ID_1);
    when(shareRepository.getShares(NODE_ID, List.of())).thenReturn(List.of(s1));

    List<ShareModel> result = shareApi.shares(node, 10, "unknown-cursor", null);

    assertThat(result).hasSize(1);
  }

  // ─── @Source: Node.share ──────────────────────────────────────────────────────

  @Test
  void share_happyPathReturnsShareModel() {
    NodeModel node = makeNodeModel(NODE_ID);
    Share share = mockShare(NODE_ID, TARGET_ID_1);

    when(permissionsChecker.getPermissions(NODE_ID, REQUESTER_ID))
        .thenReturn(aclWith(ACL.SharePermission.READ_ONLY));
    when(shareRepository.getShare(NODE_ID, TARGET_ID_1)).thenReturn(Optional.of(share));

    ShareModel result = shareApi.share(node, TARGET_ID_1);

    assertThat(result).isNotNull();
    assertThat(result.getShareTargetId()).isEqualTo(TARGET_ID_1);
  }

  @Test
  void share_notFoundReturnsNull() {
    NodeModel node = makeNodeModel(NODE_ID);

    when(permissionsChecker.getPermissions(NODE_ID, REQUESTER_ID))
        .thenReturn(aclWith(ACL.SharePermission.READ_ONLY));
    when(shareRepository.getShare(NODE_ID, TARGET_ID_1)).thenReturn(Optional.empty());

    ShareModel result = shareApi.share(node, TARGET_ID_1);

    assertThat(result).isNull();
  }

  @Test
  void share_permissionDeniedReturnsNull() {
    NodeModel node = makeNodeModel(NODE_ID);

    when(permissionsChecker.getPermissions(NODE_ID, REQUESTER_ID))
        .thenReturn(aclWith(ACL.SharePermission.NONE));

    ShareModel result = shareApi.share(node, TARGET_ID_1);

    assertThat(result).isNull();
  }
}
