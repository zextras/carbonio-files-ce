// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.tasks;

import com.google.inject.Injector;
import com.zextras.carbonio.files.Simulator;
import com.zextras.carbonio.files.Simulator.SimulatorBuilder;
import com.zextras.carbonio.files.api.utilities.DatabasePopulator;
import com.zextras.carbonio.files.api.utilities.entities.SimplePopulatorTextFile;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.utilities.StoragesMockHelper;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PurgeServiceIT {

  static Simulator simulator;
  static StoragesMockHelper storagesMockHelper;
  static NodeRepository nodeRepository;
  static FileVersionRepository fileVersionRepository;
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
    fileVersionRepository = injector.getInstance(FileVersionRepository.class);
    purgeService = injector.getInstance(PurgeService.class);
    storagesMockHelper = new StoragesMockHelper(simulator.getStoragesMock());
  }

  @AfterEach
  void cleanUp() {
    simulator.resetDatabase();
    simulator.reinitializeMocks();
  }

  @AfterAll
  static void cleanUpAll() {
    simulator.stopAll();
  }

  // --- Test 1: Happy path — 2 trashed files, all blobs succeed → both deleted ---

  @Test
  void givenTwoTrashedFilesAndAllBlobsSucceedThenBothAreDeletedFromDb() {
    String file1Id = "00000000-0000-0000-0000-300000000001";
    String file2Id = "00000000-0000-0000-0000-300000000002";

    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(new SimplePopulatorTextFile(file1Id, OWNER_ID, "file1.txt"))
        .addNode(new SimplePopulatorTextFile(file2Id, OWNER_ID, "file2.txt"))
        .addNodeToTrash(file1Id, "LOCAL_ROOT")
        .addNodeToTrash(file2Id, "LOCAL_ROOT");

    storagesMockHelper.bulkDelete(List.of());

    purgeService.purgeTrashedNodes(0);

    Assertions.assertThat(nodeRepository.getNode(file1Id)).isEmpty();
    Assertions.assertThat(nodeRepository.getNode(file2Id)).isEmpty();
  }

  // --- Test 2: Partial failure — 2 trashed files, one blob fails → only succeeded deleted ---

  @Test
  void givenTwoTrashedFilesAndOneBlobFailsThenOnlySucceededFileIsDeleted() {
    String file1Id = "00000000-0000-0000-0000-300000000003";
    String file2Id = "00000000-0000-0000-0000-300000000004";

    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(new SimplePopulatorTextFile(file1Id, OWNER_ID, "file1.txt"))
        .addNode(new SimplePopulatorTextFile(file2Id, OWNER_ID, "file2.txt"))
        .addNodeToTrash(file1Id, "LOCAL_ROOT")
        .addNodeToTrash(file2Id, "LOCAL_ROOT");

    storagesMockHelper.bulkDelete(List.of(file1Id));

    purgeService.purgeTrashedNodes(0);

    Assertions.assertThat(nodeRepository.getNode(file1Id)).isPresent();
    Assertions.assertThat(nodeRepository.getNode(file2Id)).isEmpty();
  }

  // --- Test 3: Total PowerStore error → nothing deleted ---

  @Test
  void givenTwoTrashedFilesAndPowerStoreReturnsErrorThenNothingIsDeleted() {
    String file1Id = "00000000-0000-0000-0000-300000000005";
    String file2Id = "00000000-0000-0000-0000-300000000006";

    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(new SimplePopulatorTextFile(file1Id, OWNER_ID, "file1.txt"))
        .addNode(new SimplePopulatorTextFile(file2Id, OWNER_ID, "file2.txt"))
        .addNodeToTrash(file1Id, "LOCAL_ROOT")
        .addNodeToTrash(file2Id, "LOCAL_ROOT");

    storagesMockHelper.bulkDeleteError();

    purgeService.purgeTrashedNodes(0);

    Assertions.assertThat(nodeRepository.getNode(file1Id)).isPresent();
    Assertions.assertThat(nodeRepository.getNode(file2Id)).isPresent();
  }
}
