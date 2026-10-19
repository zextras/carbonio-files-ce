// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.utilities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.dao.ebean.Share;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.ShareRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Plain JUnit (NOT {@code @QuarkusTest}) unit test for {@link PermissionsChecker}'s ACL
 * decode/combine logic, replacing the former {@code
 * com.zextras.carbonio.files.utilities.PermissionsCheckerIT} (which needed a real Postgres
 * Testcontainer purely to persist a {@link Node}/{@link Share} row that {@link PermissionsChecker}
 * itself never queries with anything but plain repository calls). {@link PermissionsChecker}'s
 * constructor takes {@link NodeRepository}/{@link ShareRepository} directly, so both are mocked
 * here — no DB, no CDI. All 6 scenarios/assertions from the deleted IT are preserved verbatim.
 */
class PermissionsCheckerTest {

  private static final String OWNER_ID = "11111111-1111-1111-1111-111111111111";
  private static final String SHARED_USER_ID = "22222222-2222-2222-2222-222222222222";
  private static final String STRANGER_ID = "33333333-3333-3333-3333-333333333333";

  private static Node node(String id, NodeType type, boolean hidden) {
    Node node =
        new Node(id, OWNER_ID, OWNER_ID, "", 1L, 1L, "node-" + id, "description", type, "", 0L);
    node.setHidden(hidden);
    return node;
  }

  @Test
  void getPermissionsShouldReturnOwnerForTheOwnerOfTheNode() {
    NodeRepository nodeRepository = mock(NodeRepository.class);
    ShareRepository shareRepository = mock(ShareRepository.class);
    String nodeId = "44444444-4444-4444-4444-444444444441";
    when(nodeRepository.getNode(nodeId))
        .thenReturn(Optional.of(node(nodeId, NodeType.FOLDER, false)));
    PermissionsChecker checker = new PermissionsChecker(nodeRepository, shareRepository);

    ACL permissions = checker.getPermissions(nodeId, OWNER_ID);

    assertThat(permissions).isEqualTo(ACL.decode(ACL.OWNER));
  }

  @Test
  void getPermissionsShouldReturnOwnerForAnyUserOnARootNode() {
    NodeRepository nodeRepository = mock(NodeRepository.class);
    ShareRepository shareRepository = mock(ShareRepository.class);
    String nodeId = "44444444-4444-4444-4444-444444444442";
    when(nodeRepository.getNode(nodeId))
        .thenReturn(Optional.of(node(nodeId, NodeType.ROOT, false)));
    PermissionsChecker checker = new PermissionsChecker(nodeRepository, shareRepository);

    ACL permissions = checker.getPermissions(nodeId, STRANGER_ID);

    assertThat(permissions).isEqualTo(ACL.decode(ACL.OWNER));
  }

  @Test
  void getPermissionsShouldReturnTheSharePermissionsForASharedUser() {
    NodeRepository nodeRepository = mock(NodeRepository.class);
    ShareRepository shareRepository = mock(ShareRepository.class);
    String nodeId = "44444444-4444-4444-4444-444444444443";
    when(nodeRepository.getNode(nodeId))
        .thenReturn(Optional.of(node(nodeId, NodeType.FOLDER, false)));
    when(shareRepository.getSharePermissions(nodeId, SHARED_USER_ID))
        .thenReturn(Optional.of(ACL.decode(SharePermission.READ_AND_WRITE)));
    PermissionsChecker checker = new PermissionsChecker(nodeRepository, shareRepository);

    ACL permissions = checker.getPermissions(nodeId, SHARED_USER_ID);

    assertThat(permissions.canRead()).isTrue();
    assertThat(permissions.canWrite()).isTrue();
    assertThat(permissions.canShare()).isFalse();
  }

  @Test
  void getPermissionsShouldReturnNoneForAStrangerWithNoShare() {
    NodeRepository nodeRepository = mock(NodeRepository.class);
    ShareRepository shareRepository = mock(ShareRepository.class);
    String nodeId = "44444444-4444-4444-4444-444444444444";
    when(nodeRepository.getNode(nodeId))
        .thenReturn(Optional.of(node(nodeId, NodeType.FOLDER, false)));
    when(shareRepository.getSharePermissions(nodeId, STRANGER_ID)).thenReturn(Optional.empty());
    PermissionsChecker checker = new PermissionsChecker(nodeRepository, shareRepository);

    ACL permissions = checker.getPermissions(nodeId, STRANGER_ID);

    assertThat(permissions).isEqualTo(ACL.decode(ACL.NONE));
  }

  @Test
  void getPermissionsShouldReturnNoneForAHiddenNodeEvenForTheOwner() {
    NodeRepository nodeRepository = mock(NodeRepository.class);
    ShareRepository shareRepository = mock(ShareRepository.class);
    String nodeId = "44444444-4444-4444-4444-444444444445";
    when(nodeRepository.getNode(nodeId))
        .thenReturn(Optional.of(node(nodeId, NodeType.FOLDER, true)));
    PermissionsChecker checker = new PermissionsChecker(nodeRepository, shareRepository);

    ACL permissions = checker.getPermissions(nodeId, OWNER_ID);

    assertThat(permissions).isEqualTo(ACL.decode(ACL.NONE));
  }

