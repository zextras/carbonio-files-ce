// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal;

import static org.assertj.core.api.Assertions.assertThat;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.dao.ebean.Share;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.ShareRepository;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.hibernate.Session;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class EmptyPersistenceContextCascadeDalTest {

  @Inject NodeRepository nodeRepository;
  @Inject ShareRepository shareRepository;
  @Inject EntityManager entityManager;

  private static String id() {
    return UUID.randomUUID().toString();
  }

  private Node persist(String id, String owner, NodeType type, String parent, String ancestors) {
    long now = System.currentTimeMillis();
    Node node = new Node(id, owner, owner, parent, now, now, "n-" + id, "d", type, ancestors, 0L);
    entityManager.persist(node);
    return node;
  }

  private void emptyPersistenceContext() {
    entityManager.flush();
    entityManager.clear();
    assertThat(entityManager.unwrap(Session.class).getStatistics().getEntityCount()).isZero();
  }

  private long count(String sql) {
    return ((Number) entityManager.createNativeQuery(sql).getSingleResult()).longValue();
  }

  @Test
  @TestTransaction
  void cascadeDeleteInheritedSharesRunsWithAnEmptyPersistenceContext() {
    String owner = id();
    String target = id();
    String folder = id();
    String child = id();
    persist(folder, owner, NodeType.FOLDER, "LOCAL_ROOT", "LOCAL_ROOT");
    persist(child, owner, NodeType.TEXT, folder, "LOCAL_ROOT," + folder);
    entityManager.persist(
        new Share(
            child,
            target,
            ACL.decode(SharePermission.READ_ONLY),
            System.currentTimeMillis(),
            false,
            false,
            null));
    emptyPersistenceContext();

    shareRepository.cascadeDeleteInheritedShares(folder, target);

    assertThat(count("SELECT count(*) FROM share WHERE trim(node_id) = '" + child + "'")).isZero();
  }

  @Test
  @TestTransaction
  void rewriteDescendantAncestorsRunsWithAnEmptyPersistenceContext() {
    String owner = id();
    String folder = id();
    String child = id();
    Node parent = persist(folder, owner, NodeType.FOLDER, "LOCAL_ROOT", "STALE");
    persist(child, owner, NodeType.TEXT, folder, "STALE");
    emptyPersistenceContext();

    assertThat(nodeRepository.rewriteDescendantAncestors(parent)).isEqualTo(1);

    assertThat(
            entityManager
                .createNativeQuery(
                    "SELECT ancestor_ids FROM node WHERE trim(node_id) = '" + child + "'",
                    String.class)
                .getSingleResult())
        .isEqualTo("STALE," + folder);
  }
}
