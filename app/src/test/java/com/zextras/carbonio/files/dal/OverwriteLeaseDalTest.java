// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal;

import static org.assertj.core.api.Assertions.assertThat;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.dao.ebean.OverwriteLease;
import com.zextras.carbonio.files.dal.repositories.interfaces.OverwriteLeaseRepository;
import com.zextras.carbonio.files.rest.services.OverwriteLeases;
import com.zextras.carbonio.files.rest.services.OverwriteLeases.Lease;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class OverwriteLeaseDalTest {

  @Inject OverwriteLeases overwriteLeases;
  @Inject OverwriteLeaseRepository leaseRepository;
  @Inject EntityManager entityManager;

  private final List<String> nodes = new ArrayList<>();

  @AfterEach
  void cleanUp() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              entityManager
                  .createQuery("delete from OverwriteLease l where l.mNodeId in :ids")
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

  private void expireAt(String nodeId, long expiresAt) {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                entityManager
                    .createQuery(
                        "update OverwriteLease l set l.mExpiresAt = :at where l.mNodeId = :id")
                    .setParameter("at", expiresAt)
                    .setParameter("id", nodeId)
                    .executeUpdate());
  }

  private void expire(String nodeId) {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                entityManager
                    .createQuery(
                        "update OverwriteLease l set l.mExpiresAt = 0 where l.mNodeId = :id")
                    .setParameter("id", nodeId)
                    .executeUpdate());
  }

  @Test
  void aLeaseIsTakenOnTheCurrentVersionWhenFreeAndRefusedWhileHeld() {
    String nodeId = fileWithCurrentVersion(3);

    Optional<Lease> first = overwriteLeases.take(nodeId);
    Optional<Lease> second = overwriteLeases.take(nodeId);

    assertThat(first).isPresent();
    assertThat(first.get().version()).isEqualTo(3);
    assertThat(second).isEmpty();
    OverwriteLease stored = leaseRepository.find(nodeId).orElseThrow();
    assertThat(stored.getToken()).isEqualTo(first.get().token());
    assertThat(stored.getRegisteredAt()).isEqualTo(first.get().date());
    assertThat(stored.getExpiresAt() - System.currentTimeMillis())
        .isBetween(Duration.ofSeconds(20).toMillis(), Duration.ofSeconds(40).toMillis());
  }

  @Test
  void anExpiredLeaseIsTakenOverAndTheOldTokenCanNoLongerReleaseIt() {
    String nodeId = fileWithCurrentVersion(1);
    Lease old = overwriteLeases.take(nodeId).orElseThrow();
    expire(nodeId);

    Lease newer = overwriteLeases.take(nodeId).orElseThrow();

    assertThat(newer.token()).isNotEqualTo(old.token());
    assertThat(newer.date()).isGreaterThan(old.date());
    assertThat(QuarkusTransaction.requiringNew().call(() -> overwriteLeases.confirm(old)))
        .isFalse();
    assertThat(leaseRepository.find(nodeId)).isPresent();
    assertThat(QuarkusTransaction.requiringNew().call(() -> overwriteLeases.confirm(newer)))
        .isTrue();
    assertThat(leaseRepository.find(nodeId)).isEmpty();
  }

  @Test
  void anExpiredLeaseNobodyTookIsStillConfirmedByItsOwner() {
    String nodeId = fileWithCurrentVersion(1);
    Lease lease = overwriteLeases.take(nodeId).orElseThrow();
    expire(nodeId);

    assertThat(QuarkusTransaction.requiringNew().call(() -> overwriteLeases.confirm(lease)))
        .isTrue();
  }

  @Test
  void onlyTheLeasesExpiredForLongerThanAGivenTimeAreRemoved() {
    String expiredLongAgo = fileWithCurrentVersion(1);
    String expiredRecently = fileWithCurrentVersion(1);
    String held = fileWithCurrentVersion(1);
    overwriteLeases.take(expiredLongAgo).orElseThrow();
    overwriteLeases.take(expiredRecently).orElseThrow();
    overwriteLeases.take(held).orElseThrow();
    expire(expiredLongAgo);
    expireAt(expiredRecently, System.currentTimeMillis() - Duration.ofMinutes(10).toMillis());

    leaseRepository.removeExpired(Duration.ofHours(1));

    assertThat(leaseRepository.find(expiredLongAgo)).isEmpty();
    assertThat(leaseRepository.find(expiredRecently)).isPresent();
    assertThat(leaseRepository.find(held)).isPresent();
  }

  @Test
  void concurrentOverwritesOfANodeGetOneLease() throws Exception {
    String nodeId = fileWithCurrentVersion(1);
    int threads = 8;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<Optional<Lease>>> results = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        results.add(
            pool.submit(
                () -> {
                  start.await();
                  return overwriteLeases.take(nodeId);
                }));
      }
      start.countDown();
      int taken = 0;
      for (Future<Optional<Lease>> result : results) {
        taken += result.get().isPresent() ? 1 : 0;
      }

      assertThat(taken).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }
}
