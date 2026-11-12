// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal;

import static org.assertj.core.api.Assertions.assertThat;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.repositories.interfaces.ShareRepository;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class DeleteSharesOfUserBelowDalTest {

  @Inject ShareRepository shareRepository;
  @Inject EntityManager entityManager;

  private static String id() {
    return UUID.randomUUID().toString();
  }

  private void node(String id, NodeType type, String parent, String ancestors) {
    long now = System.currentTimeMillis();
    entityManager.persist(
        new Node(id, "owner", "owner", parent, now, now, "n-" + id, "d", type, ancestors, 0L));
  }

  private void trash(String nodeId, String oldParent) {
    entityManager.flush();
    entityManager
        .createNativeQuery("INSERT INTO trashed (node_id, parent_id) VALUES (:node, :parent)")
        .setParameter("node", nodeId)
        .setParameter("parent", oldParent)
        .executeUpdate();
  }

  private void share(String nodeId, String target, boolean direct) {
    entityManager.flush();
    entityManager
        .createNativeQuery(
            "INSERT INTO share (node_id, rights, timestamp, target_uuid, direct) VALUES (:node, 1,"
                + " 1, :target, :direct)")
        .setParameter("node", nodeId)
        .setParameter("target", target)
        .setParameter("direct", direct)
        .executeUpdate();
  }

  private boolean hasShare(String nodeId, String target) {
    return ((Number)
                entityManager
                    .createNativeQuery(
                        "SELECT count(*) FROM share WHERE trim(node_id) = :node AND target_uuid ="
                            + " :target")
                    .setParameter("node", nodeId)
                    .setParameter("target", target)
                    .getSingleResult())
            .longValue()
        > 0;
  }

  @Test
  @TestTransaction
  void deletesOnlyTheUserSharesStrictlyBelowTheNode() {
    String u = id();
    String v = id();
    String root = id();
    String sub = id();
    String deepFile = id();
    String directChild = id();
    String trashedFromSub = id();
    String trashedChild = id();
    String outside = id();
    node(root, NodeType.FOLDER, "LOCAL_ROOT", "LOCAL_ROOT");
    node(sub, NodeType.FOLDER, root, "LOCAL_ROOT," + root);
    node(deepFile, NodeType.TEXT, sub, "LOCAL_ROOT," + root + "," + sub);
    node(directChild, NodeType.TEXT, root, "LOCAL_ROOT," + root);
    node(trashedFromSub, NodeType.FOLDER, "TRASH_ROOT", "TRASH_ROOT");
    node(trashedChild, NodeType.TEXT, trashedFromSub, "TRASH_ROOT," + trashedFromSub);
    node(outside, NodeType.FOLDER, "LOCAL_ROOT", "LOCAL_ROOT");
    trash(trashedFromSub, sub);
    share(root, u, true);
    share(sub, u, true);
    share(deepFile, u, false);
    share(directChild, u, false);
    share(trashedFromSub, u, true);
    share(trashedChild, u, false);
    share(sub, v, true);
    share(deepFile, v, false);
    share(trashedFromSub, v, false);
    share(outside, u, true);

    shareRepository.deleteSharesOfUserBelow(root, u);

    assertThat(hasShare(sub, u)).isFalse();
    assertThat(hasShare(deepFile, u)).isFalse();
    assertThat(hasShare(directChild, u)).isFalse();
    assertThat(hasShare(trashedFromSub, u)).isFalse();
    assertThat(hasShare(trashedChild, u)).isFalse();
    assertThat(hasShare(root, u)).isTrue();
    assertThat(hasShare(outside, u)).isTrue();
    assertThat(hasShare(sub, v)).isTrue();
    assertThat(hasShare(deepFile, v)).isTrue();
    assertThat(hasShare(trashedFromSub, v)).isTrue();
  }
}
