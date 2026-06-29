// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.tasks;

import com.google.inject.Injector;
import com.zextras.carbonio.files.Simulator;
import com.zextras.carbonio.files.Simulator.SimulatorBuilder;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.api.utilities.DatabasePopulator;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.api.utilities.entities.SimplePopulatorTextFile;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.TombstoneRepository;
import com.zextras.carbonio.files.utilities.StoragesMockHelper;
import com.zextras.carbonio.files.utilities.http.HttpRequest;
import com.zextras.carbonio.files.utilities.http.HttpResponse;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * Integration tests for {@link PurgeService#purgeTombstones()}.
 *
 * <p>Scenario:
 * <ol>
 *   <li>Delete a node while the storages mock fails → node gone, tombstone REMAINS.</li>
 *   <li>Fix the mock to succeed and invoke {@code purgeTombstones()} directly → tombstone REMOVED.</li>
 *   <li>A second node whose blob still fails → its tombstone KEPT after the same job run.</li>
 * </ol>
 *
 * <p>{@link PurgeService#purgeTombstones()} is package-private and therefore directly callable
 * from this test (same package: {@code com.zextras.carbonio.files.tasks}).
 */
class PurgeTombstonesJobIT {

  static Simulator simulator;
  static StoragesMockHelper storagesMockHelper;
  static NodeRepository nodeRepository;
  static TombstoneRepository tombstoneRepository;
  static PurgeService purgeService;

  private static final String OWNER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

  @BeforeAll
  static void init() {
    simulator =
        SimulatorBuilder.aSimulator()
            .init()
            .withDatabase()
            .withServiceDiscover()
            .withStorages()
            .withUserManagement(Map.of("fake-token", OWNER_ID))
            .build()
            .start();

    final Injector injector = simulator.getInjector();
    nodeRepository = injector.getInstance(NodeRepository.class);
    tombstoneRepository = injector.getInstance(TombstoneRepository.class);
    purgeService = injector.getInstance(PurgeService.class);
    storagesMockHelper = new StoragesMockHelper(simulator.getStoragesMock());
  }

  @AfterEach
  void cleanUp() {
    simulator.resetDatabase();
    // TOMBSTONE is not FK-linked to NODE → not cascade-deleted above.
    tombstoneRepository.getTombstones().forEach(t ->
        tombstoneRepository.deleteTombstonesByNodeAndVersion(t.getNodeId(), t.getVersion()));
    simulator.reinitializeMocks();
  }

  @AfterAll
  static void cleanUpAll() {
    simulator.stopAll();
  }

  private HttpResponse executeDeleteNodes(String... nodeIds) {
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("deleteNodes")
            .withListOfStrings("node_ids", nodeIds)
            .withWantedResultFormat("")
            .build();
    HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", null, bodyPayload);
    return TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());
  }

  /**
   * Full lifecycle:
   * (a) delete node while storages fails → tombstone stranded
   * (b) fix mock to succeed → purgeTombstones() removes it
   * (c) a second node whose blob still fails → tombstone kept after same run
   */
  @Test
  void givenStrandedTombstoneWhenJobRunsWithSuccessThenTombstoneRemovedAndFailedOneKept() {
    // --- (a) Setup: node1 deleted while storages is down → tombstone remains ---
    String node1Id = "00000000-0000-0000-0000-400000000001";
    String node2Id = "00000000-0000-0000-0000-400000000002";

    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(new SimplePopulatorTextFile(node1Id, OWNER_ID, "file1.txt"))
        .addNode(new SimplePopulatorTextFile(node2Id, OWNER_ID, "file2.txt"));

    // Both deletes happen while storages mock is failing.
    storagesMockHelper.bulkDeleteError();
    HttpResponse resp1 = executeDeleteNodes(node1Id);
    Assertions.assertThat(resp1.getStatus()).isEqualTo(200);

    // Reset mock and set it to fail again for node2 delete.
    simulator.reinitializeMocks();
    storagesMockHelper.bulkDeleteError();
    HttpResponse resp2 = executeDeleteNodes(node2Id);
    Assertions.assertThat(resp2.getStatus()).isEqualTo(200);

    // Both nodes are gone from DB (DB-first).
    Assertions.assertThat(nodeRepository.getNode(node1Id)).isEmpty();
    Assertions.assertThat(nodeRepository.getNode(node2Id)).isEmpty();

    // Two tombstones are stranded.
    Assertions.assertThat(tombstoneRepository.getTombstones()).hasSize(2);

    // --- (b) Fix mock for node1 (success), keep failing for node2 ---
    // We need the mock to return node2 as failed so its tombstone is kept.
    // bulkDelete(List.of(node2Id)) returns 200 with node2 in the failed list.
    simulator.reinitializeMocks();
    storagesMockHelper.bulkDelete(List.of(node2Id));

    // Invoke the purge job directly (package-private, same package).
    purgeService.purgeTombstones();

    // --- (c) Assertions ---
    // node1's tombstone must be gone (its blob succeeded this time).
    long node1Tombstones = tombstoneRepository.getTombstones().stream()
        .filter(t -> t.getNodeId().equals(node1Id))
        .count();
    Assertions.assertThat(node1Tombstones)
        .as("tombstone for node1 should have been removed after successful blob delete")
        .isZero();

    // node2's tombstone must still be present (blob still failing).
    long node2Tombstones = tombstoneRepository.getTombstones().stream()
        .filter(t -> t.getNodeId().equals(node2Id))
        .count();
    Assertions.assertThat(node2Tombstones)
        .as("tombstone for node2 should remain because its blob delete was still reported as failed")
        .isEqualTo(1);
  }

  /**
   * When purgeTombstones() runs and PowerStore is completely down (exception),
   * all tombstones are preserved for the next cycle.
   */
  @Test
  void givenStrandedTombstoneWhenJobRunsWithOutageThenTombstoneKept() {
    // Given: a stranded tombstone from a previous delete.
    String nodeId = "00000000-0000-0000-0000-400000000003";

    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(new SimplePopulatorTextFile(nodeId, OWNER_ID, "file.txt"));

    storagesMockHelper.bulkDeleteError();
    executeDeleteNodes(nodeId);

    Assertions.assertThat(tombstoneRepository.getTombstones()).hasSize(1);

    // When: job runs and storages is still down.
    // (mock is still configured to return 500)
    purgeService.purgeTombstones();

    // Then: tombstone is kept for retry (attempts=1, below cap=3).
    Assertions.assertThat(tombstoneRepository.getTombstones()).hasSize(1);
    Assertions.assertThat(tombstoneRepository.getTombstones().get(0).getNodeId())
        .isEqualTo(nodeId);
    Assertions.assertThat(tombstoneRepository.getTombstones().get(0).getAttempts())
        .as("attempts should be incremented to 1 after the first failed job run")
        .isEqualTo(1);
  }

  /**
   * Retry-cap scenario: a blob that keeps failing is dropped after the 3rd job run.
   * <p>
   * Run 1: purgeTombstones fails → tombstone kept, attempts=1.
   * Run 2: purgeTombstones fails → tombstone kept, attempts=2.
   * Run 3: purgeTombstones fails → attempts+1 == 3 == MAX_TOMBSTONE_RETRIES → tombstone REMOVED
   *         (orphan accepted, no perennial tombstone).
   */
  @Test
  void givenBlobAlwaysFailsThenTombstoneRemovedAfterThirdJobRun() {
    // Given: delete node while storages is down → tombstone created with attempts=0.
    String nodeId = "00000000-0000-0000-0000-400000000004";

    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(new SimplePopulatorTextFile(nodeId, OWNER_ID, "file.txt"));

    storagesMockHelper.bulkDeleteError();
    HttpResponse deleteResp = executeDeleteNodes(nodeId);
    Assertions.assertThat(deleteResp.getStatus()).isEqualTo(200);

    Assertions.assertThat(tombstoneRepository.getTombstones()).hasSize(1);
    Assertions.assertThat(tombstoneRepository.getTombstones().get(0).getAttempts()).isEqualTo(0);

    // Run 1: still failing.
    simulator.reinitializeMocks();
    storagesMockHelper.bulkDeleteError();
    purgeService.purgeTombstones();

    Assertions.assertThat(tombstoneRepository.getTombstones()).hasSize(1);
    Assertions.assertThat(tombstoneRepository.getTombstones().get(0).getAttempts())
        .as("attempts should be 1 after run 1")
        .isEqualTo(1);

    // Run 2: still failing.
    simulator.reinitializeMocks();
    storagesMockHelper.bulkDeleteError();
    purgeService.purgeTombstones();

    Assertions.assertThat(tombstoneRepository.getTombstones()).hasSize(1);
    Assertions.assertThat(tombstoneRepository.getTombstones().get(0).getAttempts())
        .as("attempts should be 2 after run 2")
        .isEqualTo(2);

    // Run 3: cap hit (attempts=2, +1==3==MAX_TOMBSTONE_RETRIES) → tombstone REMOVED.
    simulator.reinitializeMocks();
    storagesMockHelper.bulkDeleteError();
    purgeService.purgeTombstones();

    Assertions.assertThat(tombstoneRepository.getTombstones())
        .as("tombstone must be removed after the retry cap is reached on the 3rd job run")
        .isEmpty();
  }

  /**
   * Retry-and-recover scenario: a blob fails on the first job run but succeeds on the second.
   * <p>
   * Run 1: purgeTombstones reports the blob as failed → tombstone kept, attempts incremented.
   * Run 2: purgeTombstones succeeds → tombstone REMOVED normally (never hits the cap).
   */
  @Test
  void givenBlobSucceedsOnSecondJobRunThenTombstoneRemovedNormallyBeforeCap() {
    // Given: delete node while storages is down → tombstone created with attempts=0.
    String nodeId = "00000000-0000-0000-0000-400000000005";

    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(new SimplePopulatorTextFile(nodeId, OWNER_ID, "file.txt"));

    storagesMockHelper.bulkDeleteError();
    HttpResponse deleteResp = executeDeleteNodes(nodeId);
    Assertions.assertThat(deleteResp.getStatus()).isEqualTo(200);

    Assertions.assertThat(tombstoneRepository.getTombstones()).hasSize(1);
    Assertions.assertThat(tombstoneRepository.getTombstones().get(0).getAttempts()).isEqualTo(0);

    // Run 1: blob reported as failed → tombstone kept.
    simulator.reinitializeMocks();
    storagesMockHelper.bulkDelete(List.of(nodeId));
    purgeService.purgeTombstones();

    Assertions.assertThat(tombstoneRepository.getTombstones()).hasSize(1);
    Assertions.assertThat(tombstoneRepository.getTombstones().get(0).getAttempts())
        .as("attempts should be 1 after first failed run")
        .isEqualTo(1);

    // Run 2: blob deletion succeeds → tombstone REMOVED.
    simulator.reinitializeMocks();
    storagesMockHelper.bulkDelete(List.of());
    purgeService.purgeTombstones();

    Assertions.assertThat(tombstoneRepository.getTombstones())
        .as("tombstone must be removed when the blob is successfully deleted on the second run")
        .isEmpty();
  }
}
