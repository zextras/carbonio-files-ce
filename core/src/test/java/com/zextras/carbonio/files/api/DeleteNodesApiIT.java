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
import com.zextras.carbonio.files.api.utilities.entities.PopulatorNode;
import com.zextras.carbonio.files.api.utilities.entities.SimplePopulatorFolder;
import com.zextras.carbonio.files.api.utilities.entities.SimplePopulatorTextFile;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
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

class DeleteNodesApiIT {

  static Simulator simulator;
  static StoragesMockHelper storagesMockHelper;
  static NodeRepository nodeRepository;
  static FileVersionRepository fileVersionRepository;
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
    fileVersionRepository = injector.getInstance(FileVersionRepository.class);
    tombstoneRepository = injector.getInstance(TombstoneRepository.class);
    storagesMockHelper = new StoragesMockHelper(simulator.getStoragesMock());
  }

  @AfterEach
  void cleanUp() {
    simulator.resetDatabase();
    // Tombstones are not FK-linked to NODE so resetDatabase() doesn't clean them.
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

  // --- Test 1: Happy path — 2 files, all blobs succeed ---
  // Tombstones are created then removed after confirmed bulkDelete.

  @Test
  void givenTwoFilesAndAllBlobsSucceedThenBothAreDeletedAndReturnedInResponse() {
    // Given
    String file1Id = "00000000-0000-0000-0000-100000000001";
    String file2Id = "00000000-0000-0000-0000-100000000002";

    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(new SimplePopulatorTextFile(file1Id, OWNER_ID, "file1.txt"))
        .addNode(new SimplePopulatorTextFile(file2Id, OWNER_ID, "file2.txt"));

    storagesMockHelper.bulkDelete(List.of());

    // When
    HttpResponse httpResponse = executeDeleteNodes(file1Id, file2Id);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    List<String> deletedIds =
        (List<String>) TestUtils.jsonResponseToValue(httpResponse.getBodyPayload(), "deleteNodes")
            .orElse(List.of());
    Assertions.assertThat(deletedIds).containsExactlyInAnyOrder(file1Id, file2Id);

    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors).isEmpty();

    // Both files deleted from DB.
    Assertions.assertThat(nodeRepository.getNode(file1Id)).isEmpty();
    Assertions.assertThat(nodeRepository.getNode(file2Id)).isEmpty();

    // Tombstones cleaned up after confirmed blob delete.
    Assertions.assertThat(tombstoneRepository.getTombstones()).isEmpty();
  }

  // --- Test 2: PowerStore fails (exception) — files still deleted from DB, no error to user ---
  // Core tombstone invariant: DB delete committed first; blob failure is best-effort.

  @Test
  void givenTwoFilesAndPowerStoreFailsThenBothAreStillDeletedFromDbAndNoErrorReturnedToUser() {
    // Given
    String file1Id = "00000000-0000-0000-0000-100000000003";
    String file2Id = "00000000-0000-0000-0000-100000000004";

    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(new SimplePopulatorTextFile(file1Id, OWNER_ID, "file1.txt"))
        .addNode(new SimplePopulatorTextFile(file2Id, OWNER_ID, "file2.txt"));

    // PowerStore returns HTTP 500 — complete failure.
    storagesMockHelper.bulkDeleteError();

    // When
    HttpResponse httpResponse = executeDeleteNodes(file1Id, file2Id);

    // Then — DB-first: both nodes deleted, no error to user.
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    List<String> deletedIds =
        (List<String>) TestUtils.jsonResponseToValue(httpResponse.getBodyPayload(), "deleteNodes")
            .orElse(List.of());
    Assertions.assertThat(deletedIds).containsExactlyInAnyOrder(file1Id, file2Id);

    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors).isEmpty();

    // Both nodes deleted from DB (already committed before PowerStore call).
    Assertions.assertThat(nodeRepository.getNode(file1Id)).isEmpty();
    Assertions.assertThat(nodeRepository.getNode(file2Id)).isEmpty();

    // Tombstones remain for PurgeService retry.
    Assertions.assertThat(tombstoneRepository.getTombstones()).hasSize(2);
  }

  // --- Test 3: Folder with file, all blobs succeed — both deleted, no errors ---

  @Test
  void givenFolderWithFileAndBlobSucceedsThenBothFolderAndFileAreDeleted() {
    // Given
    String folderId = "00000000-0000-0000-0000-100000000007";
    String fileId   = "00000000-0000-0000-0000-100000000008";

    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(new SimplePopulatorFolder(folderId, OWNER_ID, "folder"))
        .addNode(new PopulatorNode(
            fileId, OWNER_ID, OWNER_ID, folderId, "file.txt", "",
            NodeType.TEXT, "LOCAL_ROOT," + folderId, 1L, "text/plain"));

    storagesMockHelper.bulkDelete(List.of());

    // When — request the folder
    HttpResponse httpResponse = executeDeleteNodes(folderId);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    List<String> deletedIds =
        (List<String>) TestUtils.jsonResponseToValue(httpResponse.getBodyPayload(), "deleteNodes")
            .orElse(List.of());
    Assertions.assertThat(deletedIds).containsExactly(folderId);

    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors).isEmpty();

    Assertions.assertThat(nodeRepository.getNode(folderId)).isEmpty();
    Assertions.assertThat(nodeRepository.getNode(fileId)).isEmpty();
    // Tombstones cleaned up.
    Assertions.assertThat(tombstoneRepository.getTombstones()).isEmpty();
  }

  // --- Test 4: Folder with file, PowerStore fails — both folder AND file still deleted (DB-first) ---

  @Test
  void givenFolderWithFileAndPowerStoreFailsThenBothFolderAndFileAreDeletedAndTombstonesRemain() {
    // Given
    String folderId = "00000000-0000-0000-0000-100000000005";
    String fileId   = "00000000-0000-0000-0000-100000000006";

    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(new SimplePopulatorFolder(folderId, OWNER_ID, "folder"))
        .addNode(new PopulatorNode(
            fileId, OWNER_ID, OWNER_ID, folderId, "file.txt", "",
            NodeType.TEXT, "LOCAL_ROOT," + folderId, 1L, "text/plain"));

    storagesMockHelper.bulkDeleteError();

    // When — request the folder (which contains the file)
    HttpResponse httpResponse = executeDeleteNodes(folderId);

    // Then — DB-first: folder and file both deleted, no user error.
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    List<String> deletedIds =
        (List<String>) TestUtils.jsonResponseToValue(httpResponse.getBodyPayload(), "deleteNodes")
            .orElse(List.of());
    Assertions.assertThat(deletedIds).containsExactly(folderId);

    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors).isEmpty();

    // Both deleted from DB (PowerStore failure does not block DB delete).
    Assertions.assertThat(nodeRepository.getNode(folderId)).isEmpty();
    Assertions.assertThat(nodeRepository.getNode(fileId)).isEmpty();

    // Tombstone remains for PurgeService retry.
    Assertions.assertThat(tombstoneRepository.getTombstones()).hasSize(1);
  }

  // --- Test 5: Protective filter — nodeNotFound for missing/no-perm IDs ---

  @Test
  void givenMissingNodeIdThenNodeNotFoundErrorReturnedAndPresentNodeStillDeleted() {
    // Given
    String presentFileId = "00000000-0000-0000-0000-100000000009";
    String missingId     = "00000000-0000-0000-0000-100000000099";

    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(new SimplePopulatorTextFile(presentFileId, OWNER_ID, "file.txt"));

    storagesMockHelper.bulkDelete(List.of());

    // When
    HttpResponse httpResponse = executeDeleteNodes(presentFileId, missingId);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    List<String> deletedIds =
        (List<String>) TestUtils.jsonResponseToValue(httpResponse.getBodyPayload(), "deleteNodes")
            .orElse(List.of());
    Assertions.assertThat(deletedIds).containsExactly(presentFileId);

    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors).hasSize(1);

    Assertions.assertThat(nodeRepository.getNode(presentFileId)).isEmpty();
  }

  // --- Test 6: Null/empty JSON response from PowerStore — CORRECTED: null is NOT success, tombstone KEPT ---

  @Test
  void givenNullResponseFromPowerStoreThenNodeDeletedButTombstoneKeptForRetry() {
    // Given
    String file1Id = "00000000-0000-0000-0000-100000000012";

    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(new SimplePopulatorTextFile(file1Id, OWNER_ID, "file1.txt"));

    // {"ids":null} / "{}" — SDK may return null or throw NPE; BOTH are treated as connection failure.
    storagesMockHelper.bulkDeleteNullResponse();

    // When
    HttpResponse httpResponse = executeDeleteNodes(file1Id);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    List<String> deletedIds =
        (List<String>) TestUtils.jsonResponseToValue(httpResponse.getBodyPayload(), "deleteNodes")
            .orElse(List.of());
    Assertions.assertThat(deletedIds).containsExactly(file1Id);

    // Node deleted from DB (DB-first design).
    Assertions.assertThat(nodeRepository.getNode(file1Id)).isEmpty();
    // CORRECTED: tombstone must REMAIN — null/empty response is NOT a success signal.
    Assertions.assertThat(tombstoneRepository.getTombstones())
        .as("Tombstone must remain when PowerStore returns null/empty response (NOT a success signal)")
        .hasSize(1);
  }
}
