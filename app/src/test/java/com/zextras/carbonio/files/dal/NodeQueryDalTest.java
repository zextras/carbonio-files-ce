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
import com.zextras.carbonio.files.dal.dao.ebean.NodeCustomAttributes;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.dao.ebean.Share;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Restores, from the deleted (Phase 7b) white-box {@code NodeRepositoryIT}, the real-Postgres
 * coverage of {@code NodeRepositoryImpl} raw-SQL queries that have no equivalent black-box
 * REST/GraphQL assertion elsewhere: the recursive-CTE {@code #calculateAbsoluteFolderSize} and
 * {@code #calculateRelativeFolderSize} (asserted on EXACT byte counts — hidden-node exclusion,
 * share-visibility pruning — never weakened to "size &gt; 0"), plus the {@code #findNodes} per-user
 * flag scoping (an unflagged search must ignore another user's flag row) and its literal-escaping
 * of {@code %}/{@code _} keyword metacharacters.
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

  private void flagFor(String nodeId, String userId) {
    entityManager.persist(new NodeCustomAttributes(nodeId, userId, true));
  }

  private List<Node> find(String userId, Optional<Boolean> flagged, List<String> keywords) {
    return nodeRepository
        .findNodes(
            userId,
            Optional.empty(),
            flagged,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            keywords,
            Optional.empty())
        .getLeft();
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
        fileD,
        "D",
        owner,
        NodeType.TEXT,
        false,
        200L,
        folderB,
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
        fileD,
        "D",
        owner,
        NodeType.TEXT,
        false,
        200L,
        folderB,
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

  @Test
  @TestTransaction
  void findNodesUnflaggedExcludesANodeFlaggedOnlyByAnotherUser() {
    String bob = id();
    String alice = id();
    String flaggedByAlice = id();
    String neverFlagged = id();
    persist(
        flaggedByAlice,
        "shared-report",
        bob,
        NodeType.TEXT,
        false,
        10L,
        "LOCAL_ROOT",
        "LOCAL_ROOT");
    persist(neverFlagged, "own-report", bob, NodeType.TEXT, false, 20L, "LOCAL_ROOT", "LOCAL_ROOT");
    shareTo(flaggedByAlice, alice);
    flagFor(flaggedByAlice, alice);
    entityManager.flush();

    // Bob never flagged his node: Alice's flag=true row must not leak it into Bob's unflagged view,
    // yet a node with no custom-attribute row at all is still returned (control).
    assertThat(find(bob, Optional.of(false), List.of()))
        .extracting(Node::getId)
        .containsExactly(neverFlagged);
  }

  @Test
  @TestTransaction
  void findNodesKeywordTreatsPercentAsLiteralNotAsWildcard() {
    String owner = id();
    String literalPercent = id();
    String plainName = id();
    persist(
        literalPercent,
        "report%2024",
        owner,
        NodeType.TEXT,
        false,
        10L,
        "LOCAL_ROOT",
        "LOCAL_ROOT");
    persist(plainName, "reportX2024", owner, NodeType.TEXT, false, 20L, "LOCAL_ROOT", "LOCAL_ROOT");
    entityManager.flush();

    // Sanity: a plain keyword matches both, proving both nodes are visible and searchable.
    assertThat(find(owner, Optional.empty(), List.of("report")))
        .extracting(Node::getId)
        .containsExactlyInAnyOrder(literalPercent, plainName);

    // "%" is escaped and matched literally, so it selects ONLY the name that actually contains it
    // instead of matching every node the way an unescaped LIKE wildcard would.
    assertThat(find(owner, Optional.empty(), List.of("%")))
        .extracting(Node::getId)
        .containsExactly(literalPercent);
  }

  @Test
  @TestTransaction
  void getChildrenOfSeveralParentsHonoursShowMarked() {
    String owner = id();
    String parentA = id();
    String parentB = id();
    String fileA = id();
    String folderA = id();
    String trashedB = id();
    String plainB = id();
    persist(parentA, "pa", owner, NodeType.FOLDER, false, 0L, "LOCAL_ROOT", "LOCAL_ROOT");
    persist(parentB, "pb", owner, NodeType.FOLDER, false, 0L, "LOCAL_ROOT", "LOCAL_ROOT");
    String ancestorsA = "LOCAL_ROOT," + parentA;
    String ancestorsB = "LOCAL_ROOT," + parentB;
    persist(fileA, "fa", owner, NodeType.TEXT, false, 1L, parentA, ancestorsA);
    persist(folderA, "fo", owner, NodeType.FOLDER, false, 0L, parentA, ancestorsA);
    persist(trashedB, "tb", owner, NodeType.TEXT, false, 1L, parentB, ancestorsB);
    persist(plainB, "pbf", owner, NodeType.TEXT, false, 1L, parentB, ancestorsB);
    entityManager.flush();
    nodeRepository.trashNode(trashedB, parentB);
    entityManager.flush();

    assertThat(nodeRepository.getChildren(List.of(parentA, parentB), true))
        .extracting(Node::getId)
        .containsExactlyInAnyOrder(fileA, folderA, trashedB, plainB);
    assertThat(nodeRepository.getChildren(List.of(parentA, parentB), false))
        .extracting(Node::getId)
        .containsExactlyInAnyOrder(fileA, folderA, plainB);
    assertThat(nodeRepository.getChildren(List.of(), true)).isEmpty();
  }

  @Test
  @TestTransaction
  void getTrashedNodeIdsByOldParentsCollectsAllGivenParents() {
    String owner = id();
    String parentA = id();
    String parentB = id();
    String parentC = id();
    String inA = id();
    String inB = id();
    String inC = id();
    persist(parentA, "pa", owner, NodeType.FOLDER, false, 0L, "LOCAL_ROOT", "LOCAL_ROOT");
    persist(parentB, "pb", owner, NodeType.FOLDER, false, 0L, "LOCAL_ROOT", "LOCAL_ROOT");
    persist(parentC, "pc", owner, NodeType.FOLDER, false, 0L, "LOCAL_ROOT", "LOCAL_ROOT");
    persist(inA, "a", owner, NodeType.TEXT, false, 1L, parentA, "LOCAL_ROOT," + parentA);
    persist(inB, "b", owner, NodeType.TEXT, false, 1L, parentB, "LOCAL_ROOT," + parentB);
    persist(inC, "c", owner, NodeType.TEXT, false, 1L, parentC, "LOCAL_ROOT," + parentC);
    entityManager.flush();
    nodeRepository.trashNode(inA, parentA);
    nodeRepository.trashNode(inB, parentB);
    nodeRepository.trashNode(inC, parentC);
    entityManager.flush();

    assertThat(nodeRepository.getTrashedNodeIdsByOldParents(List.of(parentA, parentB)))
        .containsExactlyInAnyOrder(inA, inB);
    assertThat(nodeRepository.getTrashedNodeIdsByOldParents(List.of())).isEmpty();
  }
}
