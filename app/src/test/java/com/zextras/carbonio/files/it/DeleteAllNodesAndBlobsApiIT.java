// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code com.zextras.carbonio.files.acceptance.DeleteAllNodesAndBlobsApiIT} rewritten as an
 * out-of-process {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}. Exercises the
 * account-delete orchestration ({@code NodeDataFetcher#deleteAllNodesAndBlobsForUser}) end to end
 * through its sole entry point in the Quarkus rewrite: the trusted-caller {@code DELETE
 * /internal/nodes} REST endpoint ({@code InternalNodeResource}) — UNAUTHENTICATED (mesh mTLS is the
 * trust boundary, so no cookie/token filter runs), carrying the acting {@code userId} in the JSON
 * body. The legacy class drove this same core via the now-removed {@code deleteAllNodesAndBlobs}
 * GraphQL mutation; the endpoint changed, the delete/tombstone contract did not.
 *
 * <p>All 4 legacy scenarios and their assertions are preserved (happy path, PowerStore-outage,
 * folders-with-files, folder-with-file-and-outage); only the seeding (API calls capturing
 * server-generated ids), the storages-bulk-delete-failure trigger ({@link
 * FilesStackTestResource#getStoragesService()}'s {@code setBulkDeleteAlwaysThrows}, replacing the
 * seam's {@code Mocks#storagesBulkDeleteFails}), the tombstone-count assertions (raw JDBC via
 * {@link #tombstoneRowsForNode}, since tombstones are not otherwise API-observable), and the
 * transport changed. Blob presence is asserted directly on the shared storages fake ({@code
 * MockStoragesService#has}) — the legacy IT could only prove blob deletion indirectly via the
 * tombstone count.
 *
 * <p>The core invariant under test is DB-before-blob ordering: the metadata delete (tombstones +
 * node/version + folder cascade) commits FIRST, then the best-effort storages {@code bulkDelete}
 * runs. A blob-deletion failure therefore never blocks the DB delete nor surfaces an error — the
 * node is gone and {@code deleted=true} is returned regardless, with the tombstone kept for the
 * purge retry.
 */
class DeleteAllNodesAndBlobsApiIT extends AbstractFilesIT {

  private static final String OWNER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OWNER_COOKIE = "ZM_AUTH_TOKEN=fake-token";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", OWNER_ID);
  }

  /** Trusted-caller {@code DELETE /internal/nodes}: no cookie, {@code userId} in the JSON body. */
  private static Response executeDeleteAllNodesAndBlobs(String userId) {
    return RestAssured.given()
        .contentType("application/json")
        .body("{\"userId\":\"" + userId + "\"}")
        .delete("/internal/nodes");
  }

  private static byte[] content() {
    return "content".getBytes(StandardCharsets.UTF_8);
  }

  // --- Test 1: happy path — a user's nodes and their blobs are deleted, returns true ---

  @Test
  void givenNodesOwnedByUserThenDeleteAllDeletesNodesAndBlobsAndReturnsTrue() throws SQLException {
    // Given
    String fileId = seedFile("name.txt", LOCAL_ROOT, content(), OWNER_COOKIE);
    String folderId = seedFolder("folder", LOCAL_ROOT, OWNER_COOKIE);
    // The upload stored the blob; storages bulk-delete defaults to full success (no mock setup).
    Assertions.assertThat(FilesStackTestResource.getStoragesService().has(fileId, 1)).isTrue();

    // When
    Response response = executeDeleteAllNodesAndBlobs(OWNER_ID);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(response.jsonPath().getBoolean("deleted")).isTrue();

    // Nodes deleted from DB.
    Assertions.assertThat(nodeExists(fileId, OWNER_COOKIE)).isFalse();
    Assertions.assertThat(nodeExists(folderId, OWNER_COOKIE)).isFalse();

    // Blob deleted from storages and its tombstone removed after the confirmed bulk-delete.
    Assertions.assertThat(FilesStackTestResource.getStoragesService().has(fileId, 1)).isFalse();
    Assertions.assertThat(tombstoneRowsForNode(fileId)).isEqualTo(0);
  }

  // --- Test 2: PowerStore fails — DB nodes STILL deleted, tombstones remain, returns true ---
  // DB-before-blob ordering: the metadata delete is committed before the best-effort bulkDelete.

  @Test
  void givenPowerStoreFailsThenNodesStillDeletedTombstonesRemainAndReturnsTrue()
      throws SQLException {
    // Given
    String fileId = seedFile("name.txt", LOCAL_ROOT, content(), OWNER_COOKIE);
    String folderId = seedFolder("folder", LOCAL_ROOT, OWNER_COOKIE);

    // PowerStore HTTP 500 — complete outage.
    FilesStackTestResource.getStoragesService().setBulkDeleteAlwaysThrows(true);

    // When
    Response response = executeDeleteAllNodesAndBlobs(OWNER_ID);

    // Then — DB-first: nodes deleted, no error, returns true.
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(response.jsonPath().getBoolean("deleted")).isTrue();

    Assertions.assertThat(nodeExists(fileId, OWNER_COOKIE)).isFalse();
    Assertions.assertThat(nodeExists(folderId, OWNER_COOKIE)).isFalse();

    // Blob remains (bulk-delete failed) and its tombstone is kept for the PurgeService retry.
    Assertions.assertThat(FilesStackTestResource.getStoragesService().has(fileId, 1)).isTrue();
    Assertions.assertThat(tombstoneRowsForNode(fileId)).isEqualTo(1);
  }

  // --- Test 3: folders each holding a file, all blobs succeed — everything deleted ---

  @Test
  void givenFoldersWithFilesAndAllBlobsSucceedThenEverythingIsDeleted() throws SQLException {
    // Given — folderA/file1 + folderB/file2.
    String folderAId = seedFolder("folderA", LOCAL_ROOT, OWNER_COOKIE);
    String file1Id = seedFile("file1.txt", folderAId, content(), OWNER_COOKIE);
    String folderBId = seedFolder("folderB", LOCAL_ROOT, OWNER_COOKIE);
    String file2Id = seedFile("file2.txt", folderBId, content(), OWNER_COOKIE);

    // When
    Response response = executeDeleteAllNodesAndBlobs(OWNER_ID);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(response.jsonPath().getBoolean("deleted")).isTrue();

    // Everything deleted, including the folders.
    Assertions.assertThat(nodeExists(folderAId, OWNER_COOKIE)).isFalse();
    Assertions.assertThat(nodeExists(file1Id, OWNER_COOKIE)).isFalse();
    Assertions.assertThat(nodeExists(folderBId, OWNER_COOKIE)).isFalse();
    Assertions.assertThat(nodeExists(file2Id, OWNER_COOKIE)).isFalse();

    // Both blobs deleted and both tombstones removed.
    Assertions.assertThat(FilesStackTestResource.getStoragesService().has(file1Id, 1)).isFalse();
    Assertions.assertThat(FilesStackTestResource.getStoragesService().has(file2Id, 1)).isFalse();
    Assertions.assertThat(tombstoneRowsForNode(file1Id) + tombstoneRowsForNode(file2Id))
        .isEqualTo(0);
  }

  // --- Test 4: folder with a file, PowerStore fails — both deleted (DB-first), tombstone remains
  // ---

  @Test
  void givenFolderWithFileAndPowerStoreFailsThenBothDeletedAndTombstonesRemain()
      throws SQLException {
    // Given
    String folderId = seedFolder("folder", LOCAL_ROOT, OWNER_COOKIE);
    String fileId = seedFile("file.txt", folderId, content(), OWNER_COOKIE);

    FilesStackTestResource.getStoragesService().setBulkDeleteAlwaysThrows(true);

    // When
    Response response = executeDeleteAllNodesAndBlobs(OWNER_ID);

    // Then — DB-first: folder and file both deleted, no error, returns true.
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(response.jsonPath().getBoolean("deleted")).isTrue();

    Assertions.assertThat(nodeExists(folderId, OWNER_COOKIE)).isFalse();
    Assertions.assertThat(nodeExists(fileId, OWNER_COOKIE)).isFalse();

    // Tombstone remains for the PurgeService retry.
    Assertions.assertThat(tombstoneRowsForNode(fileId)).isEqualTo(1);
  }
}
