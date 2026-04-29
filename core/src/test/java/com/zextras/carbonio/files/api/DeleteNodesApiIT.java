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

    Assertions.assertThat(nodeRepository.getNode(file1Id)).isEmpty();
    Assertions.assertThat(nodeRepository.getNode(file2Id)).isEmpty();
  }

  // --- Test 2: Partial failure — 2 files, one blob fails ---

  @Test
  void givenTwoFilesAndOneBlobFailsThenOnlySucceededFileIsDeletedAndFailedFileReturnsError() {
    // Given
    String file1Id = "00000000-0000-0000-0000-100000000003";
    String file2Id = "00000000-0000-0000-0000-100000000004";

    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(new SimplePopulatorTextFile(file1Id, OWNER_ID, "file1.txt"))
        .addNode(new SimplePopulatorTextFile(file2Id, OWNER_ID, "file2.txt"));

    // file1 blob deletion fails
    storagesMockHelper.bulkDelete(List.of(file1Id));

    // When
    HttpResponse httpResponse = executeDeleteNodes(file1Id, file2Id);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    List<String> deletedIds =
        (List<String>) TestUtils.jsonResponseToValue(httpResponse.getBodyPayload(), "deleteNodes")
            .orElse(List.of());
    Assertions.assertThat(deletedIds).containsExactly(file2Id);

    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors).isNotEmpty();

    // file1 stays in DB (blob failed), file2 is deleted
    Assertions.assertThat(nodeRepository.getNode(file1Id)).isPresent();
    Assertions.assertThat(nodeRepository.getNode(file2Id)).isEmpty();
  }

  // --- Test 3: Folder with file, blob fails — both stay in DB ---

  @Test
  void givenFolderWithFileAndFileBlobFailsThenBothFolderAndFileStayInDb() {
    // Given
    String folderId = "00000000-0000-0000-0000-100000000005";
    String fileId   = "00000000-0000-0000-0000-100000000006";

    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(new SimplePopulatorFolder(folderId, OWNER_ID, "folder"))
        .addNode(new PopulatorNode(
            fileId, OWNER_ID, OWNER_ID, folderId, "file.txt", "",
            NodeType.TEXT, "LOCAL_ROOT," + folderId, 1L, "text/plain"));

    // file blob deletion fails
    storagesMockHelper.bulkDelete(List.of(fileId));

    // When — request the folder (which contains the file)
    HttpResponse httpResponse = executeDeleteNodes(folderId);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors).isNotEmpty();

    // folder and file both stay (file blob failed → folder cannot be deleted)
    Assertions.assertThat(nodeRepository.getNode(folderId)).isPresent();
    Assertions.assertThat(nodeRepository.getNode(fileId)).isPresent();
  }

  // --- Test 4: Folder with file, blob succeeds — both deleted ---

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
  }

  // --- Test 5: Total failure — 2 files, all blobs fail ---

  @Test
  void givenTwoFilesAndAllBlobsFailThenNothingIsDeletedAndAllErrorsReturned() {
    // Given
    String file1Id = "00000000-0000-0000-0000-100000000012";
    String file2Id = "00000000-0000-0000-0000-100000000013";

    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(new SimplePopulatorTextFile(file1Id, OWNER_ID, "file1.txt"))
        .addNode(new SimplePopulatorTextFile(file2Id, OWNER_ID, "file2.txt"));

    storagesMockHelper.bulkDelete(List.of(file1Id, file2Id));

    // When
    HttpResponse httpResponse = executeDeleteNodes(file1Id, file2Id);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    List<String> deletedIds =
        (List<String>) TestUtils.jsonResponseToValue(httpResponse.getBodyPayload(), "deleteNodes")
            .orElse(List.of());
    Assertions.assertThat(deletedIds).isEmpty();

    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors).hasSize(2);

    // Both stay in DB
    Assertions.assertThat(nodeRepository.getNode(file1Id)).isPresent();
    Assertions.assertThat(nodeRepository.getNode(file2Id)).isPresent();
  }

  // --- Test 6: Mixed folder — folder with 2 files, one blob fails ---

  @Test
  void givenFolderWithTwoFilesAndOneBlobFailsThenFolderAndFailedFileStayAndSucceededFileIsDeleted() {
    // Given
    String folderId = "00000000-0000-0000-0000-100000000009";
    String file1Id  = "00000000-0000-0000-0000-100000000010";
    String file2Id  = "00000000-0000-0000-0000-100000000011";

    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(new SimplePopulatorFolder(folderId, OWNER_ID, "folder"))
        .addNode(new PopulatorNode(
            file1Id, OWNER_ID, OWNER_ID, folderId, "file1.txt", "",
            NodeType.TEXT, "LOCAL_ROOT," + folderId, 1L, "text/plain"))
        .addNode(new PopulatorNode(
            file2Id, OWNER_ID, OWNER_ID, folderId, "file2.txt", "",
            NodeType.TEXT, "LOCAL_ROOT," + folderId, 1L, "text/plain"));

    // file1 blob deletion fails
    storagesMockHelper.bulkDelete(List.of(file1Id));

    // When — request the folder (contains both files)
    HttpResponse httpResponse = executeDeleteNodes(folderId);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors).isNotEmpty();

    // folder and file1 stay (file1 failed → folder non-empty)
    Assertions.assertThat(nodeRepository.getNode(folderId)).isPresent();
    Assertions.assertThat(nodeRepository.getNode(file1Id)).isPresent();
    // file2 is deleted (its blob succeeded)
    Assertions.assertThat(nodeRepository.getNode(file2Id)).isEmpty();
  }
}
