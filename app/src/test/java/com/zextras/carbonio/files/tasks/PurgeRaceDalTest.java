// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.tasks;

import static org.assertj.core.api.Assertions.assertThat;

import com.zextras.carbonio.files.Constants.Db.RootId;
import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.dal.LockWaits;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.it.support.MockStoragesService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class PurgeRaceDalTest {

  private static final byte[] BYTES = {1, 2, 3};

  @Inject PurgeService purgeService;
  @Inject NodeRepository nodeRepository;
  @Inject FileVersionRepository fileVersionRepository;

  private final MockStoragesService storages = FilesStackTestResource.getStoragesService();
  private final List<String> seeded = new ArrayList<>();
  private final ExecutorService executor = Executors.newCachedThreadPool();

  private static Connection jdbc() throws SQLException {
    return DriverManager.getConnection(FilesStackTestResource.POSTGRES_JDBC_URL, "test", "test");
  }

  private static void execute(String sql, Object... params) throws SQLException {
    try (Connection connection = jdbc();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) {
        statement.setObject(i + 1, params[i]);
      }
      statement.execute();
    }
  }

  private String seedFolder(String parentId, String ancestors) {
    String nodeId = UUID.randomUUID().toString();
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                nodeRepository.createNewNode(
                    nodeId,
                    "u1",
                    "u1",
                    parentId,
                    "f-" + nodeId,
                    "d",
                    NodeType.FOLDER,
                    ancestors,
                    0L));
    seeded.add(nodeId);
    return nodeId;
  }

  private String seedFile(String parentId, String ancestors) {
    String nodeId = UUID.randomUUID().toString();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              nodeRepository.createNewNode(
                  nodeId, "u1", "u1", parentId, "n-" + nodeId, "d", NodeType.TEXT, ancestors, 1L);
              fileVersionRepository.createNewFileVersion(
                  nodeId, "u1", 1, "text/plain", 1L, "d", false);
            });
    storages.seed(nodeId, 1, BYTES);
    seeded.add(nodeId);
    return nodeId;
  }

  private void trash(String nodeId, String oldParentId, String ancestors, long updatedAt)
      throws SQLException {
    execute("INSERT INTO trashed (node_id, parent_id) VALUES (?, ?)", nodeId, oldParentId);
    execute(
        "UPDATE node SET folder_id = 'TRASH_ROOT', ancestor_ids = ?, updated_timestamp = ?"
            + " WHERE node_id = ?",
        ancestors,
        updatedAt,
        nodeId);
  }

  private String seedOldTrashedFile() throws SQLException {
    String nodeId = seedFile(RootId.LOCAL_ROOT, RootId.LOCAL_ROOT);
    trash(nodeId, RootId.LOCAL_ROOT, RootId.TRASH_ROOT, 0L);
    return nodeId;
  }

  private static long scalar(String sql, Object... params) throws SQLException {
    try (Connection connection = jdbc();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) {
        statement.setObject(i + 1, params[i]);
      }
      try (ResultSet rs = statement.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private CompletableFuture<Void> purgeAsync() {
    return CompletableFuture.runAsync(() -> purgeService.purgeTrashedNodes(30), executor);
  }

  @AfterEach
  void cleanup() throws SQLException {
    executor.shutdownNow();
    for (String nodeId : seeded) {
      execute("DELETE FROM tombstone WHERE node_id = ?", nodeId);
      execute("DELETE FROM node WHERE node_id = ?", nodeId);
    }
    seeded.clear();
  }

  @Test
  void givenANodeRestoredWhileThePurgeRunsThenItSurvives() throws Exception {
    String nodeId = seedOldTrashedFile();

    try (Connection holder = jdbc()) {
      holder.setAutoCommit(false);
      int holderPid = LockWaits.backendPid(holder);
      try (Statement statement = holder.createStatement()) {
        statement.execute("SELECT 1 FROM node WHERE node_id = '" + nodeId + "' FOR SHARE");
      }

      CompletableFuture<Void> purge = purgeAsync();
      LockWaits.awaitBlockedOrDone(1, List.of(holderPid), purge);

      try (Statement statement = holder.createStatement()) {
        statement.execute("DELETE FROM trashed WHERE node_id = '" + nodeId + "'");
        statement.execute(
            "UPDATE node SET folder_id = 'LOCAL_ROOT', ancestor_ids = 'LOCAL_ROOT',"
                + " updated_timestamp = "
                + System.currentTimeMillis()
                + " WHERE node_id = '"
                + nodeId
                + "'");
      }
      holder.commit();
      purge.get(60, TimeUnit.SECONDS);
    }

    assertThat(scalar("SELECT count(*) FROM node WHERE node_id = ?", nodeId)).isEqualTo(1);
    assertThat(scalar("SELECT count(*) FROM revision WHERE node_id = ?", nodeId)).isEqualTo(1);
    assertThat(storages.has(nodeId, 1)).isTrue();
  }

  @Test
  void givenTwoPurgesTogetherThenEachNodeIsDeletedOnceWithoutError() throws Exception {
    String nodeId = seedOldTrashedFile();

    try (Connection holder = jdbc()) {
      holder.setAutoCommit(false);
      int holderPid = LockWaits.backendPid(holder);
      try (Statement statement = holder.createStatement()) {
        statement.execute("SELECT 1 FROM node WHERE node_id = '" + nodeId + "' FOR SHARE");
      }

      CompletableFuture<Void> first = purgeAsync();
      CompletableFuture<Void> second = purgeAsync();
      LockWaits.awaitBlockedOrDone(2, List.of(holderPid), first, second);
      holder.commit();

      first.get(60, TimeUnit.SECONDS);
      second.get(60, TimeUnit.SECONDS);
    }

    assertThat(scalar("SELECT count(*) FROM node WHERE node_id = ?", nodeId)).isZero();
    boolean tombstoned =
        scalar("SELECT count(*) FROM tombstone WHERE node_id = ? AND version = 1", nodeId) == 1;
    assertThat(tombstoned || !storages.has(nodeId, 1)).isTrue();
  }

  @Test
  void givenATrashedFolderWhoseChildWasTouchedLaterThenTheWholeSubtreeIsPurgedTogether()
      throws Exception {
    String folderId = seedFolder(RootId.LOCAL_ROOT, RootId.LOCAL_ROOT);
    String childId = seedFile(folderId, RootId.LOCAL_ROOT + "," + folderId);
    trash(folderId, RootId.LOCAL_ROOT, RootId.TRASH_ROOT, 0L);
    execute(
        "UPDATE node SET ancestor_ids = ?, updated_timestamp = ? WHERE node_id = ?",
        RootId.TRASH_ROOT + "," + folderId,
        System.currentTimeMillis(),
        childId);

    purgeService.purgeTrashedNodes(30);

    assertThat(scalar("SELECT count(*) FROM node WHERE node_id = ?", folderId)).isZero();
    assertThat(scalar("SELECT count(*) FROM node WHERE node_id = ?", childId)).isZero();
    assertThat(storages.has(childId, 1)).isFalse();
  }

  @Test
  void givenAGhostTrashedNodeInANormalFolderThenItIsNotPurged() throws Exception {
    String folderId = seedFolder(RootId.LOCAL_ROOT, RootId.LOCAL_ROOT);
    String nodeId = seedFile(folderId, RootId.LOCAL_ROOT + "," + folderId);
    execute("INSERT INTO trashed (node_id, parent_id) VALUES (?, ?)", nodeId, folderId);
    execute("UPDATE node SET updated_timestamp = 0 WHERE node_id = ?", nodeId);

    purgeService.purgeTrashedNodes(30);

    assertThat(scalar("SELECT count(*) FROM node WHERE node_id = ?", nodeId)).isEqualTo(1);
    assertThat(scalar("SELECT count(*) FROM revision WHERE node_id = ?", nodeId)).isEqualTo(1);
    assertThat(storages.has(nodeId, 1)).isTrue();
  }
}
