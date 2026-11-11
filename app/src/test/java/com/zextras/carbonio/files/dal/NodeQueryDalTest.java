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

  private Node persistAt(
      String id, NodeType type, String parent, String ancestors, long updatedAt) {
    String owner = "owner";
    Node node =
        new Node(
            id, owner, owner, parent, updatedAt, updatedAt, "n-" + id, "d", type, ancestors, 0L);
    entityManager.persist(node);
    return node;
  }

  @Test
  @TestTransaction
  void getTrashedTopNodesReturnsOldTopsOnlyAndNeverTheirChildren() {
    long old = System.currentTimeMillis() - 40L * 86400 * 1000;
    long retention = System.currentTimeMillis() - 30L * 86400 * 1000;
    String oldTop = id();
    String recentChild = id();
    String recentTop = id();
    String strayDescendant = id();
    persistAt(oldTop, NodeType.FOLDER, "TRASH_ROOT", "TRASH_ROOT", old);
    persistAt(
        recentChild, NodeType.TEXT, oldTop, "TRASH_ROOT," + oldTop, System.currentTimeMillis());
    persistAt(recentTop, NodeType.FOLDER, "TRASH_ROOT", "TRASH_ROOT", System.currentTimeMillis());
    persistAt(strayDescendant, NodeType.TEXT, oldTop, "TRASH_ROOT," + oldTop, old);
    entityManager.flush();
    entityManager.clear();

    assertThat(nodeRepository.getTrashedTopNodes(retention))
        .extracting(Node::getId)
        .contains(oldTop)
        .doesNotContain(recentChild, recentTop, strayDescendant);
  }

  @Test
  @TestTransaction
  void getFileIdsInSubtreeReturnsTheFilesAtAnyDepth() {
    long now = System.currentTimeMillis();
    String root = id();
    String sub = id();
    String file = id();
    String deepFile = id();
    String outsideFile = id();
    persistAt(root, NodeType.FOLDER, "TRASH_ROOT", "TRASH_ROOT", now);
    persistAt(sub, NodeType.FOLDER, root, "TRASH_ROOT," + root, now);
    persistAt(file, NodeType.TEXT, root, "TRASH_ROOT," + root, now);
    persistAt(deepFile, NodeType.TEXT, sub, "TRASH_ROOT," + root + "," + sub, now);
    persistAt(outsideFile, NodeType.TEXT, "TRASH_ROOT", "TRASH_ROOT", now);
    entityManager.flush();
    entityManager.clear();

    assertThat(nodeRepository.getFileIdsInSubtree(root)).containsExactlyInAnyOrder(file, deepFile);
    assertThat(nodeRepository.getFileIdsInSubtree(file)).containsExactly(file);
  }

  @Test
  @TestTransaction
  void filterIdsUnderAncestorKeepsOnlyTheNodesWithTheTopAmongTheirAncestors() {
    long now = System.currentTimeMillis();
    String top = id();
    String inside = id();
    String moved = id();
    persistAt(top, NodeType.FOLDER, "TRASH_ROOT", "TRASH_ROOT", now);
    persistAt(inside, NodeType.TEXT, top, "TRASH_ROOT," + top, now);
    persistAt(moved, NodeType.TEXT, "LOCAL_ROOT", "LOCAL_ROOT", now);
    entityManager.flush();

    assertThat(nodeRepository.filterIdsUnderAncestor(top, List.of(inside, moved, top)))
        .containsExactly(inside);
    assertThat(nodeRepository.filterIdsUnderAncestor(top, List.of())).isEmpty();
  }

  @Test
  @TestTransaction
  void getTopMostIdsByOwnerReturnsOnlyTheNodesWhoseParentBelongsToSomeoneElse() {
    String user = id();
    String other = id();
    String top = id();
    String child = id();
    String deep = id();
    String foreignFolder = id();
    String insideForeign = id();
    String foreignInsideMine = id();
    String hidden = id();
    persist(top, "top", user, NodeType.FOLDER, false, 0, "LOCAL_ROOT", "LOCAL_ROOT");
    persist(child, "child", user, NodeType.FOLDER, false, 0, top, "LOCAL_ROOT," + top);
    persist(deep, "deep", user, NodeType.TEXT, false, 1, child, "LOCAL_ROOT," + top + "," + child);
    persist(foreignFolder, "foreign", other, NodeType.FOLDER, false, 0, "LOCAL_ROOT", "LOCAL_ROOT");
    persist(
        insideForeign,
        "inside",
        user,
        NodeType.TEXT,
        false,
        1,
        foreignFolder,
        "LOCAL_ROOT," + foreignFolder);
    persist(foreignInsideMine, "theirs", other, NodeType.TEXT, false, 1, top, "LOCAL_ROOT," + top);
    persist(hidden, "hidden", user, NodeType.TEXT, true, 1, "TRASH_ROOT", "TRASH_ROOT");
    entityManager.flush();
    entityManager.clear();

    assertThat(nodeRepository.getTopMostIdsByOwner(user))
        .containsExactlyInAnyOrder(top, insideForeign, hidden);
    assertThat(nodeRepository.getTopMostIdsByOwner(other))
        .containsExactlyInAnyOrder(foreignFolder, foreignInsideMine);
  }
}
