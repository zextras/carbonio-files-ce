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
 * {@code com.zextras.carbonio.files.acceptance.DeleteVersionsCompletenessApiIT} rewritten as an
 * out-of-process {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}. Completeness
 * companion to {@link DeleteVersionsApiIT} (which covers the happy path, the PowerStore-fails
 * DB-first invariant, current-version protection with only a bare error-count assertion, and
 * keep-forever protection likewise): this class re-covers the two "protected version" branches
 * with their EXACT message asserted, adds the genuinely-nonexistent-version case for direct
 * comparison, and documents (rather than fakes) the one branch this suite cannot reach.
 *
 * <p><b>FINDING (carried over) — "protected" and "nonexistent" are indistinguishable to the
 * caller:</b> reading {@code NodeDataFetcher#deleteVersionsFetcher} precisely, ALL THREE of the
 * following collapse onto the exact SAME {@code fileVersionNotFound(nodeId, v, path)} error
 * (message {@code "Could not find version: <v> for node with id <id>"}, extension {@code
 * errorCode: FILE_VERSION_NOT_FOUND}): {@code v} IS the node's current version (filtered out by
 * {@code !node.getCurrentVersion().equals(fv.getVersion())}); {@code v} is marked {@code
 * keepForever} (filtered out by {@code !fv.isKeptForever()}); {@code v} simply has no {@code
 * FileVersion} row at all for this node. The result-composition code only ever compares "was
 * {@code v} requested?" against "is {@code v} in the final {@code versionsToDelete} list?" — it
 * has no memory of WHY a version fell out of that list, so a client can never tell a protected
 * version apart from a typo'd/nonexistent one. Asserted directly below (all three scenarios
 * produce the byte-identical message shape), not fixed (test-only task).
 *
 * <p><b>GAP (carried over) — the transaction-rollback branch has NO reachable trigger through
 * this suite:</b> {@code deleteVersionsFetcher}'s {@code catch (RuntimeException e)} around the
 * tombstone-write-then-delete transaction (returning a single {@code nodeWriteError}) cannot be
 * exercised black-box: {@code TombstoneRepositoryEbean#createNewTombstone} is deliberately
 * idempotent (check-then-insert, cannot PK-violate); {@code
 * FileVersionRepositoryEbean#deleteFileVersions} is a plain bulk {@code DELETE} with no FK
 * referencing {@code file_version} from any other table; the only fault-injection this stack
 * exposes is on the STORAGES side (already exercised by {@link DeleteVersionsApiIT}'s
 * PowerStore-fails test). No unit test exercises this catch block either. Documented here as a
 * genuine coverage gap rather than forced with a workaround.
 */
class DeleteVersionsCompletenessApiIT extends AbstractFilesIT {

  private static final String OWNER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OWNER_COOKIE = "ZM_AUTH_TOKEN=fake-token";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", OWNER_ID);
  }

  private static Response deleteVersions(String nodeId, int... versions) {
    String mutation =
        GraphqlCommandBuilder.aMutationBuilder("deleteVersions")
            .withString("node_id", nodeId)
            .withListOfIntegers("versions", versions)
            .withWantedResultFormat("")
            .build();
    return graphql(mutation, OWNER_COOKIE);
  }

  @SuppressWarnings("unchecked")
  private static List<Integer> deletedVersions(Response response) {
    return (List<Integer>)
        TestUtils.jsonResponseToValue(response.getBody().asString(), "deleteVersions")
            .orElse(List.of());
  }

  @Test
  void givenTheCurrentVersionRequestedForDeletionItIsSkippedWithTheSameNotFoundShapeAsGenuinelyMissing()
      throws SQLException {
    // Given — v1, v2 (current)
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "v1".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    seedVersion(nodeId, "v2".getBytes(StandardCharsets.UTF_8), "file.txt", OWNER_COOKIE); // current

    // When — try to delete the current version
    Response response = deleteVersions(nodeId, 2);

    // Then — skipped, and surfaced with the EXACT SAME message/shape as "doesn't exist"
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(deletedVersions(response)).isEmpty();
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("Could not find version: 2 for node with id " + nodeId);

    // both versions remain; no tombstone was ever created (nothing was eligible)
    Assertions.assertThat(versionRows(nodeId)).containsExactly(1, 2);
    Assertions.assertThat(tombstoneRowsForNode(nodeId)).isEqualTo(0);
  }

  @Test
  void givenAKeptForeverVersionRequestedForDeletionItIsSkippedWithTheSameNotFoundShapeAsGenuinelyMissing()
      throws SQLException {
    // Given — v2 is kept-forever (non-current), v3 is current
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "v1".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    seedVersion(nodeId, "v2".getBytes(StandardCharsets.UTF_8), "file.txt", OWNER_COOKIE);
    String keepMutation =
        GraphqlCommandBuilder.aMutationBuilder("keepVersions")
            .withString("node_id", nodeId)
            .withListOfIntegers("versions", new int[] {2})
            .withBoolean("keep_forever", true)
            .withWantedResultFormat("")
            .build();
    graphql(keepMutation, OWNER_COOKIE);
    seedVersion(nodeId, "v3".getBytes(StandardCharsets.UTF_8), "file.txt", OWNER_COOKIE); // current

    // When — try to delete the kept-forever version
    Response response = deleteVersions(nodeId, 2);

    // Then — skipped, and surfaced with the EXACT SAME message/shape as "doesn't exist"
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(deletedVersions(response)).isEmpty();
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("Could not find version: 2 for node with id " + nodeId);

    // all three versions remain; no tombstone was ever created
    Assertions.assertThat(versionRows(nodeId)).containsExactly(1, 2, 3);
    Assertions.assertThat(tombstoneRowsForNode(nodeId)).isEqualTo(0);
  }

  @Test
  void givenAVersionThatDoesNotExistItProducesTheIdenticalFileVersionNotFoundShape()
      throws SQLException {
    // Given — only v1 (current) exists
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);

    // When
    Response response = deleteVersions(nodeId, 999);

    // Then — byte-identical message shape to the two "protected" cases above
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(deletedVersions(response)).isEmpty();
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("Could not find version: 999 for node with id " + nodeId);

    Assertions.assertThat(versionRows(nodeId)).containsExactly(1);
  }
}
