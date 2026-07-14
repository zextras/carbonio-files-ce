// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.acceptance;

import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.acceptance.seam.FilesTestApp;
import com.zextras.carbonio.files.acceptance.seam.impl.GuiceNettyFilesTestAppBuilder;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.api.utilities.entities.PopulatorNode;
import com.zextras.carbonio.files.api.utilities.entities.SimplePopulatorFolder;
import com.zextras.carbonio.files.api.utilities.entities.SimplePopulatorTextFile;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
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

  static FilesTestApp app;

  private static final String OWNER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

  @BeforeAll
  static void init() {
    app =
        GuiceNettyFilesTestAppBuilder.aFilesTestApp()
            .withDatabase()
            .withServiceDiscover()
            .withStorages()
            .withUserManagement(
                Map.of("fake-token", OWNER_ID))
            .build();
  }

  @AfterEach
  void cleanUp() {
    app.backdoor().resetDatabase();
    // Tombstones are not FK-linked to NODE so resetDatabase() doesn't clean them.
    app.backdoor().clearTombstones();
    app.mocks().reset();
  }

  @AfterAll
  static void cleanUpAll() {
    app.close();
  }

  private HttpResponse executeDeleteAllNodesAndBlobs() {
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("deleteAllNodesAndBlobs")
            .withString("user_id", OWNER_ID)
            .withWantedResultFormat("")
            .build();
    List<Map.Entry<String, String>> headers = List.of(Map.entry("Internal", ""));
    final HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", headers, bodyPayload);
    return app.send(httpRequest);
  }

  // --- Test 1: Happy path — all blobs succeed, returns true ---

  @Test
  void givenNodesOwnedByUserTheDeleteAllNodesAndBlobsShouldDeleteTheNodesAndTheBlobs() {
    // Given
    app.backdoor()
        .populator()
        .addNode(
            new SimplePopulatorTextFile(
                "00000000-0000-0000-0000-000000000002", OWNER_ID, "name.txt"))
        .addNode(
            new SimplePopulatorFolder(
                "00000000-0000-0000-0000-000000000003", OWNER_ID, "folder"));

    app.mocks().storagesBulkDeleteSucceeds(List.of());

    // When
    final HttpResponse httpResponse = executeDeleteAllNodesAndBlobs();

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    Optional<Object> result = TestUtils.jsonResponseToValue(httpResponse.getBodyPayload(), "deleteAllNodesAndBlobs");
    Assertions.assertThat(result).isNotEmpty();
    Assertions.assertThat(result).contains(true);

    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors).isEmpty();

    // Nodes deleted from DB.
    Assertions.assertThat(app.backdoor().nodeExists("00000000-0000-0000-0000-000000000002")).isFalse();
    Assertions.assertThat(app.backdoor().nodeExists("00000000-0000-0000-0000-000000000003")).isFalse();

    // Tombstones cleaned up.
    Assertions.assertThat(app.backdoor().tombstoneCount()).isEqualTo(0);
  }

  // --- Test 2: PowerStore fails — nodes still deleted (DB-first), returns true, tombstones remain ---
  // Old test "givenNodesOwnedByUserAndStoragesNotRespondingTheDeleteAllNodesAndBlobsShouldReturn200WithAnErrorCode"
  // is REMOVED because PowerStore failure no longer blocks deletion or causes a user error.

  @Test
  void givenNodesOwnedByUserAndPowerStoreFailsThenNodeStillDeletedAndReturnsTrue() {
    // Given
    String fileId   = "00000000-0000-0000-0000-000000000000";
    String folderId = "00000000-0000-0000-0000-000000000001";

    app.backdoor().populator()
        .addNode(new SimplePopulatorTextFile(fileId, OWNER_ID, "name.txt"))
        .addNode(new SimplePopulatorFolder(folderId, OWNER_ID, "folder"));

    // PowerStore HTTP 500 — complete failure.
    app.mocks().storagesBulkDeleteFails();

    // When
    final HttpResponse httpResponse = executeDeleteAllNodesAndBlobs();

    // Then — DB-first: nodes deleted, returns true, no user errors.
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Optional<Object> result = TestUtils.jsonResponseToValue(httpResponse.getBodyPayload(), "deleteAllNodesAndBlobs");
    Assertions.assertThat(result).contains(true);

    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors).isEmpty();

    // Both nodes deleted from DB (already committed before PowerStore call).
    Assertions.assertThat(app.backdoor().nodeExists(fileId)).isFalse();
    Assertions.assertThat(app.backdoor().nodeExists(folderId)).isFalse();

    // Tombstone remains for PurgeService retry.
    Assertions.assertThat(app.backdoor().tombstoneCount()).isEqualTo(1);
  }

  // --- Test 3: Folder with file, all blobs succeed — everything deleted ---

  @Test
  void givenAllFilesSucceedThenAllNodesIncludingFoldersAreDeleted() {
    // folderA/file1 + folderB/file2 — all succeed
    String folderAId = "00000000-0000-0000-0000-000000000040";
    String file1Id   = "00000000-0000-0000-0000-000000000041";
    String folderBId = "00000000-0000-0000-0000-000000000042";
    String file2Id   = "00000000-0000-0000-0000-000000000043";

    app.backdoor().populator()
        .addNode(new SimplePopulatorFolder(folderAId, OWNER_ID, "folderA"))
        .addNode(new PopulatorNode(file1Id, OWNER_ID, OWNER_ID, folderAId, "file1.txt", "", NodeType.TEXT, "LOCAL_ROOT," + folderAId, 1L, "text/plain"))
        .addNode(new SimplePopulatorFolder(folderBId, OWNER_ID, "folderB"))
        .addNode(new PopulatorNode(file2Id, OWNER_ID, OWNER_ID, folderBId, "file2.txt", "", NodeType.TEXT, "LOCAL_ROOT," + folderBId, 1L, "text/plain"));

    app.mocks().storagesBulkDeleteSucceeds(List.of());

    HttpResponse httpResponse = executeDeleteAllNodesAndBlobs();

    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Optional<Object> result = TestUtils.jsonResponseToValue(httpResponse.getBodyPayload(), "deleteAllNodesAndBlobs");
    Assertions.assertThat(result).contains(true);

    // Everything deleted.
    Assertions.assertThat(app.backdoor().nodeExists(folderAId)).isFalse();
    Assertions.assertThat(app.backdoor().nodeExists(file1Id)).isFalse();
    Assertions.assertThat(app.backdoor().nodeExists(folderBId)).isFalse();
    Assertions.assertThat(app.backdoor().nodeExists(file2Id)).isFalse();

    Assertions.assertThat(app.backdoor().tombstoneCount()).isEqualTo(0);
  }

  // --- Test 4: Folder + file, PowerStore fails — all deleted (DB-first), tombstones remain ---

  @Test
  void givenFolderWithFileAndPowerStoreFailsThenBothDeletedAndTombstonesRemain() {
    String folderId = "00000000-0000-0000-0000-000000000020";
    String fileId   = "00000000-0000-0000-0000-000000000021";

    app.backdoor().populator()
        .addNode(new SimplePopulatorFolder(folderId, OWNER_ID, "folder"))
        .addNode(new PopulatorNode(fileId, OWNER_ID, OWNER_ID, folderId, "file.txt", "", NodeType.TEXT, "LOCAL_ROOT," + folderId, 1L, "text/plain"));

    app.mocks().storagesBulkDeleteFails();

    HttpResponse httpResponse = executeDeleteAllNodesAndBlobs();

    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Optional<Object> result = TestUtils.jsonResponseToValue(httpResponse.getBodyPayload(), "deleteAllNodesAndBlobs");
    Assertions.assertThat(result).contains(true);

    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors).isEmpty();

    // Both deleted from DB.
    Assertions.assertThat(app.backdoor().nodeExists(folderId)).isFalse();
    Assertions.assertThat(app.backdoor().nodeExists(fileId)).isFalse();

    // Tombstone remains.
    Assertions.assertThat(app.backdoor().tombstoneCount()).isEqualTo(1);
  }
}
