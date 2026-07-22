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
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.NodeSort;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.junit.jupiter.api.Test;

/**
 * P2c: validates the clean Panache/Hibernate {@link NodeRepository} implementation ({@code
 * NodeRepositoryImpl}) that replaces the legacy Ebean {@code NodeRepositoryEbean}. Uses a real
 * Postgres Testcontainer (via {@link FilesStackTestResource}). Every test runs inside a rolled-back
 * transaction ({@link TestTransaction}); {@code findNodes}/folder-size tests key off freshly
 * generated owner ids so pre-existing rows never leak into assertions.
 */
@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class NodeRepositoryIT {

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

  private ImmutablePair<List<Node>, String> find(
      String user, Optional<NodeSort> sort, Optional<Integer> limit, Optional<String> token) {
    return nodeRepository.findNodes(
        user,
        sort,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        limit,
        Optional.empty(),
        Optional.empty(),
        List.of(),
        token);
  }

  // ------------------------------------------------------------------------------------------- CRUD

  @Test
  @TestTransaction
  void createAndGetNodeRoundTrips() {
    String nodeId = id();
    nodeRepository.createNewNode(
        nodeId, "creator", "owner", "LOCAL_ROOT", "my-folder", "desc", NodeType.FOLDER, "LOCAL_ROOT",
        0L);

    Optional<Node> found = nodeRepository.getNode(nodeId);
    assertThat(found).isPresent();
    assertThat(found.get().getId()).isEqualTo(nodeId);
    assertThat(found.get().getFullName()).isEqualTo("my-folder");
    assertThat(nodeRepository.getNode(id())).isEmpty();
  }

  @Test
  @TestTransaction
  void getNodeByNameFiltersByOwner() {
    String nodeId = id();
    nodeRepository.createNewNode(
        nodeId, "creator", "owner-x", "LOCAL_ROOT", "report", "desc", NodeType.FOLDER, "LOCAL_ROOT",
        0L);

    assertThat(nodeRepository.getNodeByName("report", "LOCAL_ROOT", "owner-x")).isPresent();
    assertThat(nodeRepository.getNodeByName("report", "LOCAL_ROOT", "someone-else")).isEmpty();
  }

  @Test
  @TestTransaction
  void updateNodeBumpsUpdatedTimestampAndPersistsChanges() {
    String nodeId = id();
    Node node =
        nodeRepository.createNewNode(
            nodeId, "creator", "owner", "LOCAL_ROOT", "before", "desc", NodeType.FOLDER,
            "LOCAL_ROOT", 0L);
    long originalUpdatedAt = node.getUpdatedAt();

    node.setFullName("after");
    nodeRepository.updateNode(node);

    Node reloaded = nodeRepository.getNode(nodeId).orElseThrow();
    assertThat(reloaded.getFullName()).isEqualTo("after");
    assertThat(reloaded.getUpdatedAt()).isGreaterThanOrEqualTo(originalUpdatedAt);
  }

  @Test
  @TestTransaction
  void deleteNodeReturnsTrueWhenPresentFalseOtherwise() {
    String nodeId = id();
    nodeRepository.createNewNode(
        nodeId, "creator", "owner", "LOCAL_ROOT", "doomed", "desc", NodeType.FOLDER, "LOCAL_ROOT",
        0L);

    assertThat(nodeRepository.deleteNode(nodeId)).isTrue();
    assertThat(nodeRepository.getNode(nodeId)).isEmpty();
    assertThat(nodeRepository.deleteNode(id())).isFalse();
  }

  @Test
  @TestTransaction
  void deleteNodesBulkRemovesEveryGivenNode() {
    String keep = id();
    String drop1 = id();
    String drop2 = id();
    for (String nodeId : List.of(keep, drop1, drop2)) {
      nodeRepository.createNewNode(
          nodeId, "creator", "owner", "LOCAL_ROOT", "n-" + nodeId, "desc", NodeType.FOLDER,
          "LOCAL_ROOT", 0L);
    }

    int deleted = nodeRepository.deleteNodes(List.of(drop1, drop2));

    assertThat(deleted).isEqualTo(2);
    assertThat(nodeRepository.getNode(drop1)).isEmpty();
    assertThat(nodeRepository.getNode(drop2)).isEmpty();
    assertThat(nodeRepository.getNode(keep)).isPresent();
  }

  // Regression for the dead @ManyToOne "node" shadow field on NodeCustomAttributes: before the fix,
  // removing a node that had a flag row loaded in the same persistence context made Hibernate's
  // flush-time transient-reference check throw TransientPropertyValueException instead of letting
  // the DB-level ON DELETE CASCADE clean up the custom-attributes row.
  @Test
  @TestTransaction
  void deleteNodesRemovesAFlaggedNodeWithoutThrowing() {
    String nodeId = id();
    String userId = id();
    nodeRepository.createNewNode(
        nodeId, "creator", "owner", "LOCAL_ROOT", "flagged", "desc", NodeType.FOLDER,
        "LOCAL_ROOT", 0L);

    nodeRepository.flagForUser(nodeId, userId, true);
    // Reads the flag row back into the same persistence context — this is what previously
    // populated the shadow association and triggered the flush-time failure on delete.
    assertThat(nodeRepository.isFlaggedForUser(nodeId, userId)).isTrue();

    int deleted = nodeRepository.deleteNodes(List.of(nodeId));

    assertThat(deleted).isEqualTo(1);
    assertThat(nodeRepository.getNode(nodeId)).isEmpty();
  }

  @Test
  @TestTransaction
  void getNodeForUpdateReturnsLockedNode() {
    String nodeId = id();
    nodeRepository.createNewNode(
        nodeId, "creator", "owner", "LOCAL_ROOT", "lockme", "desc", NodeType.FOLDER, "LOCAL_ROOT",
        0L);

    assertThat(nodeRepository.getNodeForUpdate(nodeId)).isPresent();
    assertThat(nodeRepository.getNodeForUpdate(id())).isEmpty();
  }

  // ------------------------------------------------------------------------------ batch / children

  @Test
  @TestTransaction
  void getNodesBatchAppliesSort() {
    String owner = id();
    String a = id();
    String b = id();
    String c = id();
    persist(a, "a-file", owner, NodeType.TEXT, false, 1L, "LOCAL_ROOT", "LOCAL_ROOT");
    persist(b, "b-file", owner, NodeType.TEXT, false, 1L, "LOCAL_ROOT", "LOCAL_ROOT");
    persist(c, "c-file", owner, NodeType.TEXT, false, 1L, "LOCAL_ROOT", "LOCAL_ROOT");
    entityManager.flush();

    List<Node> ascending =
        nodeRepository.getNodes(List.of(c, a, b), Optional.of(NodeSort.NAME_ASC)).toList();
    List<Node> descending =
        nodeRepository.getNodes(List.of(a, b, c), Optional.of(NodeSort.NAME_DESC)).toList();

    assertThat(ascending).extracting(Node::getFullName).containsExactly("a-file", "b-file", "c-file");
    assertThat(descending)
        .extracting(Node::getFullName)
        .containsExactly("c-file", "b-file", "a-file");
  }

  @Test
  @TestTransaction
  void getChildrenIdsReturnsChildrenAndHonoursShowMarked() {
    String folderId = id();
    persist(folderId, "parent", "owner", NodeType.FOLDER, false, 0L, "LOCAL_ROOT", "LOCAL_ROOT");
    String child1 = id();
    String child2 = id();
    String child3 = id();
    String ancestors = "LOCAL_ROOT," + folderId;
    persist(child1, "c-1", "owner", NodeType.TEXT, false, 1L, folderId, ancestors);
    persist(child2, "c-2", "owner", NodeType.TEXT, false, 1L, folderId, ancestors);
    persist(child3, "c-3", "owner", NodeType.TEXT, false, 1L, folderId, ancestors);
    entityManager.flush();

    List<String> all =
        nodeRepository.getChildrenIds(folderId, Optional.of(NodeSort.NAME_ASC), Optional.empty(), true);
    assertThat(all).containsExactly(child1, child2, child3);

    nodeRepository.trashNode(child2, folderId);
    entityManager.flush();

    List<String> visible =
        nodeRepository.getChildrenIds(
            folderId, Optional.of(NodeSort.NAME_ASC), Optional.empty(), false);
    List<String> marked =
        nodeRepository.getChildrenIds(
            folderId, Optional.of(NodeSort.NAME_ASC), Optional.empty(), true);

    assertThat(visible).containsExactly(child1, child3);
    assertThat(marked).containsExactly(child1, child2, child3);
  }

  @Test
  @TestTransaction
  void getRootsListContainsTheTwoBootstrapRoots() {
    assertThat(nodeRepository.getRootsList())
        .extracting(Node::getId)
        .contains("LOCAL_ROOT", "TRASH_ROOT");
  }

  @Test
  @TestTransaction
  void findAllNodesFilesExcludesFoldersAndRoots() {
    String owner = id();
    String folderId = id();
    String fileId = id();
    persist(folderId, "a-folder", owner, NodeType.FOLDER, false, 0L, "LOCAL_ROOT", "LOCAL_ROOT");
    persist(fileId, "a-file", owner, NodeType.TEXT, false, 1L, "LOCAL_ROOT", "LOCAL_ROOT");
    entityManager.flush();

    List<String> ids = nodeRepository.findAllNodesFiles().stream().map(Node::getId).toList();

    assertThat(ids).contains(fileId);
    assertThat(ids).doesNotContain(folderId, "LOCAL_ROOT", "TRASH_ROOT");
  }

  // -------------------------------------------------------------------------------------- move

  @Test
  @TestTransaction
  void moveNodesUpdatesParentAndAncestors() {
    String destId = id();
    Node dest =
        nodeRepository.createNewNode(
            destId, "creator", "owner", "LOCAL_ROOT", "dest", "desc", NodeType.FOLDER, "LOCAL_ROOT",
            0L);
    String movedA = id();
    String movedB = id();
    persist(movedA, "m-a", "owner", NodeType.TEXT, false, 1L, "LOCAL_ROOT", "LOCAL_ROOT");
    persist(movedB, "m-b", "owner", NodeType.TEXT, false, 1L, "LOCAL_ROOT", "LOCAL_ROOT");
    entityManager.flush();

    int moved = nodeRepository.moveNodes(List.of(movedA, movedB), dest);

    assertThat(moved).isEqualTo(2);
    Node reloaded = nodeRepository.getNode(movedA).orElseThrow();
    assertThat(reloaded.getParentId()).contains(destId);
    assertThat(reloaded.getAncestorIds()).isEqualTo("LOCAL_ROOT," + destId);
  }

  // -------------------------------------------------------------------------------- trash / restore

  @Test
  @TestTransaction
  void trashNodeAndRestoreNodeManageTheTrashedTable() {
    String nodeId = id();
    String parentId = id();
    persist(nodeId, "trashable", "owner", NodeType.TEXT, false, 1L, parentId, "LOCAL_ROOT");
    entityManager.flush();

    nodeRepository.trashNode(nodeId, parentId);
    entityManager.flush();

    assertThat(nodeRepository.getTrashedNode(nodeId)).isPresent();
    assertThat(nodeRepository.getTrashedNodeIdsByOldParent(parentId)).contains(nodeId);

    nodeRepository.restoreNode(nodeId);
    entityManager.flush();

    assertThat(nodeRepository.getTrashedNode(nodeId)).isEmpty();
    assertThat(nodeRepository.getTrashedNodeIdsByOldParent(parentId)).doesNotContain(nodeId);
  }

  @Test
  @TestTransaction
  void retentionCleanupRemovesOldTrashSubtreeNodes() {
    String owner = id();
    String nodeId = id();
    // Old node living under the trash root (ancestor path contains TRASH_ROOT) with a very old
    // updated timestamp.
    Node oldTrashed =
        new Node(
            nodeId, owner, owner, "TRASH_ROOT", 1_000L, 1_000L, "old-trash", "desc", NodeType.TEXT,
            "TRASH_ROOT," + nodeId, 1L);
    entityManager.persist(oldTrashed);
    entityManager.flush();

    long retention = System.currentTimeMillis();
    assertThat(nodeRepository.getAllTrashedNodes(retention)).extracting(Node::getId).contains(nodeId);

    int removed = nodeRepository.deleteTrashedNodesOlderThan(retention);
    entityManager.flush();

    assertThat(removed).isGreaterThanOrEqualTo(1);
    assertThat(nodeRepository.getNode(nodeId)).isEmpty();
  }

  // --------------------------------------------------------------------------------------- flag

  @Test
  @TestTransaction
  void flagForUserUpsertsAndIsFlaggedReadsBack() {
    String nodeId = id();
    persist(nodeId, "flaggable", "owner", NodeType.TEXT, false, 1L, "LOCAL_ROOT", "LOCAL_ROOT");
    entityManager.flush();

    assertThat(nodeRepository.isFlaggedForUser(nodeId, "user-1")).isFalse();

    nodeRepository.flagForUser(nodeId, "user-1", true);
    assertThat(nodeRepository.isFlaggedForUser(nodeId, "user-1")).isTrue();
    // Another user is unaffected.
    assertThat(nodeRepository.isFlaggedForUser(nodeId, "user-2")).isFalse();

    nodeRepository.flagForUser(nodeId, "user-1", false);
    assertThat(nodeRepository.isFlaggedForUser(nodeId, "user-1")).isFalse();
  }

  @Test
  @TestTransaction
  void invertHiddenFlagTogglesTheHiddenColumn() {
    String nodeId = id();
    Node node =
        persist(nodeId, "visible", "owner", NodeType.TEXT, false, 1L, "LOCAL_ROOT", "LOCAL_ROOT");
    entityManager.flush();
    assertThat(node.isHidden()).isFalse();

    nodeRepository.invertHiddenFlagNodes(List.of(node));

    assertThat(nodeRepository.getNode(nodeId).orElseThrow().isHidden()).isTrue();
  }

  // ---------------------------------------------------------------------------------- folder size

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

  // ---------------------------------------------------------------------------- findNodes paging

  @Test
  @TestTransaction
  void findNodesPagesThroughAllVisibleNodesWithoutOverlap() {
    String requester = id();
    String otherOwner = id();

    // 4 files owned by the requester.
    List<String> ownedNames = List.of("f-1", "f-2", "f-3", "f-4");
    for (String name : ownedNames) {
      persist(id(), name, requester, NodeType.TEXT, false, 1L, "LOCAL_ROOT", "LOCAL_ROOT");
    }
    // 1 file owned by someone else but shared with the requester -> visible.
    String sharedId = id();
    persist(sharedId, "f-shared", otherOwner, NodeType.TEXT, false, 1L, "LOCAL_ROOT", "LOCAL_ROOT");
    shareTo(sharedId, requester);
    // Excluded: hidden (owned by requester) and a stranger's private file.
    persist(id(), "f-hidden", requester, NodeType.TEXT, true, 1L, "LOCAL_ROOT", "LOCAL_ROOT");
    persist(id(), "f-stranger", otherOwner, NodeType.TEXT, false, 1L, "LOCAL_ROOT", "LOCAL_ROOT");
    entityManager.flush();

    List<Node> collected = new ArrayList<>();
    Optional<String> token = Optional.empty();
    int guard = 0;
    do {
      ImmutablePair<List<Node>, String> page =
          find(requester, Optional.of(NodeSort.NAME_ASC), Optional.of(2), token);
      assertThat(page.getLeft().size()).isLessThanOrEqualTo(2);
      collected.addAll(page.getLeft());
      token = Optional.ofNullable(page.getRight());
      guard++;
    } while (token.isPresent() && guard < 20);

    assertThat(guard).isLessThan(20); // pagination terminated
    // Complete, correctly ordered, no overlap:
    assertThat(collected)
        .extracting(Node::getFullName)
        .containsExactly("f-1", "f-2", "f-3", "f-4", "f-shared");
    assertThat(collected).extracting(Node::getId).doesNotHaveDuplicates();
    // Hidden and non-visible nodes never surface; roots are excluded.
    assertThat(collected).extracting(Node::getFullName).doesNotContain("f-hidden", "f-stranger");
    assertThat(collected)
        .allSatisfy(node -> assertThat(node.getNodeCategory().getValue()).isNotEqualTo((short) 0));
  }

  @Test
  @TestTransaction
  void findNodesScopedToAFolderSubtreeWithCascade() {
    String requester = id();
    String folderId = id();
    persist(folderId, "scope", requester, NodeType.FOLDER, false, 0L, "LOCAL_ROOT", "LOCAL_ROOT");
    String inside = id();
    String outside = id();
    persist(inside, "inside", requester, NodeType.TEXT, false, 1L, folderId, "LOCAL_ROOT," + folderId);
    persist(outside, "outside", requester, NodeType.TEXT, false, 1L, "LOCAL_ROOT", "LOCAL_ROOT");
    entityManager.flush();

    ImmutablePair<List<Node>, String> page =
        nodeRepository.findNodes(
            requester,
            Optional.of(NodeSort.NAME_ASC),
            Optional.empty(),
            Optional.of(folderId),
            Optional.of(true),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.of(50),
            Optional.empty(),
            Optional.empty(),
            List.of(),
            Optional.empty());

    assertThat(page.getLeft()).extracting(Node::getId).contains(inside).doesNotContain(outside);
  }
}