  @Test
  void getPermissionsShouldReturnNoneWhenNodeDoesNotExist() {
    NodeRepository nodeRepository = mock(NodeRepository.class);
    ShareRepository shareRepository = mock(ShareRepository.class);
    when(nodeRepository.getNode("55555555-5555-5555-5555-555555555555"))
        .thenReturn(Optional.empty());
    PermissionsChecker checker = new PermissionsChecker(nodeRepository, shareRepository);

    ACL permissions = checker.getPermissions("55555555-5555-5555-5555-555555555555", STRANGER_ID);

    assertThat(permissions).isEqualTo(ACL.decode(ACL.NONE));
  }

  @Test
  void getPermissionsForManyNodesShouldResolveEachNodeWithOneNodeQueryAndOneShareQuery() {
    NodeRepository nodeRepository = mock(NodeRepository.class);
    ShareRepository shareRepository = mock(ShareRepository.class);
    String rootId = "LOCAL_ROOT";
    String hiddenId = "44444444-4444-4444-4444-444444444452";
    String sharedId = "44444444-4444-4444-4444-444444444453";
    String notSharedId = "44444444-4444-4444-4444-444444444454";
    String missingId = "44444444-4444-4444-4444-444444444455";
    when(nodeRepository.getNodes(
            List.of(rootId, hiddenId, sharedId, notSharedId, missingId), Optional.empty()))
        .thenReturn(
            Stream.of(
                node(rootId, NodeType.ROOT, false),
                node(hiddenId, NodeType.FOLDER, true),
                node(sharedId, NodeType.FOLDER, false),
                node(notSharedId, NodeType.FOLDER, false)));
    when(shareRepository.getSharePermissions(anyList(), eq(SHARED_USER_ID)))
        .thenReturn(Map.of(sharedId, ACL.decode(SharePermission.READ_AND_WRITE)));
    PermissionsChecker checker = new PermissionsChecker(nodeRepository, shareRepository);

    Map<String, ACL> permissions =
        checker.getPermissions(
            List.of(rootId, hiddenId, sharedId, notSharedId, missingId), SHARED_USER_ID);

    assertThat(permissions).hasSize(5);
    assertThat(permissions).containsEntry(rootId, ACL.decode(ACL.OWNER));
    assertThat(permissions).containsEntry(hiddenId, ACL.decode(ACL.NONE));
    assertThat(permissions.get(sharedId).canWrite()).isTrue();
    assertThat(permissions.get(sharedId).canShare()).isFalse();
    assertThat(permissions).containsEntry(notSharedId, ACL.decode(ACL.NONE));
    assertThat(permissions).containsEntry(missingId, ACL.decode(ACL.NONE));
    verify(nodeRepository, times(1)).getNodes(anyList(), any());
    verify(shareRepository, times(1)).getSharePermissions(anyList(), anyString());
    verify(shareRepository)
        .getSharePermissions(
            argThat(
                (List<String> ids) ->
                    ids.size() == 2 && ids.containsAll(List.of(sharedId, notSharedId))),
            eq(SHARED_USER_ID));
  }

  @Test
  void getPermissionsOfNodesShouldNotLoadNodesAndQueryTheSharesOnlyForTheNodesThatNeedThem() {
    NodeRepository nodeRepository = mock(NodeRepository.class);
    ShareRepository shareRepository = mock(ShareRepository.class);
    String rootId = "LOCAL_ROOT";
    String hiddenId = "44444444-4444-4444-4444-444444444462";
    String ownedId = "44444444-4444-4444-4444-444444444463";
    String sharedId = "44444444-4444-4444-4444-444444444464";
    String notSharedId = "44444444-4444-4444-4444-444444444465";
    Node owned = node(ownedId, NodeType.FOLDER, false);
    Node shared = node(sharedId, NodeType.FOLDER, false);
    shared.setOwnerId(STRANGER_ID);
    Node notShared = node(notSharedId, NodeType.FOLDER, false);
    notShared.setOwnerId(STRANGER_ID);
    when(shareRepository.getSharePermissions(anyList(), eq(OWNER_ID)))
        .thenReturn(Map.of(sharedId, ACL.decode(SharePermission.READ_ONLY)));
    PermissionsChecker checker = new PermissionsChecker(nodeRepository, shareRepository);

    Map<String, ACL> permissions =
        checker.getPermissionsOfNodes(
            List.of(
                node(rootId, NodeType.ROOT, false),
                node(hiddenId, NodeType.FOLDER, true),
                owned,
                shared,
                notShared),
            OWNER_ID);

    assertThat(permissions).hasSize(5);
    assertThat(permissions).containsEntry(rootId, ACL.decode(ACL.OWNER));
    assertThat(permissions).containsEntry(hiddenId, ACL.decode(ACL.NONE));
    assertThat(permissions).containsEntry(ownedId, ACL.decode(ACL.OWNER));
    assertThat(permissions).containsEntry(sharedId, ACL.decode(SharePermission.READ_ONLY));
    assertThat(permissions).containsEntry(notSharedId, ACL.decode(ACL.NONE));
    verifyNoInteractions(nodeRepository);
    verify(shareRepository, times(1)).getSharePermissions(anyList(), anyString());
    verify(shareRepository)
        .getSharePermissions(
            argThat(
                (List<String> ids) ->
                    ids.size() == 2 && ids.containsAll(List.of(sharedId, notSharedId))),
            eq(OWNER_ID));
  }
}
