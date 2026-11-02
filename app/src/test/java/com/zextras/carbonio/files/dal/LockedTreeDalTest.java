// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal;

import static org.assertj.core.api.Assertions.assertThat;

import com.zextras.carbonio.files.Constants.Db.RootId;
import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.locks.LockedTree;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class LockedTreeDalTest {

  private static final String LOCK_NOT_AVAILABLE = "55P03";

  @Inject LockedTree lockedTree;
  @Inject NodeRepository nodeRepository;

  private final List<String> seeded = new ArrayList<>();

  private static Connection jdbc() throws SQLException {
    return DriverManager.getConnection(FilesStackTestResource.POSTGRES_JDBC_URL, "test", "test");
  }

  private String seed(String parentId, String ancestors, NodeType type) {
    String nodeId = UUID.randomUUID().toString();
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
      for (String nodeId : seeded.reversed()) {
        statement.execute("DELETE FROM node WHERE node_id = '" + nodeId + "'");
      }
    }
    seeded.clear();
  }

  @Test
  void collectReturnsAThreeLevelTreeAndLocksEveryNonRootRow() {
    String root = seed(RootId.LOCAL_ROOT, RootId.LOCAL_ROOT, NodeType.FOLDER);
    String rootFile = seed(root, "LOCAL_ROOT," + root, NodeType.TEXT);
    String level1 = seed(root, "LOCAL_ROOT," + root, NodeType.FOLDER);
    String level1File = seed(level1, "LOCAL_ROOT," + root + "," + level1, NodeType.TEXT);
    String level2 = seed(level1, "LOCAL_ROOT," + root + "," + level1, NodeType.FOLDER);
    String level2File =
        seed(level2, "LOCAL_ROOT," + root + "," + level1 + "," + level2, NodeType.TEXT);
    String unrelated = seed(RootId.LOCAL_ROOT, RootId.LOCAL_ROOT, NodeType.FOLDER);

    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              List<Node> roots = List.of(nodeRepository.getNode(root).orElseThrow());
              List<Node> collected = lockedTree.collect(roots);

              assertThat(collected.stream().map(Node::getId))
                  .containsExactlyInAnyOrder(
                      root, rootFile, level1, level1File, level2, level2File);
              try (Connection connection = jdbc()) {
                connection.setAutoCommit(false);
                for (String locked : List.of(rootFile, level1, level1File, level2, level2File)) {
                  assertThat(tryLockNoWait(connection, locked)).isEqualTo(LOCK_NOT_AVAILABLE);
                }
                assertThat(tryLockNoWait(connection, unrelated)).isNull();
              } catch (SQLException e) {
                throw new IllegalStateException(e);
              }
            });
  }
}
