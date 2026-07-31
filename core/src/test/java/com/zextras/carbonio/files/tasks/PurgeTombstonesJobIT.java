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
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for {@link PurgeService#purgeTombstones()}.
 *
 * <p>Scenario:
 *
 * <ol>
 *   <li>Delete a node while the storages mock fails → node gone, tombstone REMAINS.
 *   <li>Fix the mock to succeed and invoke {@code purgeTombstones()} directly → tombstone REMOVED.
 *   <li>A second node whose blob still fails → its tombstone KEPT after the same job run.
 * </ol>
 *
 * <p>{@link PurgeService#purgeTombstones()} is package-private and therefore directly callable from
 * this test (same package: {@code com.zextras.carbonio.files.tasks}).
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
    tombstoneRepository
        .getTombstones()
        .forEach(
            t ->
                tombstoneRepository.deleteTombstonesByNodeAndVersion(
                    t.getNodeId(), t.getVersion()));
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
   * Full lifecycle: (a) delete node while storages fails → tombstone stranded (b) fix mock to
   * succeed → purgeTombstones() removes it (c) a second node whose blob still fails → tombstone
   * kept after same run
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
    long node1Tombstones =
        tombstoneRepository.getTombstones().stream()
            .filter(t -> t.getNodeId().equals(node1Id))
            .count();
    Assertions.assertThat(node1Tombstones)
        .as("tombstone for node1 should have been removed after successful blob delete")
        .isZero();

    // node2's tombstone must still be present (blob still failing).
    long node2Tombstones =
        tombstoneRepository.getTombstones().stream()
            .filter(t -> t.getNodeId().equals(node2Id))
            .count();
    Assertions.assertThat(node2Tombstones)
        .as(
            "tombstone for node2 should remain because its blob delete was still reported as"
                + " failed")
        .isEqualTo(1);
  }

  /**
   * When purgeTombstones() runs and PowerStore is completely down (exception / HTTP 500), all
   * tombstones are preserved for the next cycle with NO increment to attempts. Even after 3
   * consecutive outage runs the tombstone must still be there with attempts==0, proving that an
   * outage never drains the retry budget.
   */
  @Test
  void givenStrandedTombstoneWhenJobRunsWithOutageThenTombstoneKeptAndAttemptsNeverIncrement() {
    // Given: a stranded tombstone from a previous delete.
    String nodeId = "00000000-0000-0000-0000-400000000003";

    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(new SimplePopulatorTextFile(nodeId, OWNER_ID, "file.txt"));

    storagesMockHelper.bulkDeleteError();
    executeDeleteNodes(nodeId);

    Assertions.assertThat(tombstoneRepository.getTombstones()).hasSize(1);
    Assertions.assertThat(tombstoneRepository.getTombstones().get(0).getAttempts())
        .as("attempts must start at 0")
        .isEqualTo(0);

    // Run the purge job 3 times while PowerStore is still down.
    // An outage must NEVER burn the retry budget (attempts stays at 0).
    for (int run = 1; run <= 3; run++) {
      simulator.reinitializeMocks();
      storagesMockHelper.bulkDeleteError();
      purgeService.purgeTombstones();

      Assertions.assertThat(tombstoneRepository.getTombstones())
          .as("tombstone must still exist after outage run " + run)
          .hasSize(1);
      Assertions.assertThat(tombstoneRepository.getTombstones().get(0).getNodeId())
          .isEqualTo(nodeId);
      Assertions.assertThat(tombstoneRepository.getTombstones().get(0).getAttempts())
          .as(
              "attempts must remain 0 after outage run "
                  + run
                  + " (outage must not burn retry budget)")
          .isEqualTo(0);
    }
  }

  /**
   * Retry-cap scenario: a blob that keeps failing (per-blob partial failure, HTTP 200 with the node
   * listed in the failed-ids list) is dropped after the 3rd job run.
   *
   * <p>This test intentionally uses the PARTIAL-FAILURE path (bulkDelete returns HTTP 200 with the
   * node id in the failed list), NOT the outage/exception path (HTTP 500). Only genuine per-blob
   * PowerStore rejections count toward the retry cap; outages do not.
   *
   * <p>Run 1: PowerStore responds 200, blob in failed list → attempts=1, tombstone kept. Run 2:
   * PowerStore responds 200, blob in failed list → attempts=2, tombstone kept. Run 3: PowerStore
   * responds 200, blob in failed list → attempts+1==3==MAX → tombstone REMOVED.
   */
  @Test
  void givenBlobAlwaysFailsWithPartialFailureThenTombstoneRemovedAfterThirdJobRun() {
    // Given: delete node while storages returns a partial failure → tombstone seeded with
    // attempts=0.
    String nodeId = "00000000-0000-0000-0000-400000000004";

    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(new SimplePopulatorTextFile(nodeId, OWNER_ID, "file.txt"));

    // Seed via partial failure (HTTP 200, node in failed ids list).
    storagesMockHelper.bulkDelete(List.of(nodeId));
    HttpResponse deleteResp = executeDeleteNodes(nodeId);
    Assertions.assertThat(deleteResp.getStatus()).isEqualTo(200);

    Assertions.assertThat(tombstoneRepository.getTombstones()).hasSize(1);
    Assertions.assertThat(tombstoneRepository.getTombstones().get(0).getAttempts())
        .as("attempts must be 0 after seeding")
        .isEqualTo(0);

    // Run 1: PowerStore reports the blob as failed (HTTP 200, failed list) → attempts=1.
    simulator.reinitializeMocks();
    storagesMockHelper.bulkDelete(List.of(nodeId));
    purgeService.purgeTombstones();

    Assertions.assertThat(tombstoneRepository.getTombstones()).hasSize(1);
    Assertions.assertThat(tombstoneRepository.getTombstones().get(0).getAttempts())
        .as("attempts should be 1 after run 1")
        .isEqualTo(1);

    // Run 2: PowerStore reports the blob as failed again → attempts=2.
    simulator.reinitializeMocks();
    storagesMockHelper.bulkDelete(List.of(nodeId));
    purgeService.purgeTombstones();

    Assertions.assertThat(tombstoneRepository.getTombstones()).hasSize(1);
    Assertions.assertThat(tombstoneRepository.getTombstones().get(0).getAttempts())
        .as("attempts should be 2 after run 2")
        .isEqualTo(2);

    // Run 3: cap hit (attempts=2, +1==3==MAX_TOMBSTONE_RETRIES) → tombstone REMOVED (orphan
    // accepted).
    simulator.reinitializeMocks();
    storagesMockHelper.bulkDelete(List.of(nodeId));
    purgeService.purgeTombstones();

    Assertions.assertThat(tombstoneRepository.getTombstones())
        .as("tombstone must be removed after the retry cap is reached on the 3rd job run")
        .isEmpty();
  }

  /**
   * Retry-and-recover scenario: a blob fails on the first job run but succeeds on the second.
   *
   * <p>Run 1: purgeTombstones reports the blob as failed → tombstone kept, attempts incremented.
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

  /**
   * Null/empty JSON response from PowerStore ({"ids":null} or {}) during purgeTombstones() must be
   * treated the same as an outage: ALL tombstones kept, attempts NOT incremented.
   */
  @Test
  void
      givenStrandedTombstoneWhenPurgeRunsWithNullResponseThenTombstoneKeptAndAttemptsNeverIncrement() {
    // Given: a stranded tombstone.
    String nodeId = "00000000-0000-0000-0000-400000000006";

    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(new SimplePopulatorTextFile(nodeId, OWNER_ID, "file.txt"));

    // Seed via error (HTTP 500) so the tombstone is created with attempts=0.
    storagesMockHelper.bulkDeleteError();
    executeDeleteNodes(nodeId);

    Assertions.assertThat(tombstoneRepository.getTombstones()).hasSize(1);
    Assertions.assertThat(tombstoneRepository.getTombstones().get(0).getAttempts())
        .as("attempts must start at 0")
        .isEqualTo(0);

    // Run purgeTombstones() with a null/empty response — must NOT remove tombstone or increment
    // attempts.
    simulator.reinitializeMocks();
    storagesMockHelper.bulkDeleteNullResponse();
    purgeService.purgeTombstones();

    Assertions.assertThat(tombstoneRepository.getTombstones())
        .as("tombstone must remain when purgeTombstones receives a null response")
        .hasSize(1);
    Assertions.assertThat(tombstoneRepository.getTombstones().get(0).getAttempts())
        .as("attempts must stay 0 — null response is treated as outage, not per-blob failure")
        .isEqualTo(0);
  }
}
