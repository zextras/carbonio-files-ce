// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal;

import static org.assertj.core.api.Assertions.assertThat;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.dao.ebean.VersionReservation;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository.VersionSlot;
import com.zextras.carbonio.files.dal.repositories.interfaces.VersionReservationRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Duration;
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
  @Inject FileVersionRepository fileVersionRepository;
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
                  .createQuery("delete from FileVersion v where v.mComposedId.mNodeId in :ids")
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

  private Optional<VersionSlot> reserve(String nodeId) {
    return QuarkusTransaction.requiringNew().call(() -> nodeRepository.reserveNextVersion(nodeId));
  }

  private static long hoursAgo(int hours) {
    return System.currentTimeMillis() - Duration.ofHours(hours).toMillis();
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

    assertThat(reserve(nodeId).map(VersionSlot::version)).contains(2);
    assertThat(reserve(nodeId).map(VersionSlot::version)).contains(3);
  }

  @Test
  void aReservationNeverReusesANumberAboveTheCurrentVersion() {
    String nodeId = fileWithCurrentVersion(2);
    reserve(nodeId);
    reserve(nodeId);

    assertThat(reserve(nodeId).map(VersionSlot::version)).contains(5);
    assertThat(counters(nodeId)[0]).isEqualTo(2);
  }

  @Test
  void reservationTimesComeFromTheDatabaseClock() {
    String nodeId = fileWithCurrentVersion(1);
    long before = System.currentTimeMillis();

    long reservedAt = reserve(nodeId).orElseThrow().reservedAt();

    assertThat(reservedAt).isBetween(before - 5_000, System.currentTimeMillis() + 5_000);
  }

  @Test
  void reservationTimesStrictlyIncreaseWhenTheNodeClockIsAheadOfTheDatabaseClock() {
    String nodeId = fileWithCurrentVersion(1);
    long ahead = System.currentTimeMillis() + 3_600_000;
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                entityManager
                    .createQuery("update Node n set n.mLastReservedAt = :ahead where n.mId = :id")
                    .setParameter("ahead", ahead)
                    .setParameter("id", nodeId)
                    .executeUpdate());

    assertThat(reserve(nodeId)).contains(new VersionSlot(2, ahead + 1));
    assertThat(
            QuarkusTransaction.requiringNew().call(() -> nodeRepository.reserveTimestamp(nodeId)))
        .contains(new VersionSlot(2, ahead + 2));
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
                  return reserve(nodeId);
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
    assertThat(reserve(UUID.randomUUID().toString())).isEmpty();
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
              reservationRepository.reserve(nodeId, 2, "owner", hoursAgo(2));
              reservationRepository.reserve(nodeId, 3, "owner", System.currentTimeMillis());
            });

    reservationRepository.claimExpired(Duration.ofHours(1));

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
        .run(() -> reservationRepository.reserve(nodeId, 2, "owner", hoursAgo(2)));
    reservationRepository.claimExpired(Duration.ofHours(1));
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

  @Test
  void aVersionKeptForeverIsNeverRemovedByTheTrimming() {
    String nodeId = fileWithCurrentVersion(2);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              fileVersionRepository.createNewFileVersion(
                  nodeId, "owner", 1, "text/plain", 1L, "d", false);
              fileVersionRepository
                  .createNewFileVersion(nodeId, "owner", 2, "text/plain", 1L, "d", false)
                  .orElseThrow()
                  .keepForever(true);
            });

    assertThat(fileVersionRepository.removeVersionNotKeptForever(nodeId, 2)).isFalse();
    assertThat(fileVersionRepository.removeVersionNotKeptForever(nodeId, 1)).isTrue();
    assertThat(fileVersionRepository.getFileVersion(nodeId, 2)).isPresent();
  }
}
