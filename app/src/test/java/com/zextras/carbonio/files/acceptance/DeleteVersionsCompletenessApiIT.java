// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.acceptance;

import com.zextras.carbonio.files.FilesStackTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.acceptance.seam.FilesTestApp;
import com.zextras.carbonio.files.acceptance.seam.impl.QuarkusFilesTestAppBuilder;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.api.utilities.entities.SimplePopulatorTextFile;
import com.zextras.carbonio.files.utilities.http.HttpRequest;
import com.zextras.carbonio.files.utilities.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Task 2.6 (part 3) of the acceptance coverage-expansion plan: completeness companion to the
 * already-existing {@code DeleteVersionsApiIT} (which covers the happy path, the PowerStore-fails
 * DB-first invariant, current-version protection with only a bare error-count assertion, and
 * keep-forever protection likewise). The user prefers duplication over gaps, so this class
 * re-covers the two "protected version" branches with their EXACT message asserted, adds the
 * genuinely-nonexistent-version case for direct comparison, and documents (rather than fakes) the
 * one branch the current seam cannot reach.
 *
 * <p><b>FINDING — "protected" and "nonexistent" are indistinguishable to the caller:</b> reading
 * {@code NodeDataFetcher#deleteVersionsFetcher} precisely, ALL THREE of the following collapse
 * onto the exact SAME {@code fileVersionNotFound(nodeId, v, path)} error (message {@code "Could
 * not find version: <v> for node with id <id>"}, extension {@code errorCode:
 * FILE_VERSION_NOT_FOUND}):
 *
 * <ul>
 *   <li>{@code v} IS the node's current version (filtered out by {@code
 *       !node.getCurrentVersion().equals(fv.getVersion())});
 *   <li>{@code v} is marked {@code keepForever} (filtered out by {@code !fv.isKeptForever()});
 *   <li>{@code v} simply has no {@code FileVersion} row at all for this node.
 * </ul>
 *
 * <p>The result-composition code only ever compares "was {@code v} requested?" against "is {@code
 * v} in the final {@code versionsToDelete} list?" — it has no memory of WHY a version fell out of
 * that list, so a client can never tell a protected version apart from a typo'd/nonexistent one.
 * This is asserted directly below (all three scenarios produce the byte-identical message shape),
 * not fixed (Phase 1 forbids {@code src/main} changes).
 *
 * <p><b>GAP — the transaction-rollback branch (STOP + report, per task instructions):</b> {@code
 * deleteVersionsFetcher}'s {@code catch (RuntimeException e)} around the
 * tombstone-write-then-delete transaction (returning a single {@code nodeWriteError}) has NO
 * reachable trigger through the current seam. Verified by reading every DB call inside that
 * transaction:
 *
 * <ul>
 *   <li>{@code TombstoneRepositoryEbean#createNewTombstone} does a check-then-insert (SELECT for
 *       an existing row with the same {@code (node_id, version)} PK, and returns {@code
 *       Optional.empty()} WITHOUT inserting if one already exists) — it is deliberately
 *       idempotent and cannot throw a PK-violation;
 *   <li>{@code FileVersionRepositoryEbean#deleteFileVersions} is a plain bulk {@code DELETE ...
 *       WHERE node_id = ? AND version IN (...)} with no FK referencing {@code file_version} from
 *       any other table (grep of every {@code V*__*.sql} migration under {@code
 *       core/src/main/resources/db/migration} shows every {@code REFERENCES} targets {@code node},
 *       none targets {@code file_version});
 *   <li>the only fault-injection this seam exposes is on the STORAGES side ({@code
 *       Mocks#storagesBulkDeleteFails/ReturnsNullResponse}, already exercised by {@code
 *       DeleteVersionsApiIT}'s Test 2) — Wave 0 did not add any DB-level fault injection (e.g.
 *       pausing/killing the shared Testcontainers Postgres, which is also a singleton shared
 *       across the whole suite and would be unsafe to disrupt from a single test).
 * </ul>
 *
 * <p>No white-box unit test exercises this catch block either (confirmed by grepping the test
 * tree for its log line), so it is untested altogether today, not merely untested black-box. Per
 * the task's explicit instruction to "STOP + report if the seam lacks something" rather than
 * force a workaround or weaken the assertion, this branch is documented here as a genuine
 * seam gap instead of a test.
 */
