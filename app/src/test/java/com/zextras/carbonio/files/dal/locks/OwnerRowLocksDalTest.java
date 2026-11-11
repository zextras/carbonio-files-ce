// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.locks;

import static org.assertj.core.api.Assertions.assertThat;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.repositories.impl.Transactions;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.graphql.support.NodeCreationHelper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class OwnerRowLocksDalTest {

  private static final String OWNER = "owner-row-locks";
  private static final String SHARE = "FOR SHARE";
  private static final String UPDATE = "FOR UPDATE";

  @Inject NodeRepository nodeRepository;
  @Inject NodeCreationHelper nodeCreationHelper;
  @Inject Transactions transactions;

  private String folder;
  private String child;

  @BeforeEach
  void createFixture() throws SQLException {
    folder = UUID.randomUUID().toString();
    child = UUID.randomUUID().toString();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              nodeRepository.createNewNode(
                  folder,
                  OWNER,
                  OWNER,
                  "LOCAL_ROOT",
                  "n-" + folder,
                  "",
                  NodeType.FOLDER,
                  "LOCAL_ROOT",
                  0L);
              nodeRepository.createNewNode(
                  child,
                  OWNER,
                  OWNER,
                  folder,
                  "n-" + child,
                  "",
                  NodeType.FOLDER,
                  "LOCAL_ROOT," + folder,
                  0L);
            });
    for (int attempt = 0; attempt < 50 && !scanOrder().equals(List.of(child, folder)); attempt++) {
      try (Connection connection = LockProbe.holder()) {
        connection.setAutoCommit(true);
        try (PreparedStatement statement =
            connection.prepareStatement(
                "UPDATE node SET updated_timestamp = updated_timestamp + 1 WHERE node_id = ?")) {
          statement.setString(1, folder);
          statement.executeUpdate();
        }
      }
    }
    assertThat(scanOrder()).containsExactly(child, folder);
  }

  @AfterEach
  void deleteFixture() throws SQLException {
    try (Connection connection = LockProbe.holder();
        PreparedStatement statement =
            connection.prepareStatement("DELETE FROM node WHERE owner_id = ?")) {
      statement.setString(1, OWNER);
      statement.executeUpdate();
      connection.commit();
    }
  }

  private java.util.List<String> scanOrder() throws SQLException {
    try (Connection connection = LockProbe.holder();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT trim(node_id) FROM node WHERE owner_id = ? ORDER BY ctid")) {
      statement.setString(1, OWNER);
      java.util.List<String> ids = new java.util.ArrayList<>();
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          ids.add(rows.getString(1));
        }
      }
      return ids;
    }
  }

  private long countOwned(String extraWhere) throws SQLException {
    try (Connection connection = LockProbe.holder();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT count(*) FROM node WHERE owner_id = ? " + extraWhere)) {
      statement.setString(1, OWNER);
      try (ResultSet rows = statement.executeQuery()) {
        rows.next();
        return rows.getLong(1);
      }
    }
  }

  private void assertWriterLocksInDepthOrder(Runnable writer) throws Exception {
    try (Connection sharee = LockProbe.holder()) {
      LockProbe.hold(sharee, folder, SHARE);
      CompletableFuture<Void> future = CompletableFuture.runAsync(writer);
      try {
        LockProbe.awaitBlockedBy(sharee);

        assertThat(LockProbe.canLockNow(child, UPDATE)).isTrue();
      } finally {
        sharee.commit();
      }
      future.get(30, TimeUnit.SECONDS);
    }
  }

  @Test
  void accountDeletionWaitsOnTheShallowAncestorBeforeLockingTheDeeperNode() throws Exception {
    assertWriterLocksInDepthOrder(() -> nodeCreationHelper.deleteAllNodesAndBlobsForUser(OWNER));

    assertThat(countOwned("")).isZero();
  }

  @Test
  void hiddenFlagUpdateWaitsOnTheShallowAncestorBeforeLockingTheDeeperNode() throws Exception {
    assertWriterLocksInDepthOrder(
        () -> transactions.inNewTransaction(() -> nodeRepository.setHiddenForOwner(OWNER, true)));

    assertThat(countOwned("AND hidden")).isEqualTo(2);
  }
}
