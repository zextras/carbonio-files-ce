// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.locks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.repositories.impl.Transactions;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.ShareRepository;
import com.zextras.carbonio.files.graphql.support.NodeCreationHelper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class SubtreePreLockDalTest {

  private static final String OWNER_A = "subtree-owner-a";
  private static final String OWNER_B = "subtree-owner-b";
  private static final String TARGET = "subtree-target";
  private static final String SHARE = "FOR SHARE";
  private static final String TAIL = "-0000-4000-8000-000000000000";

  @Inject NodeRepository nodeRepository;
  @Inject ShareRepository shareRepository;
  @Inject NodeLocks nodeLocks;
  @Inject NodeCreationHelper nodeCreationHelper;
  @Inject Transactions transactions;

  private final String subtree = "10000000" + TAIL;
  private final String folder2 = "a0000000" + TAIL;
  private final String folder1 = "b0000000" + TAIL;
  private final String bTop = "20000000" + TAIL;
  private final String bMiddle = "30000000" + TAIL;
  private final String x1 = "e1000000" + TAIL;
  private final String x2 = "f2000000" + TAIL;
  private final String w = "e5000000" + TAIL;

  @BeforeEach
  void createFixture() throws SQLException {
    create(subtree, OWNER_A, "LOCAL_ROOT", "LOCAL_ROOT");
    create(folder2, OWNER_A, subtree, "LOCAL_ROOT," + subtree);
    create(folder1, OWNER_A, subtree, "LOCAL_ROOT," + subtree);
    create(x2, OWNER_B, folder2, "LOCAL_ROOT," + subtree + "," + folder2);
    create(x1, OWNER_B, folder1, "LOCAL_ROOT," + subtree + "," + folder1);
    create(bTop, OWNER_B, "LOCAL_ROOT", "LOCAL_ROOT");
    create(bMiddle, OWNER_B, bTop, "LOCAL_ROOT," + bTop);
    create(w, OWNER_B, bMiddle, "LOCAL_ROOT," + bTop + "," + bMiddle);
    assertThat(heapOrder()).containsSubsequence(folder2, folder1).containsSubsequence(x2, x1);
  }

  private void create(String id, String owner, String parent, String ancestors) {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                nodeRepository.createNewNode(
                    id, owner, owner, parent, "n-" + id, "", NodeType.FOLDER, ancestors, 0L));
  }

  @AfterEach
  void deleteFixture() throws SQLException {
    try (Connection connection = LockProbe.holder();
        PreparedStatement statement =
            connection.prepareStatement("DELETE FROM node WHERE owner_id IN (?, ?)")) {
      statement.setString(1, OWNER_A);
      statement.setString(2, OWNER_B);
      statement.executeUpdate();
      connection.commit();
    }
  }

  private List<String> heapOrder() throws SQLException {
    return strings("SELECT trim(node_id) FROM node WHERE owner_id IN (?, ?) ORDER BY ctid");
  }

  private List<String> strings(String sql, String... extra) throws SQLException {
    try (Connection connection = LockProbe.holder();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, OWNER_A);
      statement.setString(2, OWNER_B);
      for (int i = 0; i < extra.length; i++) {
        statement.setString(3 + i, extra[i]);
      }
      List<String> values = new ArrayList<>();
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          values.add(rows.getString(1));
        }
      }
      return values;
    }
  }

  private List<String> sharedNodes() throws SQLException {
    try (Connection connection = LockProbe.holder();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT trim(node_id) FROM share WHERE target_uuid = ? AND NOT direct")) {
      statement.setString(1, TARGET);
      List<String> values = new ArrayList<>();
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          values.add(rows.getString(1));
        }
      }
      return values;
    }
  }

  private void trashSubtree() {
    trashRoots(List.of(subtree));
  }

  private void trashRoots(List<String> roots) {
    transactions.inNewTransaction(
        () -> {
          nodeLocks.lockForChange(roots);
          nodeLocks.lockSubtrees(
              roots, NodeLocks.SubtreeScope.DESCENDANTS, NodeLocks.SubtreeMode.NO_KEY_UPDATE);
          for (String root : roots) {
            Node node = nodeRepository.getNode(root).orElseThrow();
            node.setAncestorIds("TRASH_ROOT");
            node.setParentId("TRASH_ROOT");
            nodeRepository.trashNode(root, "LOCAL_ROOT");
            nodeRepository.updateNode(node);
            nodeRepository.rewriteDescendantAncestors(node);
          }
          return null;
        });
  }

  private void cascadeShare() {
    transactions.inNewTransaction(
        () -> {
          nodeLocks.lockForChange(List.of(subtree));
          shareRepository.cascadeUpsertInheritedShares(
              subtree, TARGET, ACL.decode(SharePermission.READ_ONLY), Optional.empty());
          return null;
        });
  }

  private void settle(CompletableFuture<?> first, CompletableFuture<?> second) {
    assertThatCode(() -> first.get(60, TimeUnit.SECONDS)).doesNotThrowAnyException();
    assertThatCode(() -> second.get(60, TimeUnit.SECONDS)).doesNotThrowAnyException();
  }

  @Test
  void hiddenFlagChangeAndTrashOfAMixedOwnerSubtreeDoNotDeadlock() throws Exception {
    CompletableFuture<Void> hidden;
    CompletableFuture<Void> trash;
    try (Connection sharer = LockProbe.holder()) {
      LockProbe.hold(sharer, w, SHARE);
      hidden =
          CompletableFuture.runAsync(
              () ->
                  transactions.inNewTransaction(
                      () -> nodeRepository.setHiddenForOwner(OWNER_B, true)));
      LockProbe.awaitBlockedBy(sharer, 1);
      trash = CompletableFuture.runAsync(this::trashSubtree);
      LockProbe.awaitBlockedBy(sharer, 2);
      sharer.commit();
    }

    settle(hidden, trash);
    assertThat(strings("SELECT trim(node_id) FROM node WHERE owner_id IN (?, ?) AND hidden"))
        .containsExactlyInAnyOrder(x1, x2, bTop, bMiddle, w);
    assertThat(
            strings(
                "SELECT trim(node_id) FROM node WHERE owner_id IN (?, ?) AND ancestor_ids LIKE"
                    + " 'TRASH_ROOT,%'"))
        .containsExactlyInAnyOrder(folder1, folder2, x1, x2);
  }

  @Test
  void accountDeletionAndTheShareCascadeOfAMixedOwnerSubtreeDoNotDeadlock() throws Exception {
    CompletableFuture<Void> deletion;
    CompletableFuture<Void> cascade;
    try (Connection sharer = LockProbe.holder()) {
      LockProbe.hold(sharer, w, SHARE);
      deletion =
          CompletableFuture.runAsync(
              () -> nodeCreationHelper.deleteAllNodesAndBlobsForUser(OWNER_B));
      LockProbe.awaitBlockedBy(sharer, 1);
      cascade = CompletableFuture.runAsync(this::cascadeShare);
      LockProbe.awaitBlockedBy(sharer, 2);
      sharer.commit();
    }

    settle(deletion, cascade);
    assertThat(strings("SELECT trim(node_id) FROM node WHERE owner_id IN (?, ?)"))
        .containsExactlyInAnyOrder(subtree, folder1, folder2);
    assertThat(sharedNodes()).containsExactlyInAnyOrder(folder1, folder2);
  }

  @Test
  void hiddenFlagChangeAndATwoRootTrashDoNotDeadlock() throws Exception {
    String first = "11000000" + TAIL;
    String second = "12000000" + TAIL;
    String middle = "13000000" + TAIL;
    String deep = "e7000000" + TAIL;
    String shallow = "e8000000" + TAIL;
    create(first, OWNER_A, "LOCAL_ROOT", "LOCAL_ROOT");
    create(second, OWNER_A, "LOCAL_ROOT", "LOCAL_ROOT");
    create(middle, OWNER_A, first, "LOCAL_ROOT," + first);
    create(deep, OWNER_B, middle, "LOCAL_ROOT," + first + "," + middle);
    create(shallow, OWNER_B, second, "LOCAL_ROOT," + second);

    CompletableFuture<Void> trash;
    try (Connection flagger = LockProbe.holder()) {
      LockProbe.hold(flagger, shallow, "FOR NO KEY UPDATE");
      trash = CompletableFuture.runAsync(() -> trashRoots(List.of(first, second)));
      LockProbe.awaitBlockedBy(flagger, 1);
      LockProbe.hold(flagger, deep, "FOR NO KEY UPDATE");
      flagger.commit();
    }

    assertThatCode(() -> trash.get(60, TimeUnit.SECONDS)).doesNotThrowAnyException();
    assertThat(
            strings(
                "SELECT trim(node_id) FROM node WHERE owner_id IN (?, ?) AND ancestor_ids LIKE"
                    + " 'TRASH_ROOT,%'"))
        .containsExactlyInAnyOrder(middle, deep, shallow);
  }
}
