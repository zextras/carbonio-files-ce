// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.tasks;

import static org.assertj.core.api.Assertions.assertThat;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.dal.dao.ebean.FileVersion;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.dao.ebean.TrashedNode;
import com.zextras.carbonio.files.dal.locks.LockProbe;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class PurgeLocksDalTest {

  private static final String OWNER = "11111111-1111-1111-1111-111111111111";
  private static final long THIRTY_ONE_DAYS_AGO = System.currentTimeMillis() - 31L * 86400 * 1000;

  @Inject PurgeService purgeService;
  @Inject EntityManager entityManager;

  private final List<String> created = new ArrayList<>();

  @AfterEach
  void deleteFixtures() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              for (String table : List.of("tombstone", "revision", "trashed", "node")) {
                entityManager
                    .createNativeQuery("DELETE FROM " + table + " WHERE node_id IN (:ids)")
                    .setParameter("ids", created)
                    .executeUpdate();
              }
            });
    created.clear();
  }

  private String givenAnExpiredTrashedFolder() {
    String id = UUID.randomUUID().toString();
    created.add(id);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              entityManager.persist(
                  new Node(
                      id,
                      OWNER,
                      OWNER,
                      "TRASH_ROOT",
                      THIRTY_ONE_DAYS_AGO,
                      THIRTY_ONE_DAYS_AGO,
                      id,
                      "",
                      NodeType.FOLDER,
                      "TRASH_ROOT",
                      1L));
              entityManager.persist(new TrashedNode(id, "LOCAL_ROOT"));
            });
    return id;
  }

  private String givenAFileUnder(String top) {
    String id = UUID.randomUUID().toString();
    created.add(id);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Node file =
                  new Node(
                      id,
                      OWNER,
                      OWNER,
                      top,
                      THIRTY_ONE_DAYS_AGO,
                      THIRTY_ONE_DAYS_AGO,
                      id,
                      "",
                      NodeType.TEXT,
                      "TRASH_ROOT," + top,
                      1L);
              file.setCurrentVersion(1);
              entityManager.persist(file);
              entityManager.persist(
                  new FileVersion(id, OWNER, THIRTY_ONE_DAYS_AGO, 1, "text/plain", 1L, "d", false));
            });
    return id;
  }

  private long nodeRows(String id) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                ((Number)
                        entityManager
                            .createNativeQuery("SELECT count(*) FROM node WHERE node_id = :id")
                            .setParameter("id", id)
                            .getSingleResult())
                    .longValue());
  }

  private void purgeInRequestContext() {
    ManagedContext requestContext = Arc.container().requestContext();
    requestContext.activate();
    try {
      purgeService.purgeTrashedNodes(30);
    } finally {
      requestContext.terminate();
    }
  }

  @Test
  void purgeRechecksTheTopUnderTheLock() throws Exception {
    String top = givenAnExpiredTrashedFolder();
    String file = givenAFileUnder(top);
    List<String> warnings = new CopyOnWriteArrayList<>();
    Handler capture =
        new Handler() {
          @Override
          public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
              warnings.add(
                  record.getMessage() + " " + java.util.Arrays.toString(record.getParameters()));
            }
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    Logger purgeLogger = Logger.getLogger(PurgeService.class.getName());
    purgeLogger.addHandler(capture);

    CompletableFuture<Void> purge;
    try (Connection holder = LockProbe.holder()) {
      LockProbe.hold(holder, top, "FOR NO KEY UPDATE");
      purge = CompletableFuture.runAsync(this::purgeInRequestContext);
      LockProbe.awaitBlockedBy(holder);
      try (PreparedStatement touch =
          holder.prepareStatement("UPDATE node SET updated_timestamp = ? WHERE node_id = ?")) {
        touch.setLong(1, System.currentTimeMillis());
        touch.setString(2, top);
        touch.executeUpdate();
      }
      holder.commit();
    }
    try {
      purge.get(60, TimeUnit.SECONDS);
    } finally {
      purgeLogger.removeHandler(capture);
    }

    assertThat(warnings).noneMatch(w -> w.contains("Unable to purge"));
    assertThat(nodeRows(top)).isEqualTo(1);
    assertThat(nodeRows(file)).isEqualTo(1);
  }

  @Test
  void aFileMovedOutOfTheTopBeforeTheBatchSurvives() throws Exception {
    String top = givenAnExpiredTrashedFolder();
    String file = givenAFileUnder(top);

    CompletableFuture<Void> purge;
    try (Connection holder = LockProbe.holder()) {
      LockProbe.hold(holder, top, "FOR SHARE");
      purge = CompletableFuture.runAsync(this::purgeInRequestContext);
      LockProbe.awaitBlockedBy(holder);
      try (PreparedStatement move =
          holder.prepareStatement(
              "UPDATE node SET folder_id = 'LOCAL_ROOT', ancestor_ids = 'LOCAL_ROOT',"
                  + " updated_timestamp = ? WHERE node_id = ?")) {
        move.setLong(1, System.currentTimeMillis());
        move.setString(2, file);
        move.executeUpdate();
      }
      holder.commit();
    }
    purge.get(60, TimeUnit.SECONDS);

    assertThat(nodeRows(file)).isEqualTo(1);
    assertThat(nodeRows(top)).isZero();
  }

  @Test
  void purgeDeletesAnExpiredTopNotHeld() {
    String top = givenAnExpiredTrashedFolder();
    String file = givenAFileUnder(top);

    purgeService.purgeTrashedNodes(30);

    assertThat(nodeRows(top)).isZero();
    assertThat(nodeRows(file)).isZero();
  }
}
