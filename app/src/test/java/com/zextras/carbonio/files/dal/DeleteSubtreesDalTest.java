// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal;

import static org.assertj.core.api.Assertions.assertThat;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.rest.services.BlobKey;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.hibernate.Session;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class DeleteSubtreesDalTest {

  private static final String ALICE = UUID.randomUUID().toString();
  private static final String BOB = UUID.randomUUID().toString();

  @Inject NodeRepository nodeRepository;
  @Inject EntityManager entityManager;

  private static String id() {
    return UUID.randomUUID().toString();
  }

  private void node(String id, String owner, NodeType type, String parent, String ancestors) {
    long now = System.currentTimeMillis();
    entityManager.persist(
        new Node(id, owner, owner, parent, now, now, "n-" + id, "d", type, ancestors, 0L));
  }

  private void revisions(String nodeId, int count) {
    entityManager.flush();
    for (int version = 1; version <= count; version++) {
      entityManager
          .createNativeQuery(
              "INSERT INTO revision (node_id, version, mime_type, size, digest, editor_id,"
                  + " timestamp) VALUES (:node, :version, 'text/plain', 1, 'd', 'e', 1)")
          .setParameter("node", nodeId)
          .setParameter("version", version)
          .executeUpdate();
    }
  }

  private void trash(String nodeId, String oldParent) {
    entityManager.flush();
    entityManager
        .createNativeQuery("INSERT INTO trashed (node_id, parent_id) VALUES (:node, :parent)")
        .setParameter("node", nodeId)
        .setParameter("parent", oldParent)
        .executeUpdate();
  }

  private void share(String nodeId, String target) {
    entityManager.flush();
    entityManager
        .createNativeQuery(
            "INSERT INTO share (node_id, rights, timestamp, target_uuid, direct) VALUES (:node, 1,"
                + " 1, :target, true)")
        .setParameter("node", nodeId)
        .setParameter("target", target)
        .executeUpdate();
  }

  private long count(String sql) {
    return ((Number) entityManager.createNativeQuery(sql).getSingleResult()).longValue();
  }

  private static String ids(Collection<String> ids) {
    return String.join(", ", ids.stream().map(i -> "'" + i + "'").toList());
  }

  @Test
  @TestTransaction
  void tombstonesExactlyTheRevisionsItDeletesUnderTheOwnerOfEachNode() {
    String top = id();
    String sub = id();
    String file1 = id();
    String file2 = id();
    String deep = id();
    String foreign = id();
    String trashedFromSub = id();
    String trashedFile = id();
    String outside = id();
    node(top, ALICE, NodeType.FOLDER, "LOCAL_ROOT", "LOCAL_ROOT");
    node(sub, ALICE, NodeType.FOLDER, top, "LOCAL_ROOT," + top);
    node(file1, ALICE, NodeType.TEXT, top, "LOCAL_ROOT," + top);
    node(file2, ALICE, NodeType.TEXT, top, "LOCAL_ROOT," + top);
    node(deep, ALICE, NodeType.TEXT, sub, "LOCAL_ROOT," + top + "," + sub);
    node(foreign, BOB, NodeType.TEXT, sub, "LOCAL_ROOT," + top + "," + sub);
    node(trashedFromSub, ALICE, NodeType.FOLDER, "TRASH_ROOT", "TRASH_ROOT");
    node(trashedFile, ALICE, NodeType.TEXT, trashedFromSub, "TRASH_ROOT," + trashedFromSub);
    node(outside, ALICE, NodeType.TEXT, "LOCAL_ROOT", "LOCAL_ROOT");
    revisions(file1, 1);
    revisions(file2, 3);
    revisions(deep, 2);
    revisions(foreign, 2);
    revisions(trashedFile, 2);
    revisions(outside, 2);
    trash(trashedFromSub, sub);
    share(trashedFromSub, "target");
    share(file1, "target");
    entityManager.flush();
    entityManager.clear();
    Set<String> deleted = Set.of(top, sub, file1, file2, deep, foreign);

    Map<String, List<BlobKey>> keys = nodeRepository.deleteSubtrees(List.of(top));

    Set<String> returned = new HashSet<>();
    keys.forEach(
        (owner, list) ->
            list.forEach(key -> returned.add(key.nodeId() + ":" + key.version() + ":" + owner)));
    assertThat(keys.values().stream().mapToInt(List::size).sum()).isEqualTo(8);
    assertThat(returned)
        .containsExactlyInAnyOrder(
            file1 + ":1:" + ALICE,
            file2 + ":1:" + ALICE,
            file2 + ":2:" + ALICE,
            file2 + ":3:" + ALICE,
            deep + ":1:" + ALICE,
            deep + ":2:" + ALICE,
            foreign + ":1:" + BOB,
            foreign + ":2:" + BOB);
    @SuppressWarnings("unchecked")
    List<Object[]> tombstones =
        entityManager
            .createNativeQuery(
                "SELECT trim(node_id), version, owner_id FROM tombstone WHERE trim(node_id) IN ("
                    + ids(Set.of(file1, file2, deep, foreign, trashedFile, outside))
                    + ")",
                Object[].class)
            .getResultList();
    List<String> written = new ArrayList<>();
    tombstones.forEach(row -> written.add(row[0] + ":" + row[1] + ":" + row[2]));
    assertThat(written).containsExactlyInAnyOrderElementsOf(returned);
    assertThat(count("SELECT count(*) FROM node WHERE trim(node_id) IN (" + ids(deleted) + ")"))
        .isZero();
    assertThat(count("SELECT count(*) FROM revision WHERE trim(node_id) IN (" + ids(deleted) + ")"))
        .isZero();
    assertThat(count("SELECT count(*) FROM share WHERE trim(node_id) = '" + file1 + "'")).isZero();
    assertThat(count("SELECT count(*) FROM node WHERE trim(node_id) = '" + trashedFromSub + "'"))
        .isEqualTo(1);
    assertThat(count("SELECT count(*) FROM share WHERE trim(node_id) = '" + trashedFromSub + "'"))
        .isZero();
    assertThat(count("SELECT count(*) FROM revision WHERE trim(node_id) = '" + outside + "'"))
        .isEqualTo(2);
  }

  @Test
  @TestTransaction
  void deletesEachOverlappingRootOnceAndReturnsEachKeyOnce() {
    String top = id();
    String file = id();
    node(top, ALICE, NodeType.FOLDER, "LOCAL_ROOT", "LOCAL_ROOT");
    node(file, ALICE, NodeType.TEXT, top, "LOCAL_ROOT," + top);
    revisions(file, 2);
    entityManager.flush();
    entityManager.clear();

    Map<String, List<BlobKey>> keys = nodeRepository.deleteSubtrees(List.of(top, file, top));

    assertThat(keys.get(ALICE))
        .containsExactlyInAnyOrder(new BlobKey(file, 1), new BlobKey(file, 2));
    assertThat(count("SELECT count(*) FROM tombstone WHERE trim(node_id) = '" + file + "'"))
        .isEqualTo(2);
  }

  @Test
  @TestTransaction
  void runsWithAnEmptyPersistenceContextAndLeavesItEmpty() {
    String top = id();
    String file = id();
    node(top, ALICE, NodeType.FOLDER, "LOCAL_ROOT", "LOCAL_ROOT");
    node(file, ALICE, NodeType.TEXT, top, "LOCAL_ROOT," + top);
    revisions(file, 1);
    entityManager.flush();
    entityManager.clear();
    assertThat(entityManager.unwrap(Session.class).getStatistics().getEntityCount()).isZero();

    assertThat(nodeRepository.deleteSubtrees(List.of(top)).get(ALICE))
        .containsExactly(new BlobKey(file, 1));

    assertThat(entityManager.unwrap(Session.class).getStatistics().getEntityCount()).isZero();
    assertThat(entityManager.find(Node.class, top)).isNull();
  }

  @Test
  @TestTransaction
  void noRootsDeleteNothing() {
    assertThat(nodeRepository.deleteSubtrees(List.of())).isEmpty();
  }
}
