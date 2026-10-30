// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.zextras.carbonio.files.config.SharingPolicy;
import com.zextras.carbonio.files.config.SharingType;
import com.zextras.carbonio.files.dal.dao.UserId;
import com.zextras.carbonio.files.dal.dao.UserMyself;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.dal.dao.ebean.Share;
import com.zextras.carbonio.files.dal.repositories.interfaces.ShareRepository;
import com.zextras.carbonio.files.graphql.errors.ErrorCodes;
import com.zextras.carbonio.files.graphql.errors.FilesGraphQLException;
import com.zextras.carbonio.files.graphql.model.FolderModel;
import com.zextras.carbonio.files.graphql.model.NodeModel;
import com.zextras.carbonio.files.graphql.model.ShareModel;
import com.zextras.carbonio.files.graphql.model.SharePermission;
import com.zextras.carbonio.files.graphql.validation.GraphQLInputValidator;
import com.zextras.carbonio.files.utilities.PermissionsChecker;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ShareApiTest {

  private static final String NODE_ID = "00000000-0000-0000-0000-000000000001";
  private static final String TARGET_ID_1 = "target-user-1";
  private static final String TARGET_ID_2 = "target-user-2";
  private static final String REQUESTER_ID = "requester-user-id";

  private ShareRepository shareRepository;
  private PermissionsChecker permissionsChecker;
  private SharingPolicy sharingPolicy;
  private ShareApi shareApi;

  @BeforeEach
  void setUp() {
    shareRepository = mock(ShareRepository.class);
    permissionsChecker = mock(PermissionsChecker.class);
    sharingPolicy = mock(SharingPolicy.class);
    when(sharingPolicy.isEnabled(any(), any())).thenReturn(true);
    when(sharingPolicy.allowsGrant(any(), any())).thenReturn(true);

    UserMyself requester = mock(UserMyself.class);
    when(requester.getId()).thenReturn(new UserId(REQUESTER_ID));

    shareApi = new ShareApi();
    shareApi.shareRepository = shareRepository;
    shareApi.permissionsChecker = permissionsChecker;
    shareApi.sharingPolicy = sharingPolicy;
    shareApi.requester = requester;
    shareApi.validator = new GraphQLInputValidator();
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

  // ─── @Source: Node.shares (batch) ────────────────────────────────────────────

  private static final String NODE_ID_2 = "00000000-0000-0000-0000-000000000002";
  private static final String NODE_ID_3 = "00000000-0000-0000-0000-000000000003";
  private static final String TARGET_ID_3 = "target-user-3";

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
  void shares_cursorSkipsPastCursorTarget() throws Exception {
    NodeModel node = makeNodeModel(NODE_ID);
    Share s1 = mockShare(NODE_ID, TARGET_ID_1);
    Share s2 = mockShare(NODE_ID, TARGET_ID_2);
    when(shareRepository.getShares(List.of(NODE_ID))).thenReturn(List.of(s1, s2));

    List<List<ShareModel>> result = shareApi.shares(List.of(node), 10, TARGET_ID_1, null);

    assertThat(result).hasSize(1);
    assertThat(result.get(0)).hasSize(1);
    assertThat(result.get(0).get(0).getShareTargetId()).isEqualTo(TARGET_ID_2);
  }

  @Test
  void shares_unknownCursorReturnsFromBeginning() throws Exception {
    NodeModel node = makeNodeModel(NODE_ID);
    Share s1 = mockShare(NODE_ID, TARGET_ID_1);
    when(shareRepository.getShares(List.of(NODE_ID))).thenReturn(List.of(s1));

    List<List<ShareModel>> result = shareApi.shares(List.of(node), 10, "unknown-cursor", null);

    assertThat(result).hasSize(1);
    assertThat(result.get(0)).hasSize(1);
  }

  @Test
  void shares_batchIssuesExactlyOneRepositoryCallAndAlignsByPosition() throws Exception {
    NodeModel node1 = makeNodeModel(NODE_ID);
    NodeModel node2 = makeNodeModel(NODE_ID_2);
    NodeModel node3 = makeNodeModel(NODE_ID_3);

    Share s1a = mockShare(NODE_ID, TARGET_ID_1);
    Share s1b = mockShare(NODE_ID, TARGET_ID_2);
    Share s2a = mockShare(NODE_ID_2, TARGET_ID_3);

    when(shareRepository.getShares(anyList())).thenReturn(List.of(s1a, s1b, s2a));

    List<List<ShareModel>> result = shareApi.shares(List.of(node1, node2, node3), 10, null, null);

    verify(shareRepository, times(1)).getShares(anyList());

    assertThat(result).hasSize(3);
    assertThat(result.get(0))
        .extracting(ShareModel::getShareTargetId)
        .containsExactly(TARGET_ID_1, TARGET_ID_2);
    assertThat(result.get(1)).extracting(ShareModel::getShareTargetId).containsExactly(TARGET_ID_3);
    assertThat(result.get(2)).isEmpty();
  }

  @Test
  void shares_aShareWithAnUnmappablePermissionIsNullAndTheOthersAreServed() throws Exception {
    NodeModel node = makeNodeModel(NODE_ID);
    Share first = mockShare(NODE_ID, TARGET_ID_1);
    Share corrupt = mockShare(NODE_ID, TARGET_ID_2);
    when(corrupt.getPermissions()).thenReturn(ACL.decode((short) 2));
    Share last = mockShare(NODE_ID, TARGET_ID_3);
    when(shareRepository.getShares(List.of(NODE_ID))).thenReturn(List.of(first, corrupt, last));

    List<List<ShareModel>> result = shareApi.shares(List.of(node), 10, null, null);

    assertThat(result.get(0)).hasSize(3);
    assertThat(result.get(0).get(0).getShareTargetId()).isEqualTo(TARGET_ID_1);
    assertThat(result.get(0).get(1)).isNull();
    assertThat(result.get(0).get(2).getShareTargetId()).isEqualTo(TARGET_ID_3);
  }

  private static void assertErrorCode(Throwable thrown, ErrorCodes code) {
    assertThat(thrown)
        .isInstanceOfSatisfying(
            FilesGraphQLException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(code));
  }

  @Test
  void createShareRejectsAnExcessivePermissionBeforeNodeAuthorization() {
    when(sharingPolicy.allowsGrant(eq(ACL.SharePermission.READ_AND_WRITE), any()))
        .thenReturn(false);

    assertThatThrownBy(
            () ->
                shareApi.createShare(
                    NODE_ID, TARGET_ID_1, SharePermission.READ_AND_WRITE, null, null))
        .satisfies(ex -> assertErrorCode(ex, ErrorCodes.SHARE_PERMISSION_NOT_ALLOWED));
    verifyNoInteractions(permissionsChecker);
  }

  @Test
  void updateSharesRejectsAnExcessivePermissionBeforeNodeAuthorization() {
    when(sharingPolicy.allowsGrant(eq(ACL.SharePermission.READ_AND_WRITE), any()))
        .thenReturn(false);

    assertThatThrownBy(
            () ->
                shareApi.updateShares(
                    NODE_ID, List.of(TARGET_ID_1), SharePermission.READ_AND_WRITE, null))
        .satisfies(ex -> assertErrorCode(ex, ErrorCodes.SHARE_PERMISSION_NOT_ALLOWED));
    verifyNoInteractions(permissionsChecker);
  }

  @Test
  void updateSharesWithoutAPermissionDoesNotEvaluateTheGrantCap() throws Exception {
    when(permissionsChecker.getPermissions(NODE_ID, REQUESTER_ID))
        .thenReturn(aclWith(ACL.SharePermission.READ_AND_SHARE));
    Share existing = mockShare(NODE_ID, TARGET_ID_1);
    when(shareRepository.getShare(NODE_ID, TARGET_ID_1)).thenReturn(Optional.of(existing));
    when(shareRepository.updateShare(any())).thenAnswer(i -> i.getArgument(0));

    shareApi.updateShares(NODE_ID, List.of(TARGET_ID_1), null, 5000L);

    verify(sharingPolicy, never()).allowsGrant(any(), any());
  }

  @Test
  void aDisabledTypeIsRejectedBeforeTheGrantCapAndNodeAuthorization() {
    when(sharingPolicy.isEnabled(eq(SharingType.ACCOUNT_SHARES), any())).thenReturn(false);

    assertThatThrownBy(
            () ->
                shareApi.createShare(
                    NODE_ID, TARGET_ID_1, SharePermission.READ_AND_WRITE, null, null))
        .satisfies(ex -> assertErrorCode(ex, ErrorCodes.SHARING_TYPE_DISABLED));
    assertThatThrownBy(
            () ->
                shareApi.updateShares(
                    NODE_ID, List.of(TARGET_ID_1), SharePermission.READ_AND_WRITE, null))
        .satisfies(ex -> assertErrorCode(ex, ErrorCodes.SHARING_TYPE_DISABLED));
    verify(sharingPolicy, never()).allowsGrant(any(), any());
    verifyNoInteractions(permissionsChecker);
  }
}
