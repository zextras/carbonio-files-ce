// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.dao.ebean.Share;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Restores, from the deleted (Phase 7b) white-box {@code NodeRepositoryIT}, the real-Postgres
 * coverage of {@code NodeRepositoryImpl#calculateAbsoluteFolderSize} and {@code
 * #calculateRelativeFolderSize}: both are raw-SQL recursive-CTE queries with no equivalent
 * black-box REST/GraphQL assertion elsewhere, and the whole point of these two tests is the EXACT
 * byte counts (hidden-node exclusion, share-visibility pruning) — never weakened to "size &gt;
 * 0".
 *
 * <p><b>Why {@code @QuarkusTest}, and why the {@code *DalTest} suffix (not {@code *IT}):</b> this
 * class needs a real database and CDI-injected repositories, so it must be in-process (JVM,
 * {@code @QuarkusTest} + {@link FilesStackTestResource}) — no hybrid, out-of-process alternative
 * exists. Naming it {@code *DalTest} instead of {@code *IT} routes it to Maven's default
 * <b>surefire</b> {@code *Test} inclusion pattern rather than <b>failsafe</b>'s {@code *IT}
 * pattern, so it runs during {@code test}, never during {@code integration-test}. That keeps the
 * failsafe IT suite 100% out-of-process ({@code @QuarkusIntegrationTest}), so {@code verify
 * -Dnative} still exercises the whole IT suite against the packaged NATIVE binary. This class
 * exercises SQL, not the packaged binary, so it never needed native coverage in the first place —
 * excluding it from the native run costs nothing.
 */
@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class NodeQueryDalTest {

  @Inject NodeRepository nodeRepository;
  @Inject EntityManager entityManager;

  private static String id() {
    return UUID.randomUUID().toString();
  }

  private Node persist(
      String id,
      String name,
      String owner,
      NodeType type,
      boolean hidden,
      long size,
      String parent,
      String ancestors) {
    long now = System.currentTimeMillis();
    Node node = new Node(id, owner, owner, parent, now, now, name, "desc", type, ancestors, size);
    if (hidden) {
      node.setHidden(true);
    }
    entityManager.persist(node);
    return node;
  }

  private void shareTo(String nodeId, String userId) {
    entityManager.persist(
        new Share(
            nodeId,
            userId,
            ACL.decode(SharePermission.READ_ONLY),
            System.currentTimeMillis(),
            true,
            false,
            null));
  }

  @Test
  @TestTransaction
  void calculateAbsoluteFolderSizeSumsSubtreeExcludingHidden() {
    String owner = id();
    String folderA = id();
    String folderB = id();
    String fileC = id();
    String fileD = id();
    String fileEHidden = id();
    persist(folderA, "A", owner, NodeType.FOLDER, false, 0L, "LOCAL_ROOT", "LOCAL_ROOT");
    persist(folderB, "B", owner, NodeType.FOLDER, false, 0L, folderA, "LOCAL_ROOT," + folderA);
    persist(fileC, "C", owner, NodeType.TEXT, false, 100L, folderA, "LOCAL_ROOT," + folderA);
    persist(
        fileD, "D", owner, NodeType.TEXT, false, 200L, folderB,
        "LOCAL_ROOT," + folderA + "," + folderB);
    persist(fileEHidden, "E", owner, NodeType.TEXT, true, 50L, folderA, "LOCAL_ROOT," + folderA);
    entityManager.flush();

    assertThat(nodeRepository.calculateAbsoluteFolderSize(folderA)).contains(300L);

    assertThatThrownBy(() -> nodeRepository.calculateAbsoluteFolderSize(fileC))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  @TestTransaction
  void calculateRelativeFolderSizeRespectsVisibility() {
    String owner = id();
    String viewer = id();
    String stranger = id();
    String folderA = id();
    String folderB = id();
    String fileC = id();
    String fileD = id();
    persist(folderA, "A", owner, NodeType.FOLDER, false, 0L, "LOCAL_ROOT", "LOCAL_ROOT");
    persist(folderB, "B", owner, NodeType.FOLDER, false, 0L, folderA, "LOCAL_ROOT," + folderA);
    persist(fileC, "C", owner, NodeType.TEXT, false, 100L, folderA, "LOCAL_ROOT," + folderA);
    persist(
        fileD, "D", owner, NodeType.TEXT, false, 200L, folderB,
        "LOCAL_ROOT," + folderA + "," + folderB);
    entityManager.flush();

    // Owner sees the whole subtree.
    assertThat(nodeRepository.calculateRelativeFolderSize(folderA, owner)).contains(300L);
    // A stranger cannot even see the anchor folder.
    assertThat(nodeRepository.calculateRelativeFolderSize(folderA, stranger)).contains(0L);

    // Share A, B and D (but NOT C) with the viewer: C is pruned because it is not visible to them.
    shareTo(folderA, viewer);
    shareTo(folderB, viewer);
    shareTo(fileD, viewer);
    entityManager.flush();

    assertThat(nodeRepository.calculateRelativeFolderSize(folderA, viewer)).contains(200L);
  }
}
