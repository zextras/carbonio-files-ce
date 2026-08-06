// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code com.zextras.carbonio.files.acceptance.DeleteNodesApiIT} rewritten as an out-of-process
 * {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}. All 8 methods and their assertions
 * are preserved verbatim; only the seeding mechanism (API calls capturing server-generated ids),
 * the storages-bulk-delete-failure triggers ({@link FilesStackTestResource#getStoragesService()}'s
 * {@code setBulkDeleteAlwaysThrows}/{@code setBulkDeleteReturnsNull}, replacing the seam's {@code
 * Mocks#storagesBulkDeleteFails}/{@code storagesBulkDeleteReturnsNullResponse}), the tombstone-
 * count assertions (raw JDBC via {@link #tombstoneRowsForNode}, since tombstones are not otherwise
 * API-observable), and the transport changed.
 */
class DeleteNodesApiIT extends AbstractFilesIT {

  private static final String OWNER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OWNER_COOKIE = "ZM_AUTH_TOKEN=fake-token";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", OWNER_ID);
  }

  private Response executeDeleteNodes(String... nodeIds) {
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("deleteNodes")
            .withListOfStrings("node_ids", nodeIds)
            .withWantedResultFormat("")
            .build();
    return graphql(bodyPayload, OWNER_COOKIE);
  }

  @SuppressWarnings("unchecked")
  private List<String> deletedIds(Response response) {
    return (List<String>)
        TestUtils.jsonResponseToValue(response.getBody().asString(), "deleteNodes")
            .orElse(List.of());
  }

  // --- Test 1: Happy path — 2 files, all blobs succeed ---
  // Tombstones are created then removed after confirmed bulkDelete.

  @Test
  void givenTwoFilesAndAllBlobsSucceedThenBothAreDeletedAndReturnedInResponse()
      throws SQLException {
    // Given
    String file1Id =
        seedFile("file1.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    String file2Id =
        seedFile("file2.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    // storages bulk-delete defaults to full success (empty failed list) — no mock setup needed.

    // When
    Response response = executeDeleteNodes(file1Id, file2Id);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(deletedIds(response)).containsExactlyInAnyOrder(file1Id, file2Id);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(response.getBody().asString())).isEmpty();

    // Both files deleted from DB.
    Assertions.assertThat(nodeExists(file1Id, OWNER_COOKIE)).isFalse();
    Assertions.assertThat(nodeExists(file2Id, OWNER_COOKIE)).isFalse();

    // Tombstones cleaned up after confirmed blob delete.
    Assertions.assertThat(tombstoneRowsForNode(file1Id) + tombstoneRowsForNode(file2Id))
        .isEqualTo(0);
  }

  // --- Test 2: PowerStore fails (exception) — files still deleted from DB, no error to user ---
  // Core tombstone invariant: DB delete committed first; blob failure is best-effort.

  @Test
  void givenTwoFilesAndPowerStoreFailsThenBothAreStillDeletedFromDbAndNoErrorReturnedToUser()
      throws SQLException {
    // Given
    String file1Id =
        seedFile("file1.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    String file2Id =
        seedFile("file2.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);

    // PowerStore returns HTTP 500 — complete failure.
    FilesStackTestResource.getStoragesService().setBulkDeleteAlwaysThrows(true);

    // When
    Response response = executeDeleteNodes(file1Id, file2Id);

    // Then — DB-first: both nodes deleted, no error to user.
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(deletedIds(response)).containsExactlyInAnyOrder(file1Id, file2Id);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(response.getBody().asString())).isEmpty();

    // Both nodes deleted from DB (already committed before PowerStore call).
    Assertions.assertThat(nodeExists(file1Id, OWNER_COOKIE)).isFalse();
    Assertions.assertThat(nodeExists(file2Id, OWNER_COOKIE)).isFalse();

    // Tombstones remain for PurgeService retry.
    Assertions.assertThat(tombstoneRowsForNode(file1Id) + tombstoneRowsForNode(file2Id))
        .isEqualTo(2);
  }

  // --- Test 3: Folder with file, all blobs succeed — both deleted, no errors ---

  @Test
  void givenFolderWithFileAndBlobSucceedsThenBothFolderAndFileAreDeleted() throws SQLException {
    // Given
    String folderId = seedFolder("folder", LOCAL_ROOT, OWNER_COOKIE);
    String fileId =
        seedFile("file.txt", folderId, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);

    // When — request the folder
    Response response = executeDeleteNodes(folderId);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(deletedIds(response)).containsExactly(folderId);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(response.getBody().asString())).isEmpty();

    Assertions.assertThat(nodeExists(folderId, OWNER_COOKIE)).isFalse();
    Assertions.assertThat(nodeExists(fileId, OWNER_COOKIE)).isFalse();
    // Tombstones cleaned up.
    Assertions.assertThat(tombstoneRowsForNode(fileId)).isEqualTo(0);
  }

  // --- Test 4: Folder with file, PowerStore fails — both folder AND file still deleted (DB-first)
  // ---

  @Test
  void givenFolderWithFileAndPowerStoreFailsThenBothFolderAndFileAreDeletedAndTombstonesRemain()
      throws SQLException {
    // Given
    String folderId = seedFolder("folder", LOCAL_ROOT, OWNER_COOKIE);
    String fileId =
        seedFile("file.txt", folderId, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);

    FilesStackTestResource.getStoragesService().setBulkDeleteAlwaysThrows(true);

    // When — request the folder (which contains the file)
    Response response = executeDeleteNodes(folderId);

    // Then — DB-first: folder and file both deleted, no user error.
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(deletedIds(response)).containsExactly(folderId);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(response.getBody().asString())).isEmpty();

    // Both deleted from DB (PowerStore failure does not block DB delete).
    Assertions.assertThat(nodeExists(folderId, OWNER_COOKIE)).isFalse();
    Assertions.assertThat(nodeExists(fileId, OWNER_COOKIE)).isFalse();

    // Tombstone remains for PurgeService retry.
    Assertions.assertThat(tombstoneRowsForNode(fileId)).isEqualTo(1);
  }

  // --- Test 5: Protective filter — nodeNotFound for missing/no-perm IDs ---

  @Test
  void givenMissingNodeIdThenNodeNotFoundErrorReturnedAndPresentNodeStillDeleted() {
    // Given
    String presentFileId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    String missingId = "00000000-0000-0000-0000-100000000099";

    // When
    Response response = executeDeleteNodes(presentFileId, missingId);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(deletedIds(response)).containsExactly(presentFileId);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(response.getBody().asString())).hasSize(1);

    Assertions.assertThat(nodeExists(presentFileId, OWNER_COOKIE)).isFalse();
  }

  // --- Test 6: Null/empty JSON response from PowerStore — null is NOT success, tombstone KEPT ---

  @Test
  void givenNullResponseFromPowerStoreThenNodeDeletedButTombstoneKeptForRetry()
      throws SQLException {
    // Given
    String file1Id =
        seedFile("file1.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);

    // {"ids":null} / "{}" — SDK may return null or throw NPE; BOTH are treated as connection
    // failure.
    FilesStackTestResource.getStoragesService().setBulkDeleteReturnsNull(true);

    // When
    Response response = executeDeleteNodes(file1Id);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(deletedIds(response)).containsExactly(file1Id);

    // Node deleted from DB (DB-first design).
    Assertions.assertThat(nodeExists(file1Id, OWNER_COOKIE)).isFalse();
    // Tombstone must REMAIN — null/empty response is NOT a success signal.
    Assertions.assertThat(tombstoneRowsForNode(file1Id))
        .as(
            "Tombstone must remain when PowerStore returns null/empty response (NOT a success"
                + " signal)")
        .isEqualTo(1);
  }

  // --- Test 7/8: Regression — deleting a FLAGGED node must succeed. ---
  // Before the fix, the dead @ManyToOne "node" shadow field on NodeCustomAttributes made
  // Hibernate's
  // flush-time transient-reference check throw TransientPropertyValueException when a flagged node
  // was removed, surfacing to GraphQL as errorCode NODE_WRITE_ERROR.

  @Test
  void givenAFlaggedFileWhenDeleteNodesThenItSucceeds() {
    // Given
    String fileId =
        seedFile(
            "flagged-file.txt",
            LOCAL_ROOT,
            "content".getBytes(StandardCharsets.UTF_8),
            OWNER_COOKIE);
    seedFlag(fileId, OWNER_COOKIE);

    // When
    Response response = executeDeleteNodes(fileId);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(deletedIds(response)).containsExactly(fileId);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(response.getBody().asString())).isEmpty();

    Assertions.assertThat(nodeExists(fileId, OWNER_COOKIE)).isFalse();
  }

  @Test
  void givenAFlaggedFolderWhenDeleteNodesThenItSucceeds() {
    // Given
    String folderId = seedFolder("flagged-folder", LOCAL_ROOT, OWNER_COOKIE);
    seedFlag(folderId, OWNER_COOKIE);

    // When
    Response response = executeDeleteNodes(folderId);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(deletedIds(response)).containsExactly(folderId);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(response.getBody().asString())).isEmpty();

    Assertions.assertThat(nodeExists(folderId, OWNER_COOKIE)).isFalse();
  }
}
