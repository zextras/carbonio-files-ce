// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal;

import static org.assertj.core.api.Assertions.assertThat;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.dao.ebean.VersionReservation;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository.VersionSlot;
import com.zextras.carbonio.files.dal.repositories.interfaces.VersionReservationRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class VersionReservationDalTest {

  @Inject NodeRepository nodeRepository;
  @Inject VersionReservationRepository reservationRepository;
  @Inject EntityManager entityManager;

  private final List<String> nodes = new ArrayList<>();

  @AfterEach
  void cleanUp() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              entityManager
                  .createQuery(
                      "delete from VersionReservation r where r.mComposedId.mNodeId in :ids")
                  .setParameter("ids", nodes)
                  .executeUpdate();
              entityManager
                  .createQuery("delete from Node n where n.mId in :ids")
                  .setParameter("ids", nodes)
                  .executeUpdate();
            });
  }

  private String fileWithCurrentVersion(int currentVersion) {
    String id = UUID.randomUUID().toString();
    nodes.add(id);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              long now = System.currentTimeMillis();
              Node node =
                  new Node(
                      id,
                      "owner",
                      "owner",
                      "LOCAL_ROOT",
                      now,
                      now,
                      "f",
                      "",
                      NodeType.TEXT,
                      "LOCAL_ROOT",
                      1L);
              node.setCurrentVersion(currentVersion);
              entityManager.persist(node);
            });
    return id;
  }

  private Optional<VersionSlot> reserve(String nodeId, long now) {
    return QuarkusTransaction.requiringNew()
        .call(() -> nodeRepository.reserveNextVersion(nodeId, now));
  }

  private Object[] counters(String nodeId) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                entityManager
                    .createQuery(
                        "select n.mCurrentVersion, n.mLastVersion, n.mLastReservedAt from Node n"
                            + " where n.mId = :id",
                        Object[].class)
                    .setParameter("id", nodeId)
                    .getSingleResult());
  }

  @Test
  void reservationsHandOutTheNumbersAfterTheCurrentVersionOneByOne() {
    String nodeId = fileWithCurrentVersion(1);

    assertThat(reserve(nodeId, 1000L)).contains(new VersionSlot(2, 1000L));
    assertThat(reserve(nodeId, 2000L)).contains(new VersionSlot(3, 2000L));
  }

  @Test
  void aReservationNeverReusesANumberAboveTheCurrentVersion() {
    String nodeId = fileWithCurrentVersion(2);
    reserve(nodeId, 1000L);
    reserve(nodeId, 1000L);

    assertThat(reserve(nodeId, 1000L).map(VersionSlot::version)).contains(5);
    assertThat(counters(nodeId)[0]).isEqualTo(2);
  }

  @Test
  void reservationTimesStrictlyIncreaseWhenTheClockGoesBack() {
    String nodeId = fileWithCurrentVersion(1);

    assertThat(reserve(nodeId, 1000L).map(VersionSlot::reservedAt)).contains(1000L);
    assertThat(reserve(nodeId, 500L).map(VersionSlot::reservedAt)).contains(1001L);
    assertThat(
            QuarkusTransaction.requiringNew()
                .call(() -> nodeRepository.reserveTimestamp(nodeId, 10L)))
        .contains(new VersionSlot(3, 1002L));
  }

  @Test
  void concurrentReservationsGetDistinctConsecutiveNumbers() throws Exception {
    String nodeId = fileWithCurrentVersion(1);
    int threads = 16;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<Optional<VersionSlot>>> results = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        results.add(
            pool.submit(
                () -> {
                  start.await();
                  return reserve(nodeId, System.currentTimeMillis());
                }));
      }
      start.countDown();
      List<Integer> versions = new ArrayList<>();
      List<Long> times = new ArrayList<>();
      for (Future<Optional<VersionSlot>> result : results) {
        VersionSlot slot = result.get().orElseThrow();
        versions.add(slot.version());
        times.add(slot.reservedAt());
      }

      assertThat(versions)
          .containsExactlyInAnyOrderElementsOf(IntStream.rangeClosed(2, 17).boxed().toList());
      assertThat(times).doesNotHaveDuplicates();
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void aMissingNodeGetsNoReservation() {
    assertThat(reserve(UUID.randomUUID().toString(), 1000L)).isEmpty();
  }

  @Test
  void aPendingReservationIsConsumedOnce() {
    String nodeId = fileWithCurrentVersion(1);
    QuarkusTransaction.requiringNew()
        .run(() -> reservationRepository.reserve(nodeId, 2, "owner", 1000L));

    assertThat(
            QuarkusTransaction.requiringNew().call(() -> reservationRepository.consume(nodeId, 2)))
        .isTrue();
    assertThat(
            QuarkusTransaction.requiringNew().call(() -> reservationRepository.consume(nodeId, 2)))
        .isFalse();
  }

  @Test
  void aClaimedReservationCanNoLongerBeConsumed() {
    String nodeId = fileWithCurrentVersion(1);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              reservationRepository.reserve(nodeId, 2, "owner", 100L);
              reservationRepository.reserve(nodeId, 3, "owner", 900L);
            });

    reservationRepository.claimExpired(500L);

    assertThat(reservationRepository.getPurging())
        .filteredOn(r -> r.getNodeId().equals(nodeId))
        .extracting(VersionReservation::getVersion)
        .containsExactly(2);
    assertThat(
            QuarkusTransaction.requiringNew().call(() -> reservationRepository.consume(nodeId, 2)))
        .isFalse();
    assertThat(
            QuarkusTransaction.requiringNew().call(() -> reservationRepository.consume(nodeId, 3)))
        .isTrue();
  }

  @Test
  void aFailedPurgeAttemptIsCountedOnTheClaimedReservation() {
    String nodeId = fileWithCurrentVersion(1);
    QuarkusTransaction.requiringNew()
        .run(() -> reservationRepository.reserve(nodeId, 2, "owner", 100L));
    reservationRepository.claimExpired(500L);
    VersionReservation claimed =
        reservationRepository.getPurging().stream()
            .filter(r -> r.getNodeId().equals(nodeId))
            .findFirst()
            .orElseThrow();

    reservationRepository.recordFailedAttempt(claimed);
    reservationRepository.recordFailedAttempt(claimed);

    assertThat(reservationRepository.getPurging())
        .filteredOn(r -> r.getNodeId().equals(nodeId))
        .extracting(VersionReservation::getAttempts)
        .containsExactly(2);
    reservationRepository.remove(claimed);
    assertThat(reservationRepository.getPurging()).noneMatch(r -> r.getNodeId().equals(nodeId));
  }

  @Test
  void theCurrentVersionOnlyMovesForward() {
    String nodeId = fileWithCurrentVersion(3);

    assertThat(nodeRepository.moveCurrentVersion(nodeId, 2, 5000L)).isFalse();
    assertThat(counters(nodeId)[0]).isEqualTo(3);
    assertThat(nodeRepository.moveCurrentVersion(nodeId, 3, 5000L, 7L, "editor")).isTrue();
    assertThat(nodeRepository.moveCurrentVersion(nodeId, 5, 6000L)).isTrue();
    assertThat(counters(nodeId)[0]).isEqualTo(5);
  }
}
