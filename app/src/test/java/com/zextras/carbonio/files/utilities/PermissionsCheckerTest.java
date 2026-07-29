// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.utilities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.dao.ebean.Share;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.ShareRepository;
import java.util.Optional;
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
    when(nodeRepository.getNode(nodeId)).thenReturn(Optional.of(node(nodeId, NodeType.FOLDER, false)));
    PermissionsChecker checker = new PermissionsChecker(nodeRepository, shareRepository);

    ACL permissions = checker.getPermissions(nodeId, OWNER_ID);

    assertThat(permissions).isEqualTo(ACL.decode(ACL.OWNER));
  }

  @Test
  void getPermissionsShouldReturnOwnerForAnyUserOnARootNode() {
    NodeRepository nodeRepository = mock(NodeRepository.class);
    ShareRepository shareRepository = mock(ShareRepository.class);
    String nodeId = "44444444-4444-4444-4444-444444444442";
    when(nodeRepository.getNode(nodeId)).thenReturn(Optional.of(node(nodeId, NodeType.ROOT, false)));
    PermissionsChecker checker = new PermissionsChecker(nodeRepository, shareRepository);

    ACL permissions = checker.getPermissions(nodeId, STRANGER_ID);

    assertThat(permissions).isEqualTo(ACL.decode(ACL.OWNER));
  }

  @Test
  void getPermissionsShouldReturnTheSharePermissionsForASharedUser() {
    NodeRepository nodeRepository = mock(NodeRepository.class);
    ShareRepository shareRepository = mock(ShareRepository.class);
    String nodeId = "44444444-4444-4444-4444-444444444443";
    when(nodeRepository.getNode(nodeId)).thenReturn(Optional.of(node(nodeId, NodeType.FOLDER, false)));
    Share share =
        new Share(
            nodeId, SHARED_USER_ID, ACL.decode(SharePermission.READ_AND_WRITE), 1_000L, true, false,
            null);
    when(shareRepository.getShare(nodeId, SHARED_USER_ID)).thenReturn(Optional.of(share));
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
    when(nodeRepository.getNode(nodeId)).thenReturn(Optional.of(node(nodeId, NodeType.FOLDER, false)));
    when(shareRepository.getShare(nodeId, STRANGER_ID)).thenReturn(Optional.empty());
    PermissionsChecker checker = new PermissionsChecker(nodeRepository, shareRepository);

    ACL permissions = checker.getPermissions(nodeId, STRANGER_ID);

    assertThat(permissions).isEqualTo(ACL.decode(ACL.NONE));
  }

  @Test
  void getPermissionsShouldReturnNoneForAHiddenNodeEvenForTheOwner() {
    NodeRepository nodeRepository = mock(NodeRepository.class);
    ShareRepository shareRepository = mock(ShareRepository.class);
    String nodeId = "44444444-4444-4444-4444-444444444445";
    when(nodeRepository.getNode(nodeId)).thenReturn(Optional.of(node(nodeId, NodeType.FOLDER, true)));
    PermissionsChecker checker = new PermissionsChecker(nodeRepository, shareRepository);

    ACL permissions = checker.getPermissions(nodeId, OWNER_ID);

    assertThat(permissions).isEqualTo(ACL.decode(ACL.NONE));
  }

  @Test
  void getPermissionsShouldReturnNoneWhenNodeDoesNotExist() {
    NodeRepository nodeRepository = mock(NodeRepository.class);
    ShareRepository shareRepository = mock(ShareRepository.class);
    when(nodeRepository.getNode("55555555-5555-5555-5555-555555555555")).thenReturn(Optional.empty());
    PermissionsChecker checker = new PermissionsChecker(nodeRepository, shareRepository);

    ACL permissions = checker.getPermissions("55555555-5555-5555-5555-555555555555", STRANGER_ID);

    assertThat(permissions).isEqualTo(ACL.decode(ACL.NONE));
  }
}
