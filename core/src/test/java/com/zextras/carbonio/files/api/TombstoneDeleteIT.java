// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.api;

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
 * Integration tests covering the tombstone sync-path lifecycle:
 * <ol>
 *   <li>tombstone created on delete then removed after a successful bulkDelete</li>
 *   <li>tombstone remains when bulkDelete reports the blob as failed</li>
 *   <li>tombstone remains when PowerStore is completely unavailable (500 outage)</li>
 * </ol>
 *
 * <p>These tests mirror the Advanced TombstoneDeleteIT to ensure CE parity.</p>
 */
class TombstoneDeleteIT {

  static Simulator simulator;
  static StoragesMockHelper storagesMockHelper;
  static NodeRepository nodeRepository;
  static TombstoneRepository tombstoneRepository;

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
    storagesMockHelper = new StoragesMockHelper(simulator.getStoragesMock());
  }

  @AfterEach
  void cleanUp() {
    simulator.resetDatabase();
    // TOMBSTONE is not FK-linked to NODE, so resetDatabase() does not cascade into it.
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

  // --- Test 1: tombstone created → removed on sync-path success ---
  // When bulkDelete succeeds the delete-handler removes the tombstone in the same request.

  @Test
  void givenNodeDeletedAndBlobSucceedsThenTombstoneIsCreatedAndThenRemovedSynchronously() {
    // Given
    String fileId = "00000000-0000-0000-0000-200000000001";

    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(new SimplePopulatorTextFile(fileId, OWNER_ID, "file.txt"));

    storagesMockHelper.bulkDelete(List.of());

    // When
    HttpResponse httpResponse = executeDeleteNodes(fileId);

    // Then — node deleted from DB.
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Assertions.assertThat(nodeRepository.getNode(fileId)).isEmpty();

    // Tombstone was created and then immediately removed after confirmed bulkDelete.
    Assertions.assertThat(tombstoneRepository.getTombstones()).isEmpty();
  }

  // --- Test 2: tombstone remains when bulkDelete reports the blob as failed ---
  // The response contains the nodeId in its failed-ids list; the handler must leave the tombstone.

  @Test
  void givenNodeDeletedAndBulkDeleteReportsFailureThenTombstoneRemains() {
    // Given
    String fileId = "00000000-0000-0000-0000-200000000002";

    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(new SimplePopulatorTextFile(fileId, OWNER_ID, "file.txt"));

    // PowerStore responds 200 but reports this node as failed.
    storagesMockHelper.bulkDelete(List.of(fileId));

    // When
    HttpResponse httpResponse = executeDeleteNodes(fileId);

    // Then — DB delete committed (DB-first contract).
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Assertions.assertThat(nodeRepository.getNode(fileId)).isEmpty();

    // Tombstone remains so PurgeService can retry blob deletion.
    Assertions.assertThat(tombstoneRepository.getTombstones()).hasSize(1);
    Assertions.assertThat(tombstoneRepository.getTombstones().get(0).getNodeId())
        .isEqualTo(fileId);
  }

  // --- Test 3: tombstone remains on complete PowerStore outage (HTTP 500) ---

  @Test
  void givenNodeDeletedAndPowerStoreIsDownThenTombstoneRemains() {
    // Given
    String fileId = "00000000-0000-0000-0000-200000000003";

    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(new SimplePopulatorTextFile(fileId, OWNER_ID, "file.txt"));

    storagesMockHelper.bulkDeleteError();

    // When
    HttpResponse httpResponse = executeDeleteNodes(fileId);

    // Then — DB delete committed; user sees success (DB-first contract).
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Assertions.assertThat(nodeRepository.getNode(fileId)).isEmpty();

    // Tombstone remains for PurgeService retry.
    Assertions.assertThat(tombstoneRepository.getTombstones()).hasSize(1);
    Assertions.assertThat(tombstoneRepository.getTombstones().get(0).getNodeId())
        .isEqualTo(fileId);
  }
}
