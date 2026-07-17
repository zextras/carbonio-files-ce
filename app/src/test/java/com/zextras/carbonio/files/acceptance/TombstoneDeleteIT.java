// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.acceptance;

import com.zextras.carbonio.files.FilesStackTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

import com.zextras.carbonio.files.acceptance.seam.FilesTestApp;
import com.zextras.carbonio.files.acceptance.seam.impl.QuarkusFilesTestAppBuilder;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.api.utilities.entities.SimplePopulatorTextFile;
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
@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class TombstoneDeleteIT {

  static FilesTestApp app;

  private static final String OWNER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

  @BeforeAll
  static void init() {
    app =
        QuarkusFilesTestAppBuilder.aFilesTestApp()
            .withDatabase()
            .withServiceDiscover()
            .withStorages()
            .withUserManagement(Map.of("fake-token", OWNER_ID))
            .build();
  }

  @AfterEach
  void cleanUp() {
    app.backdoor().resetDatabase();
    // TOMBSTONE is not FK-linked to NODE, so resetDatabase() does not cascade into it.
    app.backdoor().clearTombstones();
    app.mocks().reset();
  }

  @AfterAll
  static void cleanUpAll() {
    app.close();
  }

  private HttpResponse executeDeleteNodes(String... nodeIds) {
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("deleteNodes")
            .withListOfStrings("node_ids", nodeIds)
            .withWantedResultFormat("")
            .build();
    HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", null, bodyPayload);
    return app.send(httpRequest);
  }

  // --- Test 1: tombstone created → removed on sync-path success ---
  // When bulkDelete succeeds the delete-handler removes the tombstone in the same request.

  @Test
  void givenNodeDeletedAndBlobSucceedsThenTombstoneIsCreatedAndThenRemovedSynchronously() {
    // Given
    String fileId = "00000000-0000-0000-0000-200000000001";

    app.backdoor()
        .populator()
        .addNode(new SimplePopulatorTextFile(fileId, OWNER_ID, "file.txt"));

    app.mocks().storagesBulkDeleteSucceeds(List.of());

    // When
    HttpResponse httpResponse = executeDeleteNodes(fileId);

    // Then — node deleted from DB.
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Assertions.assertThat(app.backdoor().nodeExists(fileId)).isFalse();

    // Tombstone was created and then immediately removed after confirmed bulkDelete.
    Assertions.assertThat(app.backdoor().tombstoneCount()).isEqualTo(0);
  }

  // --- Test 2: tombstone remains when bulkDelete reports the blob as failed ---
  // The response contains the nodeId in its failed-ids list; the handler must leave the tombstone.

  @Test
  void givenNodeDeletedAndBulkDeleteReportsFailureThenTombstoneRemains() {
    // Given
    String fileId = "00000000-0000-0000-0000-200000000002";

    app.backdoor()
        .populator()
        .addNode(new SimplePopulatorTextFile(fileId, OWNER_ID, "file.txt"));

    // PowerStore responds 200 but reports this node as failed.
    app.mocks().storagesBulkDeleteSucceeds(List.of(fileId));

    // When
    HttpResponse httpResponse = executeDeleteNodes(fileId);

    // Then — DB delete committed (DB-first contract).
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Assertions.assertThat(app.backdoor().nodeExists(fileId)).isFalse();

    // Tombstone remains so PurgeService can retry blob deletion.
    Assertions.assertThat(app.backdoor().tombstoneCount()).isEqualTo(1);
    Assertions.assertThat(app.backdoor().tombstoneCountForNode(fileId)).isEqualTo(1);
  }

  // --- Test 3: tombstone remains on complete PowerStore outage (HTTP 500) ---

  @Test
  void givenNodeDeletedAndPowerStoreIsDownThenTombstoneRemains() {
    // Given
    String fileId = "00000000-0000-0000-0000-200000000003";

    app.backdoor()
        .populator()
        .addNode(new SimplePopulatorTextFile(fileId, OWNER_ID, "file.txt"));

    app.mocks().storagesBulkDeleteFails();

    // When
    HttpResponse httpResponse = executeDeleteNodes(fileId);

    // Then — DB delete committed; user sees success (DB-first contract).
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Assertions.assertThat(app.backdoor().nodeExists(fileId)).isFalse();

    // Tombstone remains for PurgeService retry.
    Assertions.assertThat(app.backdoor().tombstoneCount()).isEqualTo(1);
    Assertions.assertThat(app.backdoor().tombstoneCountForNode(fileId)).isEqualTo(1);
  }
}
