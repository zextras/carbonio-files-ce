// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zextras.carbonio.files.Constants.Db.RootId;
import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.locks.LockKey;
import com.zextras.carbonio.files.dal.locks.NodeLocks;
import com.zextras.carbonio.files.dal.locks.NodeLocks.NodePlace;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class NodeLocksDalTest {

  private static final String LOCK_NOT_AVAILABLE = "55P03";

  @Inject NodeLocks nodeLocks;
  @Inject NodeRepository nodeRepository;

  private final List<String> seeded = new ArrayList<>();

  private static String id() {
    return UUID.randomUUID().toString();
  }

  private static Connection jdbc() throws SQLException {
    return DriverManager.getConnection(FilesStackTestResource.POSTGRES_JDBC_URL, "test", "test");
  }

  private String seed(String parentId, NodeType type) {
    String nodeId = id();
    String ancestors = RootId.LOCAL_ROOT.equals(parentId) ? parentId : "LOCAL_ROOT," + parentId;
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                nodeRepository.createNewNode(
                    nodeId, "u1", "u1", parentId, "n-" + nodeId, "d", type, ancestors, 0L));
    seeded.add(nodeId);
    return nodeId;
  }

  private static String tryLockNoWait(Connection connection, String nodeId) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "SELECT 1 FROM node WHERE node_id = '" + nodeId + "' FOR NO KEY UPDATE NOWAIT");
      return null;
    } catch (SQLException e) {
      return e.getSQLState();
    } finally {
      connection.rollback();
    }
  }

  @AfterEach
  void cleanup() throws SQLException {
    try (Connection connection = jdbc();
        Statement statement = connection.createStatement()) {
      for (String nodeId : seeded) {
        statement.execute("DELETE FROM trashed WHERE node_id = '" + nodeId + "'");
        statement.execute("DELETE FROM node WHERE node_id = '" + nodeId + "'");
      }
    }
    seeded.clear();
  }

  @Test
  void lockOutsideATransactionFails() {
    String a = seed(RootId.LOCAL_ROOT, NodeType.FOLDER);

    assertThatThrownBy(() -> nodeLocks.lock(List.of(LockKey.node(a))))
        .isInstanceOf(jakarta.transaction.TransactionalException.class);
  }

  @Test
  void lockReturnsOnlyExistingNodesAndNeverLocksRoots() {
    String a = seed(RootId.LOCAL_ROOT, NodeType.FOLDER);

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Set<String> locked =
                  nodeLocks.lock(
                      List.of(
                          LockKey.node(a),
                          LockKey.node(id()),
                          LockKey.node(RootId.LOCAL_ROOT),
                          LockKey.children(RootId.LOCAL_ROOT, "u1")));
              assertThat(locked).containsExactly(a);
              try (Connection connection = jdbc()) {
                connection.setAutoCommit(false);
                assertThat(tryLockNoWait(connection, RootId.LOCAL_ROOT)).isNull();
                assertThat(tryLockNoWait(connection, a)).isEqualTo(LOCK_NOT_AVAILABLE);
              } catch (SQLException e) {
                throw new IllegalStateException(e);
              }
            });
  }

  @Test
  void lockAfterAWriteFails() {
    String a = seed(RootId.LOCAL_ROOT, NodeType.FOLDER);

    assertThatThrownBy(
            () ->
                QuarkusTransaction.requiringNew()
                    .run(
                        () -> {
                          Node node = nodeRepository.getNode(a).orElseThrow();
                          node.setDescription("x");
                          nodeLocks.lock(List.of(LockKey.node(a)));
                        }))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void lockWaitsAtMostTheTimeout() throws SQLException {
    String a = seed(RootId.LOCAL_ROOT, NodeType.FOLDER);

    try (Connection holder = jdbc()) {
      holder.setAutoCommit(false);
      try (Statement statement = holder.createStatement()) {
        statement.execute("SELECT 1 FROM node WHERE node_id = '" + a + "' FOR UPDATE");
        assertThatThrownBy(
                () ->
                    QuarkusTransaction.requiringNew()
                        .run(() -> nodeLocks.lock(List.of(LockKey.node(a)))))
            .satisfies(
                e -> {
                  Throwable cause = e;
                  String state = null;
                  while (cause != null && state == null) {
                    if (cause instanceof SQLException sqlException) {
                      state = sqlException.getSQLState();
                    }
                    cause = cause.getCause();
                  }
                  assertThat(state).isEqualTo(LOCK_NOT_AVAILABLE);
                });
      } finally {
        holder.rollback();
      }
    }
  }

  @Test
  void rootChildrenKeysAreAdvisoryPerOwner() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              nodeLocks.lock(List.of(LockKey.children(RootId.LOCAL_ROOT, "u1")));
              try (Connection connection = jdbc()) {
                connection.setAutoCommit(false);
                assertThat(tryAdvisory(connection, "u1")).isFalse();
                assertThat(tryAdvisory(connection, "u2")).isTrue();
                connection.rollback();
              } catch (SQLException e) {
                throw new IllegalStateException(e);
              }
            });
  }

  private static boolean tryAdvisory(Connection connection, String owner) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT pg_try_advisory_xact_lock(hashtextextended(?, 0))"); ) {
      statement.setString(1, "files-children/LOCAL_ROOT/" + owner);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getBoolean(1);
      }
    }
  }

  @Test
  void lockClearsThePersistenceContext() {
    String a = seed(RootId.LOCAL_ROOT, NodeType.FOLDER);

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              nodeRepository.getNode(a).orElseThrow();
              try (Connection connection = jdbc();
                  Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                    "UPDATE node SET name = 'renamed' WHERE node_id = '" + a + "'");
              } catch (SQLException e) {
                throw new IllegalStateException(e);
              }
              nodeLocks.lock(List.of(LockKey.node(a)));
              assertThat(nodeRepository.getNode(a).orElseThrow().getFullName())
                  .isEqualTo("renamed");
            });
  }

  @Test
  void lockChildFoldersLocksOnlyFolderChildren() {
    String parent = seed(RootId.LOCAL_ROOT, NodeType.FOLDER);
    String folder = seed(parent, NodeType.FOLDER);
    String file = seed(parent, NodeType.TEXT);

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              assertThat(nodeLocks.lockChildFolders(List.of(parent))).containsExactly(folder);
              try (Connection connection = jdbc()) {
                connection.setAutoCommit(false);
                assertThat(tryLockNoWait(connection, folder)).isEqualTo(LOCK_NOT_AVAILABLE);
                assertThat(tryLockNoWait(connection, file)).isNull();
              } catch (SQLException e) {
                throw new IllegalStateException(e);
              }
            });
  }

  @Test
  void placesAndTrashedParentsAreReadWithoutLocking() {
    String parent = seed(RootId.LOCAL_ROOT, NodeType.FOLDER);
    String a = seed(parent, NodeType.FOLDER);
    String trashed = seed(parent, NodeType.TEXT);
    QuarkusTransaction.requiringNew().run(() -> nodeRepository.trashNode(trashed, parent));

    assertThat(nodeLocks.placesOf(List.of(a))).containsExactly(new NodePlace(a, parent, "u1"));
    assertThat(nodeLocks.trashedParentsOf(List.of(trashed, a))).isEqualTo(Map.of(trashed, parent));
  }
}
