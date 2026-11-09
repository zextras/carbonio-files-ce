// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.locks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.RollbackException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class NodeLocksDalTest {

  private static final String OWNER = "owner-locks";
  private static final String SHARE = "FOR SHARE";
  private static final String NO_KEY_UPDATE = "FOR NO KEY UPDATE";
  private static final Duration PATIENT = Duration.ofSeconds(30);

  @Inject NodeLocks nodeLocks;
  @Inject NodeRepository nodeRepository;
  @Inject EntityManager entityManager;

  private String a;
  private String b;
  private String c;
  private String d;

  @BeforeEach
  void createFixture() {
    a = UUID.randomUUID().toString();
    b = UUID.randomUUID().toString();
    c = UUID.randomUUID().toString();
    d = UUID.randomUUID().toString();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              folder(a, "LOCAL_ROOT", "LOCAL_ROOT");
              folder(b, a, "LOCAL_ROOT," + a);
              folder(c, b, "LOCAL_ROOT," + a + "," + b);
              folder(d, "LOCAL_ROOT", "LOCAL_ROOT");
            });
  }

  private void folder(String id, String parent, String ancestors) {
    nodeRepository.createNewNode(
        id, OWNER, OWNER, parent, "n-" + id, "", NodeType.FOLDER, ancestors, 0L);
  }

  @AfterEach
  void deleteFixture() throws SQLException {
    try (Connection connection =
            DriverManager.getConnection(FilesStackTestResource.POSTGRES_JDBC_URL, "test", "test");
        PreparedStatement statement =
            connection.prepareStatement("DELETE FROM node WHERE owner_id = ?")) {
      statement.setString(1, OWNER);
      statement.executeUpdate();
    }
  }

  private static void inNewTransaction(ThrowingRunnable body) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              try {
                body.run();
              } catch (RuntimeException e) {
                throw e;
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            });
  }

  private interface ThrowingRunnable {
    void run() throws Exception;
  }

  private static boolean causedBy(Throwable throwable, Class<? extends Throwable> type) {
    for (Throwable t = throwable; t != null; t = t.getCause()) {
      if (type.isInstance(t)) {
        return true;
      }
    }
    return false;
  }

  private String setting(String name) {
    return (String)
        entityManager
            .createNativeQuery("SELECT current_setting(?1)")
            .setParameter(1, name)
            .getSingleResult();
  }

  private void assertSavepointReleased() {
    entityManager
        .unwrap(Session.class)
        .doWork(
            connection -> {
              try (Statement statement = connection.createStatement()) {
                statement.execute("SAVEPOINT files_probe");
                assertThatThrownBy(() -> statement.execute("RELEASE SAVEPOINT files_node_locks"))
                    .isInstanceOfSatisfying(
                        SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("3B001"));
                statement.execute("ROLLBACK TO SAVEPOINT files_probe");
                statement.execute("RELEASE SAVEPOINT files_probe");
              }
            });
  }

  @Test
  void locksTheChangedNodeExclusivelyAndItsAncestorsShared() {
    inNewTransaction(
        () -> {
          nodeLocks.lockForChange(List.of(c));

          assertThat(LockProbe.canLockNow(c, SHARE)).isFalse();
          assertThat(LockProbe.canLockNow(b, SHARE)).isTrue();
          assertThat(LockProbe.canLockNow(b, NO_KEY_UPDATE)).isFalse();
          assertThat(LockProbe.canLockNow(a, NO_KEY_UPDATE)).isFalse();
          assertThat(LockProbe.canLockNow("LOCAL_ROOT", "FOR UPDATE")).isTrue();
          assertThat(LockProbe.canLockNow(d, NO_KEY_UPDATE)).isTrue();
          assertThat(LockProbe.canLockNow(c, "FOR KEY SHARE")).isTrue();
          assertThat(LockProbe.canLockNow(a, SHARE)).isTrue();
          assertSavepointReleased();
        });
  }

  @Test
  void returnsTheLockedIdsAndIgnoresMissingOnes() {
    inNewTransaction(
        () -> {
          assertThat(nodeLocks.lockForChange(List.of("missing-id", c))).isEqualTo(Set.of(a, b, c));
          assertSavepointReleased();
        });
  }

  @Test
  void waitsForAHolderThenProceeds() throws Exception {
    try (Connection holder = LockProbe.holder()) {
      LockProbe.hold(holder, b, NO_KEY_UPDATE);
      CompletableFuture<Void> future =
          CompletableFuture.runAsync(
              () ->
                  QuarkusTransaction.requiringNew()
                      .run(() -> nodeLocks.lockForChange(List.of(c), PATIENT)));
      LockProbe.awaitBlockedBy(holder);
      holder.commit();
      future.get(30, TimeUnit.SECONDS);
    }
  }

  @Test
  void failsAfterTheBudgetWithoutWriting() throws Exception {
    try (Connection holder = LockProbe.holder()) {
      LockProbe.hold(holder, c, NO_KEY_UPDATE);
      long start = System.nanoTime();
      assertThrows(
          NodeLockTimeoutException.class,
          () ->
              QuarkusTransaction.requiringNew()
                  .run(() -> nodeLocks.lockForChange(List.of(c), Duration.ofMillis(300))));
      long elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();
      assertThat(elapsedMillis).isGreaterThanOrEqualTo(300).isLessThan(5000);
      holder.rollback();
    }
  }

  @Test
  void restoresTheTimeoutsOnceTheLocksAreHeld() {
    inNewTransaction(
        () -> {
          entityManager.createNativeQuery("SET LOCAL lock_timeout = '7s'").executeUpdate();
          entityManager.createNativeQuery("SET LOCAL statement_timeout = '9s'").executeUpdate();

          nodeLocks.lockForChange(List.of(c));

          assertThat(setting("lock_timeout")).isEqualTo("7s");
          assertThat(setting("statement_timeout")).isEqualTo("9s");
          assertThat(setting("idle_in_transaction_session_timeout")).isEqualTo("1min");
        });
  }

  @Test
  void relocksWhenTheChainMovedWhileWaiting() throws Exception {
    CountDownLatch reached = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicReference<Throwable> backgroundFailure = new AtomicReference<>();
    try (Connection holder = LockProbe.holder()) {
      LockProbe.hold(holder, a, NO_KEY_UPDATE);
      try (PreparedStatement moveB =
              holder.prepareStatement(
                  "UPDATE node SET folder_id = ?, ancestor_ids = ? WHERE node_id = ?");
          PreparedStatement moveC =
              holder.prepareStatement("UPDATE node SET ancestor_ids = ? WHERE node_id = ?")) {
        moveB.setString(1, d);
        moveB.setString(2, "LOCAL_ROOT," + d);
        moveB.setString(3, b);
        moveB.executeUpdate();
        moveC.setString(1, "LOCAL_ROOT," + d + "," + b);
        moveC.setString(2, c);
        moveC.executeUpdate();
      }
      CompletableFuture<Void> future =
          CompletableFuture.runAsync(
              () ->
                  inNewTransaction(
                      () -> {
                        nodeLocks.lockForChange(List.of(c), PATIENT);
                        try {
                          assertSavepointReleased();
                        } catch (Throwable t) {
                          backgroundFailure.set(t);
                        }
                        reached.countDown();
                        assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
                      }));
      try {
        LockProbe.awaitBlockedBy(holder);
        holder.commit();
        assertThat(reached.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(backgroundFailure.get()).isNull();
        assertThat(LockProbe.canLockNow(d, NO_KEY_UPDATE)).isFalse();
        assertThat(LockProbe.canLockNow(a, NO_KEY_UPDATE)).isTrue();
      } finally {
        release.countDown();
      }
      future.get(30, TimeUnit.SECONDS);
    }
  }

  @Test
  void refusesWithPendingEntityChanges() {
    Throwable thrown =
        assertThrows(
            RuntimeException.class,
            () ->
                inNewTransaction(
                    () -> {
                      entityManager.find(Node.class, d).setName("renamed");

                      assertThatThrownBy(() -> nodeLocks.lockForChange(List.of(c)))
                          .isInstanceOf(IllegalStateException.class);
                    }));
    assertThat(causedBy(thrown, RollbackException.class)).isTrue();
  }

  @Test
  void locksInTheGlobalOrderSoLaterRowsStayFreeWhileBlocked() throws Exception {
    Map<String, Integer> depths = Map.of(a, 0, b, 1, c, 2, d, 0);
    List<String> order =
        depths.keySet().stream()
            .sorted(Comparator.comparingInt((String id) -> depths.get(id)).thenComparing(id -> id))
            .toList();
    int blockedAt = order.indexOf(d);
    try (Connection holder = LockProbe.holder()) {
      LockProbe.hold(holder, d, NO_KEY_UPDATE);
      CompletableFuture<Void> future =
          CompletableFuture.runAsync(
              () ->
                  QuarkusTransaction.requiringNew()
                      .run(() -> nodeLocks.lockForChange(List.of(c, d), PATIENT)));
      try {
        LockProbe.awaitBlockedBy(holder);
        for (int i = 0; i < order.size(); i++) {
          if (i > blockedAt) {
            assertThat(LockProbe.canLockNow(order.get(i), NO_KEY_UPDATE)).isTrue();
          } else if (i < blockedAt) {
            assertThat(LockProbe.canLockNow(order.get(i), NO_KEY_UPDATE)).isFalse();
          }
        }
      } finally {
        holder.commit();
      }
      future.get(30, TimeUnit.SECONDS);
    }
  }

  @Test
  void refusesAfterAWrite() {
    boolean[] refused = {false};
    Throwable thrown =
        assertThrows(
            RuntimeException.class,
            () ->
                inNewTransaction(
                    () -> {
                      entityManager
                          .createNativeQuery(
                              "UPDATE node SET updated_timestamp = updated_timestamp"
                                  + " WHERE node_id = ?1")
                          .setParameter(1, d)
                          .executeUpdate();

                      assertThatThrownBy(() -> nodeLocks.lockForChange(List.of(c)))
                          .isInstanceOf(IllegalStateException.class);
                      refused[0] = true;
                    }));
    assertThat(refused[0]).isTrue();
    assertThat(causedBy(thrown, RollbackException.class)).isTrue();
  }

  @Test
  void doesNothingForRootsOnly() {
    inNewTransaction(
        () -> assertThat(nodeLocks.lockForChange(List.of("LOCAL_ROOT", "TRASH_ROOT"))).isEmpty());
  }

  private long advisoryLocksHeld() {
    return ((Number)
            entityManager
                .createNativeQuery(
                    "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND pid ="
                        + " pg_backend_pid()")
                .getSingleResult())
        .longValue();
  }

  @Test
  void locksAParentSharedWithItsAncestorsAndSerializesItsChildren() {
    inNewTransaction(
        () -> {
          Set<String> locked = nodeLocks.lock(List.of(), List.of(c));

          assertThat(locked).isEqualTo(Set.of(a, b, c));
          assertThat(LockProbe.canLockNow(c, SHARE)).isTrue();
          assertThat(LockProbe.canLockNow(c, NO_KEY_UPDATE)).isFalse();
          assertThat(LockProbe.canLockNow(b, SHARE)).isTrue();
          assertThat(LockProbe.canLockNow(b, NO_KEY_UPDATE)).isFalse();
          assertThat(LockProbe.canLockNow(a, SHARE)).isTrue();
          assertThat(LockProbe.canLockNow(a, NO_KEY_UPDATE)).isFalse();
          assertThat(advisoryLocksHeld()).isEqualTo(1);
          assertSavepointReleased();
        });
  }

  @Test
  void concurrentChildCreationsInTheSameParentSerialize() throws Exception {
    CountDownLatch firstHolds = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    CompletableFuture<Void> first =
        CompletableFuture.runAsync(
            () ->
                inNewTransaction(
                    () -> {
                      nodeLocks.lock(List.of(), List.of(b), PATIENT);
                      firstHolds.countDown();
                      assertThat(releaseFirst.await(30, TimeUnit.SECONDS)).isTrue();
                    }));
    try {
      assertThat(firstHolds.await(30, TimeUnit.SECONDS)).isTrue();
      CompletableFuture<Void> second =
          CompletableFuture.runAsync(
              () ->
                  QuarkusTransaction.requiringNew()
                      .run(() -> nodeLocks.lock(List.of(), List.of(b), PATIENT)));
      try {
        LockProbe.awaitAdvisoryWaiter();
        assertThat(second).isNotDone();
      } finally {
        releaseFirst.countDown();
      }
      second.get(30, TimeUnit.SECONDS);
    } finally {
      releaseFirst.countDown();
    }
    first.get(30, TimeUnit.SECONDS);
  }

  @Test
  void aParentIsNotBlockedByLongOperationsBelowIt() throws Exception {
    try (Connection holder = LockProbe.holder()) {
      LockProbe.hold(holder, b, SHARE);
      LockProbe.hold(holder, c, NO_KEY_UPDATE);

      Set<String> locked =
          QuarkusTransaction.requiringNew()
              .call(() -> nodeLocks.lock(List.of(), List.of(b), Duration.ofSeconds(3)));

      assertThat(locked).isEqualTo(Set.of(a, b));
      holder.rollback();
    }
  }

  @Test
  void aRootParentTakesNoLocks() {
    inNewTransaction(
        () -> {
          assertThat(nodeLocks.lock(List.of(), List.of("LOCAL_ROOT"))).isEmpty();
          assertThat(advisoryLocksHeld()).isZero();
          assertThat(nodeLocks.lock(List.of(), List.of("LOCAL_ROOT", "TRASH_ROOT", d)))
              .isEqualTo(Set.of(d));
          assertThat(advisoryLocksHeld()).isEqualTo(1);
        });
  }

  @Test
  void anIdInBothListsEndsExclusive() {
    inNewTransaction(
        () -> {
          assertThat(nodeLocks.lock(List.of(b), List.of(b))).isEqualTo(Set.of(a, b));

          assertThat(LockProbe.canLockNow(b, SHARE)).isFalse();
          assertThat(LockProbe.canLockNow(a, SHARE)).isTrue();
          assertThat(advisoryLocksHeld()).isEqualTo(1);
        });
  }
}
