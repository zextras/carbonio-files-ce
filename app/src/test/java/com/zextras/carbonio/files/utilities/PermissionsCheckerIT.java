// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.utilities;

import static org.assertj.core.api.Assertions.assertThat;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.dao.ebean.Share;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

/**
 * P3a: validates {@link PermissionsChecker}, which computes the effective {@link ACL} of a {@link
 * Node} for a given user relying only on {@code NodeRepository}/{@code ShareRepository}.
 */
@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class PermissionsCheckerIT {

  private static final String OWNER_ID = "11111111-1111-1111-1111-111111111111";
  private static final String SHARED_USER_ID = "22222222-2222-2222-2222-222222222222";
  private static final String STRANGER_ID = "33333333-3333-3333-3333-333333333333";

  @Inject PermissionsChecker permissionsChecker;

  @Inject EntityManager entityManager;

  private Node persistNode(String nodeId, NodeType type) {
    Node node =
        new Node(
            nodeId,
            OWNER_ID,
            OWNER_ID,
            "",
            1L,
            1L,
            "node-" + nodeId,
            "description",
            type,
            "",
            0L);
    entityManager.persist(node);
    return node;
  }

  @Test
  @TestTransaction
  void getPermissionsShouldReturnOwnerForTheOwnerOfTheNode() {
    String nodeId = "44444444-4444-4444-4444-444444444441";
    persistNode(nodeId, NodeType.FOLDER);

    ACL permissions = permissionsChecker.getPermissions(nodeId, OWNER_ID);

    assertThat(permissions).isEqualTo(ACL.decode(ACL.OWNER));
  }

  @Test
  @TestTransaction
  void getPermissionsShouldReturnOwnerForAnyUserOnARootNode() {
    String nodeId = "44444444-4444-4444-4444-444444444442";
    persistNode(nodeId, NodeType.ROOT);

    ACL permissions = permissionsChecker.getPermissions(nodeId, STRANGER_ID);

    assertThat(permissions).isEqualTo(ACL.decode(ACL.OWNER));
  }

  @Test
  @TestTransaction
  void getPermissionsShouldReturnTheSharePermissionsForASharedUser() {
    String nodeId = "44444444-4444-4444-4444-444444444443";
    persistNode(nodeId, NodeType.FOLDER);
    entityManager.persist(
        new Share(
            nodeId,
            SHARED_USER_ID,
            ACL.decode(SharePermission.READ_AND_WRITE),
            1_000L,
            true,
            false,
            null));

    ACL permissions = permissionsChecker.getPermissions(nodeId, SHARED_USER_ID);

    assertThat(permissions.canRead()).isTrue();
    assertThat(permissions.canWrite()).isTrue();
    assertThat(permissions.canShare()).isFalse();
  }

  @Test
  @TestTransaction
  void getPermissionsShouldReturnNoneForAStrangerWithNoShare() {
    String nodeId = "44444444-4444-4444-4444-444444444444";
    persistNode(nodeId, NodeType.FOLDER);

    ACL permissions = permissionsChecker.getPermissions(nodeId, STRANGER_ID);

    assertThat(permissions).isEqualTo(ACL.decode(ACL.NONE));
  }

  @Test
  @TestTransaction
  void getPermissionsShouldReturnNoneForAHiddenNodeEvenForTheOwner() {
    String nodeId = "44444444-4444-4444-4444-444444444445";
    Node node = persistNode(nodeId, NodeType.FOLDER);
    node.setHidden(true);
    entityManager.merge(node);

    ACL permissions = permissionsChecker.getPermissions(nodeId, OWNER_ID);

    assertThat(permissions).isEqualTo(ACL.decode(ACL.NONE));
  }

  @Test
  @TestTransaction
  void getPermissionsShouldReturnNoneWhenNodeDoesNotExist() {
    ACL permissions =
        permissionsChecker.getPermissions(
            "55555555-5555-5555-5555-555555555555", STRANGER_ID);

    assertThat(permissions).isEqualTo(ACL.decode(ACL.NONE));
  }
}