@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class DeleteVersionsCompletenessApiIT {

  static FilesTestApp app;

  private static final String OWNER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OWNER_COOKIE = "ZM_AUTH_TOKEN=fake-token";

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
    // Tombstones are not FK-linked to NODE so resetDatabase() doesn't clean them.
    app.backdoor().clearTombstones();
    app.mocks().reset();
  }

  @AfterAll
  static void cleanUpAll() {
    app.close();
  }

  private HttpResponse deleteVersions(String nodeId, int... versions) {
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("deleteVersions")
            .withString("node_id", nodeId)
            .withListOfIntegers("versions", versions)
            .withWantedResultFormat("")
            .build();
    HttpRequest httpRequest = HttpRequest.of("POST", "/graphql/", OWNER_COOKIE, bodyPayload);
    return app.send(httpRequest);
  }

  @SuppressWarnings("unchecked")
  private List<Integer> deletedVersions(HttpResponse httpResponse) {
    return (List<Integer>)
        TestUtils.jsonResponseToValue(httpResponse.getBodyPayload(), "deleteVersions").orElse(List.of());
  }

  @Test
  void givenTheCurrentVersionRequestedForDeletionItIsSkippedWithTheSameNotFoundShapeAsGenuinelyMissing() {
    // Given — v1, v2 (current)
    String nodeId = "70000000-0000-0000-0000-000000000001";
    app.backdoor()
        .populator()
        .addNode(new SimplePopulatorTextFile(nodeId, OWNER_ID, "file.txt"))
        .addVersion(nodeId); // v2, current

    // When — try to delete the current version
    HttpResponse httpResponse = deleteVersions(nodeId, 2);

    // Then — skipped, and surfaced with the EXACT SAME message/shape as "doesn't exist"
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Assertions.assertThat(deletedVersions(httpResponse)).isEmpty();
    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("Could not find version: 2 for node with id " + nodeId);

    // both versions remain; no tombstone was ever created (nothing was eligible)
    Assertions.assertThat(app.backdoor().remainingVersionNumbers(nodeId)).containsExactly(1, 2);
    Assertions.assertThat(app.backdoor().tombstoneCountForNode(nodeId)).isEqualTo(0);
  }

  @Test
  void givenAKeptForeverVersionRequestedForDeletionItIsSkippedWithTheSameNotFoundShapeAsGenuinelyMissing() {
    // Given — v2 is kept-forever (non-current), v3 is current
    String nodeId = "70000000-0000-0000-0000-000000000002";
    app.backdoor()
        .populator()
        .addNode(new SimplePopulatorTextFile(nodeId, OWNER_ID, "file.txt"))
        .addVersion(nodeId, true) // v2, keepForever=true
        .addVersion(nodeId); // v3, current

    // When — try to delete the kept-forever version
    HttpResponse httpResponse = deleteVersions(nodeId, 2);

    // Then — skipped, and surfaced with the EXACT SAME message/shape as "doesn't exist"
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Assertions.assertThat(deletedVersions(httpResponse)).isEmpty();
    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("Could not find version: 2 for node with id " + nodeId);

    // all three versions remain; no tombstone was ever created
    Assertions.assertThat(app.backdoor().remainingVersionNumbers(nodeId)).containsExactly(1, 2, 3);
    Assertions.assertThat(app.backdoor().tombstoneCountForNode(nodeId)).isEqualTo(0);
  }

  @Test
  void givenAVersionThatDoesNotExistItProducesTheIdenticalFileVersionNotFoundShape() {
    // Given — only v1 (current) exists
    String nodeId = "70000000-0000-0000-0000-000000000003";
    app.backdoor().populator().addNode(new SimplePopulatorTextFile(nodeId, OWNER_ID, "file.txt"));

    // When
    HttpResponse httpResponse = deleteVersions(nodeId, 999);

    // Then — byte-identical message shape to the two "protected" cases above
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Assertions.assertThat(deletedVersions(httpResponse)).isEmpty();
    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("Could not find version: 999 for node with id " + nodeId);

    Assertions.assertThat(app.backdoor().remainingVersionNumbers(nodeId)).containsExactly(1);
  }
}
