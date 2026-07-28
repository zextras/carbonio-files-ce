// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code com.zextras.carbonio.files.acceptance.TombstoneDeleteIT} rewritten as an out-of-process
 * {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}. Covers the tombstone sync-path
 * lifecycle:
 *
 * <ol>
 *   <li>tombstone created on delete then removed after a successful bulkDelete
 *   <li>tombstone remains when bulkDelete reports the blob as failed
 *   <li>tombstone remains when PowerStore is completely unavailable (500 outage)
 * </ol>
 *
 * <p>These tests mirror the Advanced {@code TombstoneDeleteIT} to ensure CE parity. All 3 methods
 * and their assertions are preserved verbatim; only the seeding (API calls capturing
 * server-generated ids), the storages-bulk-delete-failure triggers ({@link
 * FilesStackTestResource#getStoragesService()}'s {@code failBulkDeleteFor}/{@code
 * setBulkDeleteAlwaysThrows}, replacing the seam's {@code Mocks#storagesBulkDeleteSucceeds(List)}/
 * {@code storagesBulkDeleteFails}), the tombstone-count assertions (raw JDBC via {@link
 * #tombstoneRowsForNode}, since tombstones are not otherwise API-observable), and the transport
 * changed.
 */
class TombstoneDeleteIT extends AbstractFilesIT {

  private static final String OWNER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OWNER_COOKIE = "ZM_AUTH_TOKEN=fake-token";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", OWNER_ID);
  }

  private static Response executeDeleteNodes(String... nodeIds) {
    String mutation =
        GraphqlCommandBuilder.aMutationBuilder("deleteNodes")
            .withListOfStrings("node_ids", nodeIds)
            .withWantedResultFormat("")
            .build();
    return graphql(mutation, OWNER_COOKIE);
  }

  // --- Test 1: tombstone created -> removed on sync-path success ---
  // When bulkDelete succeeds the delete-handler removes the tombstone in the same request.

  @Test
  void givenNodeDeletedAndBlobSucceedsThenTombstoneIsCreatedAndThenRemovedSynchronously()
      throws SQLException {
    // Given — storages bulk-delete defaults to full success (empty failed list); no mock setup
    // needed.
    String fileId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);

    // When
    Response response = executeDeleteNodes(fileId);

    // Then — node deleted from DB.
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(nodeExists(fileId, OWNER_COOKIE)).isFalse();

    // Tombstone was created and then immediately removed after confirmed bulkDelete.
    Assertions.assertThat(tombstoneRowsForNode(fileId)).isEqualTo(0);
  }

  // --- Test 2: tombstone remains when bulkDelete reports the blob as failed ---
  // The response contains the nodeId in its failed-ids list; the handler must leave the tombstone.

  @Test
  void givenNodeDeletedAndBulkDeleteReportsFailureThenTombstoneRemains() throws SQLException {
    // Given
    String fileId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);

    // PowerStore responds 200 but reports this node as failed.
    FilesStackTestResource.getStoragesService().failBulkDeleteFor(fileId);

    // When
    Response response = executeDeleteNodes(fileId);

    // Then — DB delete committed (DB-first contract).
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(nodeExists(fileId, OWNER_COOKIE)).isFalse();

    // Tombstone remains so PurgeService can retry blob deletion.
    Assertions.assertThat(tombstoneRowsForNode(fileId)).isEqualTo(1);
  }

  // --- Test 3: tombstone remains on complete PowerStore outage (HTTP 500) ---

  @Test
  void givenNodeDeletedAndPowerStoreIsDownThenTombstoneRemains() throws SQLException {
    // Given
    String fileId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);

    FilesStackTestResource.getStoragesService().setBulkDeleteAlwaysThrows(true);

    // When
    Response response = executeDeleteNodes(fileId);

    // Then — DB delete committed; user sees success (DB-first contract).
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(nodeExists(fileId, OWNER_COOKIE)).isFalse();

    // Tombstone remains for PurgeService retry.
    Assertions.assertThat(tombstoneRowsForNode(fileId)).isEqualTo(1);
  }
}
