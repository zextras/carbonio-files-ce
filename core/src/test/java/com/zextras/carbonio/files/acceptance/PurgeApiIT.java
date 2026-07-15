// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.acceptance;

import com.zextras.carbonio.files.acceptance.seam.FilesTestApp;
import com.zextras.carbonio.files.acceptance.seam.impl.GuiceNettyFilesTestAppBuilder;
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
 * Task 6.1 of the acceptance coverage-expansion plan: {@code tasks.PurgeService}, driven through
 * the APPROVED non-HTTP backdoor {@code backdoor().runPurge()} (see plan §2.5/§10). There is no
 * HTTP trigger for this cron {@code Runnable} at all; {@code runPurge()} calls the same public
 * {@code PurgeService#run()} entry point the scheduler uses, which invokes {@code
 * purgeTombstones()} then {@code purgeTrashedNodes(RETENTION_TRASHED_ITEMS_IN_DAYS)} in that order
 * every time. Effects are asserted via the neutral backdoor inspectors ({@code tombstoneCount()}/
 * {@code tombstoneCountForNode()}/{@code nodeExists()}), mirroring the white-box {@code
 * tasks.PurgeServiceIT}/{@code tasks.PurgeTombstonesJobIT} scenarios exactly, but through the
 * seam instead of a same-package direct call.
 *
 * <p><b>FINDING — the "old trashed nodes actually removed" retention half of this task is
 * UNREACHABLE via the current, APPROVED backdoor (reported, not faked):</b> {@code
 * Constants.Config.PurgeService.RETENTION_TRASHED_ITEMS_IN_DAYS} is a hardcoded compile-time
 * constant ({@code 30L}), not overridable via {@code FilesConfig}/Service-Discover/any {@code
 * Mocks} knob, and {@code runPurge()} always calls {@code purgeTrashedNodes} with that production
 * value (per its Javadoc/impl — no reflection/package-bridge workaround is sanctioned). {@code
 * NodeRepositoryEbean#getAllTrashedNodes(Long)} selects strictly on {@code updated_at <
 * retentionTimestamp}; the ONLY public write path that persists a node's {@code updated_at} is
 * {@code NodeRepository#updateNode(Node)}, which unconditionally stamps it to {@code
 * System.currentTimeMillis()} (see {@code NodeRepositoryEbean#updateNode}) — including inside
 * {@code DatabasePopulator#addNodeToTrash}, the only seeding path to the TRASH_ROOT subtree. There
 * is therefore no way, through {@code backdoor().populator()} or any other Wave-0 hook, to seed a
 * trashed node whose {@code updated_at} predates "now minus 30 days"; backdating it would require
 * a NEW seam capability (e.g. raw-SQL/ebean access to stamp an arbitrary {@code updated_at}), which
 * is out of scope for this wave (Wave 6 is scoped to the three already-approved hooks: {@code
 * runPurge()}, {@code injectUserStatusChanged()}, {@code injectMaxVersionNumberChanged()}). Only
 * the "recent trashed node is retained" half of the retention behaviour is exercised below;
 * the "old trashed node is actually deleted" half stays covered ONLY by the white-box {@code
 * PurgeServiceIT} (which calls the package-private {@code purgeTrashedNodes(0)} directly with an
 * arbitrary retention argument, sidestepping the constant entirely).
 */
class PurgeApiIT {

  static FilesTestApp app;

  private static final String OWNER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OWNER_COOKIE = "ZM_AUTH_TOKEN=fake-token";

  @BeforeAll
  static void init() {
    app =
        GuiceNettyFilesTestAppBuilder.aFilesTestApp()
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
    HttpRequest httpRequest = HttpRequest.of("POST", "/graphql/", OWNER_COOKIE, null, bodyPayload);
    return app.send(httpRequest);
  }

  // --- purgeTrashedNodes — retention -------------------------------------------------------

  /**
   * Reachable half of the retention behaviour: a node trashed "just now" (the only age {@code
   * DatabasePopulator#addNodeToTrash} can produce) is NOT older than the production 30-day
   * retention window, so {@code purgeTrashedNodes} must leave it alone. See the class-level
   * FINDING for why the "old enough to be removed" half cannot be driven through this backdoor.
   */
  @Test
  void givenARecentlyTrashedNodeThenRunPurgeLeavesItInPlace() {
    // Given
    String fileId = "00000000-0000-0000-0000-600000000001";
    app.backdoor()
        .populator()
        .addNode(new SimplePopulatorTextFile(fileId, OWNER_ID, "recent.txt"))
        .addNodeToTrash(fileId, "LOCAL_ROOT");

    Assertions.assertThat(app.backdoor().nodeExists(fileId)).isTrue();

    // When
    app.backdoor().runPurge();

    // Then — recent trash age never crosses the 30-day retention threshold, so it is kept.
    Assertions.assertThat(app.backdoor().nodeExists(fileId)).isTrue();
  }

  // --- purgeTombstones — success / partial-failure / outage ------------------------------

  @Test
  void givenAStrandedTombstoneWhenRunPurgeSucceedsThenTombstoneIsCleared() {
    // Given — node deleted while storages is completely down: DB row gone, tombstone stranded.
    String fileId = "00000000-0000-0000-0000-600000000002";
    app.backdoor().populator().addNode(new SimplePopulatorTextFile(fileId, OWNER_ID, "a.txt"));

    app.mocks().storagesBulkDeleteFails();
    HttpResponse deleteResponse = executeDeleteNodes(fileId);
    Assertions.assertThat(deleteResponse.getStatus()).isEqualTo(200);
    Assertions.assertThat(app.backdoor().nodeExists(fileId)).isFalse();
    Assertions.assertThat(app.backdoor().tombstoneCountForNode(fileId)).isEqualTo(1);

    // When — storages is fixed, then the purge job runs.
    app.mocks().reset();
    app.mocks().storagesBulkDeleteSucceeds(List.of());
    app.backdoor().runPurge();

    // Then
    Assertions.assertThat(app.backdoor().tombstoneCountForNode(fileId)).isEqualTo(0);
  }

  @Test
  void givenTwoStrandedTombstonesWhenRunPurgeReportsOnePartialFailureThenOnlyThatOneIsKept() {
    // Given — two nodes owned by the same user, both deleted while storages is down.
    String keptFailingId = "00000000-0000-0000-0000-600000000003";
    String succeedingId = "00000000-0000-0000-0000-600000000004";
    app.backdoor()
        .populator()
        .addNode(new SimplePopulatorTextFile(keptFailingId, OWNER_ID, "a.txt"))
        .addNode(new SimplePopulatorTextFile(succeedingId, OWNER_ID, "b.txt"));

    app.mocks().storagesBulkDeleteFails();
    executeDeleteNodes(keptFailingId, succeedingId);
    Assertions.assertThat(app.backdoor().tombstoneCount()).isEqualTo(2);

    // When — PowerStore reports keptFailingId as still-failed, succeedingId as deleted.
    app.mocks().reset();
    app.mocks().storagesBulkDeleteSucceeds(List.of(keptFailingId));
    app.backdoor().runPurge();

    // Then
    Assertions.assertThat(app.backdoor().tombstoneCountForNode(keptFailingId)).isEqualTo(1);
    Assertions.assertThat(app.backdoor().tombstoneCountForNode(succeedingId)).isEqualTo(0);
    Assertions.assertThat(app.backdoor().tombstoneCount()).isEqualTo(1);
  }

  @Test
  void givenAStrandedTombstoneWhenRunPurgeHitsACompleteOutageThenTombstoneIsKeptForRetry() {
    // Given
    String fileId = "00000000-0000-0000-0000-600000000005";
    app.backdoor().populator().addNode(new SimplePopulatorTextFile(fileId, OWNER_ID, "a.txt"));

    app.mocks().storagesBulkDeleteFails();
    executeDeleteNodes(fileId);
    Assertions.assertThat(app.backdoor().tombstoneCountForNode(fileId)).isEqualTo(1);

    // When — PowerStore is STILL completely down during the purge run.
    app.mocks().reset();
    app.mocks().storagesBulkDeleteFails();
    app.backdoor().runPurge();

    // Then — kept for the next cycle.
    Assertions.assertThat(app.backdoor().tombstoneCountForNode(fileId)).isEqualTo(1);
  }

  @Test
  void givenAStrandedTombstoneWhenRunPurgeGetsANullBulkDeleteResponseThenTombstoneIsKept() {
    // Given — a null/empty PowerStore response is NOT a success signal and must be treated like
    // an outage (per PurgeService#purgeTombstones), never as "all deleted".
    String fileId = "00000000-0000-0000-0000-600000000006";
    app.backdoor().populator().addNode(new SimplePopulatorTextFile(fileId, OWNER_ID, "a.txt"));

    app.mocks().storagesBulkDeleteFails();
    executeDeleteNodes(fileId);
    Assertions.assertThat(app.backdoor().tombstoneCountForNode(fileId)).isEqualTo(1);

    // When
    app.mocks().reset();
    app.mocks().storagesBulkDeleteReturnsNullResponse();
    app.backdoor().runPurge();

    // Then
    Assertions.assertThat(app.backdoor().tombstoneCountForNode(fileId)).isEqualTo(1);
  }

  @Test
  void givenAnOutageAcrossThreeConsecutiveRunPurgeCallsThenTombstoneIsNeverDropped() {
    // Given — an outage must NEVER burn the retry budget, no matter how many cycles pass.
    String fileId = "00000000-0000-0000-0000-600000000007";
    app.backdoor().populator().addNode(new SimplePopulatorTextFile(fileId, OWNER_ID, "a.txt"));

    app.mocks().storagesBulkDeleteFails();
    executeDeleteNodes(fileId);
    Assertions.assertThat(app.backdoor().tombstoneCountForNode(fileId)).isEqualTo(1);

    // When — run the purge job 3 times while PowerStore is still down.
    for (int run = 1; run <= 3; run++) {
      app.mocks().reset();
      app.mocks().storagesBulkDeleteFails();
      app.backdoor().runPurge();

      // Then
      Assertions.assertThat(app.backdoor().tombstoneCountForNode(fileId))
          .as("tombstone must still exist after outage run " + run)
          .isEqualTo(1);
    }
  }

  // --- purgeTombstones — retry cap / give-up ----------------------------------------------

  /**
   * {@code MAX_TOMBSTONE_RETRIES = 3}: a blob that PowerStore keeps genuinely rejecting (HTTP 200,
   * listed in the failed-ids response — NOT an outage) is dropped as an accepted orphan on the
   * 3rd {@code runPurge()} call. Each {@code runPurge()} call is one full {@code
   * PurgeService#run()} cycle, i.e. one retry attempt for {@code purgeTombstones}'s per-blob
   * counter — mirrors {@code PurgeTombstonesJobIT
   * #givenBlobAlwaysFailsWithPartialFailureThenTombstoneRemovedAfterThirdJobRun} exactly, driven
   * through the seam instead of a same-package direct call to {@code purgeTombstones()}.
   */
  @Test
  void givenABlobAlwaysFailsWithPartialFailureThenTombstoneIsDroppedAfterTheThirdRunPurgeCall() {
    // Given — seed the tombstone via a partial-failure delete (HTTP 200, node in failed list).
    String fileId = "00000000-0000-0000-0000-600000000008";
    app.backdoor().populator().addNode(new SimplePopulatorTextFile(fileId, OWNER_ID, "a.txt"));

    app.mocks().storagesBulkDeleteSucceeds(List.of(fileId));
    HttpResponse deleteResponse = executeDeleteNodes(fileId);
    Assertions.assertThat(deleteResponse.getStatus()).isEqualTo(200);
    Assertions.assertThat(app.backdoor().tombstoneCountForNode(fileId)).isEqualTo(1);

    // Run 1: still failing -> attempts=1, kept.
    app.mocks().reset();
    app.mocks().storagesBulkDeleteSucceeds(List.of(fileId));
    app.backdoor().runPurge();
    Assertions.assertThat(app.backdoor().tombstoneCountForNode(fileId))
        .as("kept after run 1 (attempts=1)")
        .isEqualTo(1);

    // Run 2: still failing -> attempts=2, kept.
    app.mocks().reset();
    app.mocks().storagesBulkDeleteSucceeds(List.of(fileId));
    app.backdoor().runPurge();
    Assertions.assertThat(app.backdoor().tombstoneCountForNode(fileId))
        .as("kept after run 2 (attempts=2)")
        .isEqualTo(1);

    // Run 3: still failing -> attempts+1==3==MAX -> orphan accepted, tombstone dropped.
    app.mocks().reset();
    app.mocks().storagesBulkDeleteSucceeds(List.of(fileId));
    app.backdoor().runPurge();
    Assertions.assertThat(app.backdoor().tombstoneCountForNode(fileId))
        .as("dropped as orphan after the 3rd run hits the retry cap")
        .isEqualTo(0);
  }

  /**
   * Retry-and-recover: a blob that fails once but succeeds on the second {@code runPurge()} call
   * is removed normally, well before the retry cap — the counter is not a one-way ratchet toward
   * deletion, recovery clears it immediately.
   */
  @Test
  void givenABlobFailsOnceThenSucceedsOnTheSecondRunPurgeCallTombstoneIsRemovedNormally() {
    // Given
    String fileId = "00000000-0000-0000-0000-600000000009";
    app.backdoor().populator().addNode(new SimplePopulatorTextFile(fileId, OWNER_ID, "a.txt"));

    app.mocks().storagesBulkDeleteFails();
    executeDeleteNodes(fileId);
    Assertions.assertThat(app.backdoor().tombstoneCountForNode(fileId)).isEqualTo(1);

    // Run 1: still failing -> attempts=1, kept.
    app.mocks().reset();
    app.mocks().storagesBulkDeleteSucceeds(List.of(fileId));
    app.backdoor().runPurge();
    Assertions.assertThat(app.backdoor().tombstoneCountForNode(fileId)).isEqualTo(1);

    // Run 2: blob deletion now succeeds -> tombstone removed (nowhere near the retry cap).
    app.mocks().reset();
    app.mocks().storagesBulkDeleteSucceeds(List.of());
    app.backdoor().runPurge();
    Assertions.assertThat(app.backdoor().tombstoneCountForNode(fileId)).isEqualTo(0);
  }
}
