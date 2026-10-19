// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.dal.dao.ebean.Share;
import com.zextras.carbonio.files.dal.repositories.interfaces.ShareRepository;
import com.zextras.carbonio.files.graphql.model.FolderModel;
import com.zextras.carbonio.files.graphql.model.NodeModel;
import com.zextras.carbonio.files.graphql.model.ShareModel;
import com.zextras.carbonio.files.graphql.validation.GraphQLInputValidator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ShareApiTest {

  private static final String NODE_ID = "00000000-0000-0000-0000-000000000001";
  private static final String TARGET_ID_1 = "target-user-1";
  private static final String TARGET_ID_2 = "target-user-2";

  private ShareRepository shareRepository;
  private ShareApi shareApi;

  @BeforeEach
  void setUp() {
    shareRepository = mock(ShareRepository.class);

    shareApi = new ShareApi();
    shareApi.shareRepository = shareRepository;
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
}
