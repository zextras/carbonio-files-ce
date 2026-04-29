// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
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
import com.zextras.carbonio.files.dal.repositories.interfaces.LinkRepository;
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
import java.util.Optional;

class DeleteAllNodesAndBlobsApiIT {

  static Simulator simulator;
  static StoragesMockHelper storagesMockHelper;
  static NodeRepository nodeRepository;
  static FileVersionRepository fileVersionRepository;
  static LinkRepository linkRepository;

  @BeforeAll
  static void init() {
    simulator =
        SimulatorBuilder.aSimulator()
            .init()
            .withDatabase()
            .withServiceDiscover()
            .withStorages()
            .withUserManagement( // create a fake token to use in cookie for auth
                Map.of(
                    "fake-token",
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"))
            .build()
            .start();

    final Injector injector = simulator.getInjector();
    nodeRepository = injector.getInstance(NodeRepository.class);
    fileVersionRepository = injector.getInstance(FileVersionRepository.class);
    linkRepository = injector.getInstance(LinkRepository.class);
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

  @Test
  void givenNodesOwnedByUserAndStoragesNotRespondingTheDeleteAllNodesAndBlobsShouldReturn200WithAnErrorCode() {
    // Given
    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(
            new SimplePopulatorTextFile(
                "00000000-0000-0000-0000-000000000000", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "name.txt"))
        .addNode(
            new SimplePopulatorFolder(
                "00000000-0000-0000-0000-000000000001", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "folder"
            )
        );

    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("deleteAllNodesAndBlobs")
            .withString("user_id", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
            .withWantedResultFormat("")
            .build();


    List<Map.Entry<String, String>> headers = List.of(Map.entry("Internal", ""));
    final HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", headers, bodyPayload);

    // When
    final HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    final List<String> errorResponse =
        TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errorResponse)
        .hasSize(1)
        .containsExactly("Storages returned an error while trying to delete all blobs");

  }

  @Test
  void givenNodesOwnedByUserTheDeleteAllNodesAndBlobsShouldDeleteTheNodesAndTheBlobs() {
    // Given
    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(
            new SimplePopulatorTextFile(
                "00000000-0000-0000-0000-000000000002", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "name.txt"))
        .addNode(
            new SimplePopulatorFolder(
                "00000000-0000-0000-0000-000000000003", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "folder"
            )
        );

    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("deleteAllNodesAndBlobs")
            .withString("user_id", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
            .withWantedResultFormat("")
            .build();

    storagesMockHelper.bulkDelete(List.of());

    List<Map.Entry<String, String>> headers = List.of(Map.entry("Internal", ""));
    final HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", headers, bodyPayload);

    // When
    final HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    Optional<Object> result = TestUtils.jsonResponseToValue(httpResponse.getBodyPayload(), "deleteAllNodesAndBlobs");

    Assertions.assertThat(result).isNotEmpty();
    Assertions.assertThat(result).contains(true);

  }

  // --- Helpers for common request pattern ---

  private static final String OWNER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

  private HttpResponse executeDeleteAllNodesAndBlobs() {
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("deleteAllNodesAndBlobs")
            .withString("user_id", OWNER_ID)
            .withWantedResultFormat("")
            .build();
    List<Map.Entry<String, String>> headers = List.of(Map.entry("Internal", ""));
    final HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", headers, bodyPayload);
    return TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());
  }

  // --- Folder pruning tests ---

  @Test
  void givenFailedFileInFolderAndSuccessfulFileInAnotherFolderThenOnlySuccessfulSubtreeIsDeleted() {
    // folderA/file1 (FAILS) + folderB/file2 (succeeds)
    String folderAId = "00000000-0000-0000-0000-000000000010";
    String file1Id   = "00000000-0000-0000-0000-000000000011";
    String folderBId = "00000000-0000-0000-0000-000000000012";
    String file2Id   = "00000000-0000-0000-0000-000000000013";

    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(new SimplePopulatorFolder(folderAId, OWNER_ID, "folderA"))
        .addNode(new PopulatorNode(file1Id, OWNER_ID, OWNER_ID, folderAId, "file1.txt", "", NodeType.TEXT, "LOCAL_ROOT," + folderAId, 1L, "text/plain"))
        .addNode(new SimplePopulatorFolder(folderBId, OWNER_ID, "folderB"))
        .addNode(new PopulatorNode(file2Id, OWNER_ID, OWNER_ID, folderBId, "file2.txt", "", NodeType.TEXT, "LOCAL_ROOT," + folderBId, 1L, "text/plain"));

    storagesMockHelper.bulkDelete(List.of(file1Id));

    HttpResponse httpResponse = executeDeleteAllNodesAndBlobs();

    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Optional<Object> result = TestUtils.jsonResponseToValue(httpResponse.getBodyPayload(), "deleteAllNodesAndBlobs");
    Assertions.assertThat(result).contains(false);

    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors).isNotEmpty();

    // file1 + folderA still in DB (folder non-empty because file1 remains)
    Assertions.assertThat(nodeRepository.getNode(file1Id)).isPresent();
    Assertions.assertThat(nodeRepository.getNode(folderAId)).isPresent();
    // file2 + folderB deleted (folder empty after file2 deleted)
    Assertions.assertThat(nodeRepository.getNode(file2Id)).isEmpty();
    Assertions.assertThat(nodeRepository.getNode(folderBId)).isEmpty();
  }

  @Test
  void givenTwoFilesInSameFolderAndOneFailsThenFolderAndFailedFileStay() {
    // folder/file1 (FAILS) + folder/file2 (succeeds) → folder stays because file1 is still inside
    String folderId = "00000000-0000-0000-0000-000000000020";
    String file1Id  = "00000000-0000-0000-0000-000000000021";
    String file2Id  = "00000000-0000-0000-0000-000000000022";

    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(new SimplePopulatorFolder(folderId, OWNER_ID, "folder"))
        .addNode(new PopulatorNode(file1Id, OWNER_ID, OWNER_ID, folderId, "file1.txt", "", NodeType.TEXT, "LOCAL_ROOT," + folderId, 1L, "text/plain"))
        .addNode(new PopulatorNode(file2Id, OWNER_ID, OWNER_ID, folderId, "file2.txt", "", NodeType.TEXT, "LOCAL_ROOT," + folderId, 1L, "text/plain"));

    storagesMockHelper.bulkDelete(List.of(file1Id));

    HttpResponse httpResponse = executeDeleteAllNodesAndBlobs();

    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Optional<Object> result = TestUtils.jsonResponseToValue(httpResponse.getBodyPayload(), "deleteAllNodesAndBlobs");
    Assertions.assertThat(result).contains(false);

    // file1 stays (failed), folder stays (still contains file1)
    Assertions.assertThat(nodeRepository.getNode(file1Id)).isPresent();
    Assertions.assertThat(nodeRepository.getNode(folderId)).isPresent();
    // file2 deleted (success)
    Assertions.assertThat(nodeRepository.getNode(file2Id)).isEmpty();
  }

  @Test
  void givenDeepNestedFolderWithFailedLeafFileThenAllAncestorFoldersStay() {
    // folderA/folderB/file1 (FAILS) → all three stay
    String folderAId = "00000000-0000-0000-0000-000000000030";
    String folderBId = "00000000-0000-0000-0000-000000000031";
    String file1Id   = "00000000-0000-0000-0000-000000000032";

    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(new SimplePopulatorFolder(folderAId, OWNER_ID, "folderA"))
        .addNode(new PopulatorNode(folderBId, OWNER_ID, OWNER_ID, folderAId, "folderB", "", NodeType.FOLDER, "LOCAL_ROOT," + folderAId, 0L, null))
        .addNode(new PopulatorNode(file1Id, OWNER_ID, OWNER_ID, folderBId, "file1.txt", "", NodeType.TEXT, "LOCAL_ROOT," + folderAId + "," + folderBId, 1L, "text/plain"));

    storagesMockHelper.bulkDelete(List.of(file1Id));

    HttpResponse httpResponse = executeDeleteAllNodesAndBlobs();

    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Optional<Object> result = TestUtils.jsonResponseToValue(httpResponse.getBodyPayload(), "deleteAllNodesAndBlobs");
    Assertions.assertThat(result).contains(false);

    // All three stay: file1 failed, folderB contains file1, folderA contains folderB
    Assertions.assertThat(nodeRepository.getNode(file1Id)).isPresent();
    Assertions.assertThat(nodeRepository.getNode(folderBId)).isPresent();
    Assertions.assertThat(nodeRepository.getNode(folderAId)).isPresent();
  }

  @Test
  void givenAllFilesSucceedThenAllNodesIncludingFoldersAreDeleted() {
    // folderA/file1 + folderB/file2 — all succeed
    String folderAId = "00000000-0000-0000-0000-000000000040";
    String file1Id   = "00000000-0000-0000-0000-000000000041";
    String folderBId = "00000000-0000-0000-0000-000000000042";
    String file2Id   = "00000000-0000-0000-0000-000000000043";

    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(new SimplePopulatorFolder(folderAId, OWNER_ID, "folderA"))
        .addNode(new PopulatorNode(file1Id, OWNER_ID, OWNER_ID, folderAId, "file1.txt", "", NodeType.TEXT, "LOCAL_ROOT," + folderAId, 1L, "text/plain"))
        .addNode(new SimplePopulatorFolder(folderBId, OWNER_ID, "folderB"))
        .addNode(new PopulatorNode(file2Id, OWNER_ID, OWNER_ID, folderBId, "file2.txt", "", NodeType.TEXT, "LOCAL_ROOT," + folderBId, 1L, "text/plain"));

    storagesMockHelper.bulkDelete(List.of());

    HttpResponse httpResponse = executeDeleteAllNodesAndBlobs();

    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Optional<Object> result = TestUtils.jsonResponseToValue(httpResponse.getBodyPayload(), "deleteAllNodesAndBlobs");
    Assertions.assertThat(result).contains(true);

    // Everything deleted
    Assertions.assertThat(nodeRepository.getNode(folderAId)).isEmpty();
    Assertions.assertThat(nodeRepository.getNode(file1Id)).isEmpty();
    Assertions.assertThat(nodeRepository.getNode(folderBId)).isEmpty();
    Assertions.assertThat(nodeRepository.getNode(file2Id)).isEmpty();
  }

  @Test
  void givenEmptyFolderAndAllFilesSucceedThenEmptyFolderIsAlsoDeleted() {
    // emptyFolder + file1 (root-level) — both succeed
    String emptyFolderId = "00000000-0000-0000-0000-000000000050";
    String file1Id       = "00000000-0000-0000-0000-000000000051";

    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(new SimplePopulatorFolder(emptyFolderId, OWNER_ID, "emptyFolder"))
        .addNode(new SimplePopulatorTextFile(file1Id, OWNER_ID, "file1.txt"));

    storagesMockHelper.bulkDelete(List.of());

    HttpResponse httpResponse = executeDeleteAllNodesAndBlobs();

    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Optional<Object> result = TestUtils.jsonResponseToValue(httpResponse.getBodyPayload(), "deleteAllNodesAndBlobs");
    Assertions.assertThat(result).contains(true);

    Assertions.assertThat(nodeRepository.getNode(emptyFolderId)).isEmpty();
    Assertions.assertThat(nodeRepository.getNode(file1Id)).isEmpty();
  }

}
