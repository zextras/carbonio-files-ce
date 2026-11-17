// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal;

import static org.assertj.core.api.Assertions.assertThat;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class NodeHiddenForOwnerDalTest {

  @Inject NodeRepository nodeRepository;
  @Inject EntityManager entityManager;

  private static String id() {
    return UUID.randomUUID().toString();
  }

  private String persist(String owner, boolean hidden) {
    String id = id();
    long now = System.currentTimeMillis();
    Node node =
        new Node(
            id, owner, owner, "LOCAL_ROOT", now, now, id, "d", NodeType.TEXT, "LOCAL_ROOT", 1L);
    node.setHidden(hidden);
    entityManager.persist(node);
    return id;
  }

  private boolean hiddenInDb(String nodeId) {
    return (Boolean)
        entityManager
            .createNativeQuery("select hidden from node where node_id = :id")
            .setParameter("id", nodeId)
            .getSingleResult();
  }

  private long hiddenRootsCount() {
    return ((Number)
            entityManager
                .createNativeQuery("select count(*) from node where owner_id is null and hidden")
                .getSingleResult())
        .longValue();
  }

  @Test
  @TestTransaction
  void setHiddenForOwnerHidesOnlyTheOwnersNodesAndIsIdempotent() {
    String owner = id();
    String other = id();
    String a = persist(owner, false);
    String b = persist(owner, true);
    String c = persist(other, false);
    entityManager.flush();
    long rootsHiddenBefore = hiddenRootsCount();

    assertThat(nodeRepository.setHiddenForOwner(owner, true)).isEqualTo(1);

    assertThat(hiddenInDb(a)).isTrue();
    assertThat(hiddenInDb(b)).isTrue();
    assertThat(hiddenInDb(c)).isFalse();
    assertThat(hiddenRootsCount()).isEqualTo(rootsHiddenBefore);

    assertThat(nodeRepository.setHiddenForOwner(owner, true)).isZero();

    assertThat(hiddenInDb(a)).isTrue();
    assertThat(hiddenInDb(b)).isTrue();
    assertThat(hiddenInDb(c)).isFalse();
    assertThat(entityManager.find(Node.class, a).isHidden()).isTrue();

    assertThat(nodeRepository.setHiddenForOwner(owner, false)).isEqualTo(2);

    assertThat(hiddenInDb(a)).isFalse();
    assertThat(hiddenInDb(b)).isFalse();
    assertThat(hiddenInDb(c)).isFalse();
    assertThat(hiddenRootsCount()).isEqualTo(rootsHiddenBefore);
  }
}
