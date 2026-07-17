// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal;

import static org.assertj.core.api.Assertions.assertThat;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

/**
 * P2b runtime validation: proves the ported Hibernate/JPA entities actually instantiate and
 * round-trip against the real (Flyway-migrated) schema — i.e. the mechanically added no-arg
 * constructors and the @Column/@Enumerated mappings work at runtime, not just at build time.
 */
@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class NodeMappingIT {

  @Inject EntityManager entityManager;

  @Test
  @Transactional
  void nodeRoundTripsThroughHibernate() {
    String nodeId = "11111111-1111-1111-1111-111111111111"; // CHAR(36)
    Node node =
        new Node(
            nodeId,
            "creator-id",
            "owner-id",
            "parent-id",
            1L,
            1L,
            "p2b-mapping-test",
            "description",
            NodeType.FOLDER,
            "",
            0L);

    entityManager.persist(node);
    entityManager.flush();
    entityManager.clear();

    Node found = entityManager.find(Node.class, nodeId);
    assertThat(found).isNotNull();
    assertThat(found.getId()).isEqualTo(nodeId);
    assertThat(found.getName()).isEqualTo("p2b-mapping-test");
  }
}
