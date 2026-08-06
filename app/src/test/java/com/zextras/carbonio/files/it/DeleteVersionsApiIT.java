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
import java.util.Map;
import java.util.stream.Collectors;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code com.zextras.carbonio.files.acceptance.DeleteVersionsApiIT} rewritten as an out-of-process
 * {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}. All 4 methods and their assertions
 * are preserved verbatim; only the seeding mechanism (API calls capturing server-generated ids),
 * the storages-bulk-delete-failure triggers ({@link FilesStackTestResource#getStoragesService()}'s
 * {@code setBulkDeleteAlwaysThrows}, replacing the seam's {@code Mocks#storagesBulkDeleteFails}),
 * the tombstone-count assertions (raw JDBC via {@link #tombstoneRowsForNode}, since tombstones are
 * not otherwise API-observable), and the transport changed.
 */
class DeleteVersionsApiIT extends AbstractFilesIT {

  private static final String OWNER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OWNER_COOKIE = "ZM_AUTH_TOKEN=fake-token";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", OWNER_ID);
  }

  private static Response executeDeleteVersions(String nodeId, int... versions) {
    String mutation =
        GraphqlCommandBuilder.aMutationBuilder("deleteVersions")
            .withString("node_id", nodeId)
            .withListOfIntegers("versions", versions)
            .withWantedResultFormat("")
            .build();
    return graphql(mutation, OWNER_COOKIE);
  }

  /** Creates a file with 3 versions (v1 via seedFile, v2/v3 via seedVersion); current = 3. */
  private static String createFileWithThreeVersions() {
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "v1".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    seedVersion(nodeId, "v2".getBytes(StandardCharsets.UTF_8), "file.txt", OWNER_COOKIE);
    seedVersion(nodeId, "v3".getBytes(StandardCharsets.UTF_8), "file.txt", OWNER_COOKIE);
    return nodeId;
  }

  /**
   * Remaining version numbers, ascending, read back via the public {@code getVersions} GraphQL
   * query (contract-observable) rather than raw JDBC. Omitting the "versions" argument returns
   * every version currently persisted for the node.
   */
  private static List<Integer> getRemainingVersionNumbers(String nodeId) {
    String query =
        GraphqlCommandBuilder.aQueryBuilder("getVersions")
            .withString("node_id", nodeId)
            .withWantedResultFormat("{ version }")
            .build();
    Response response = graphql(query, OWNER_COOKIE);
    List<Map<String, Object>> versions =
        TestUtils.jsonResponseToList(response.getBody().asString(), "getVersions");
    return versions.stream()
        .map(v -> (Integer) v.get("version"))
        .sorted()
        .collect(Collectors.toList());
  }

  // --- Test 1: Happy path — file with 3 versions, delete [1,2], all blobs succeed ---
  // Tombstones created then cleaned up.

  @Test
  void
      givenFileWithThreeVersionsDeleteVersionsOneAndTwoAllBlobsSucceedThenVersionsOneAndTwoDeletedVersionThreeStays()
          throws SQLException {
    // Given
    String nodeId = createFileWithThreeVersions();
    // storages bulk-delete defaults to full success (empty failed list) — no mock setup needed.

    // When — delete versions 1 and 2 (current=3 must stay)
    Response response = executeDeleteVersions(nodeId, 1, 2);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);

    List<Integer> deletedVersions =
        (List<Integer>)
            TestUtils.jsonResponseToValue(response.getBody().asString(), "deleteVersions")
                .orElse(List.of());
    Assertions.assertThat(deletedVersions).containsExactlyInAnyOrder(1, 2);

    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors).isEmpty();

    // Verify DB: only version 3 remains.
    Assertions.assertThat(getRemainingVersionNumbers(nodeId)).containsExactly(3);

    // Tombstones cleaned up after confirmed blob delete.
    Assertions.assertThat(tombstoneRowsForNode(nodeId)).isEqualTo(0);
  }

  // --- Test 2: PowerStore fails (exception) — versions still deleted from DB, no error to user ---
  // Core tombstone invariant: DB-first, tombstones remain for retry.

  @Test
  void
      givenFileWithThreeVersionsAndPowerStoreFailsThenVersionsOneAndTwoStillDeletedAndTombstonesRemain()
          throws SQLException {
    // Given
    String nodeId = createFileWithThreeVersions();

    // PowerStore returns HTTP 500 — complete failure.
    FilesStackTestResource.getStoragesService().setBulkDeleteAlwaysThrows(true);

    // When — delete versions 1 and 2
    Response response = executeDeleteVersions(nodeId, 1, 2);

    // Then — DB-first: versions deleted, no error to user.
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);

    List<Integer> deletedVersions =
        (List<Integer>)
            TestUtils.jsonResponseToValue(response.getBody().asString(), "deleteVersions")
                .orElse(List.of());
    Assertions.assertThat(deletedVersions).containsExactlyInAnyOrder(1, 2);

    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors).isEmpty();

    // v1 and v2 deleted from DB (already committed before PowerStore call).
    Assertions.assertThat(getRemainingVersionNumbers(nodeId)).containsExactly(3);

    // Tombstones remain for PurgeService retry.
    Assertions.assertThat(tombstoneRowsForNode(nodeId)).isEqualTo(2);
  }

  // --- Test 3: Protective filter — current version cannot be deleted ---

  @Test
  void givenFileWithThreeVersionsDeleteCurrentVersionThenCurrentVersionSkippedAndErrorReturned()
      throws SQLException {
    // Given
    String nodeId = createFileWithThreeVersions(); // current = 3

    // When — try to delete current version (3)
    Response response = executeDeleteVersions(nodeId, 3);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);

    List<Integer> deletedVersions =
        (List<Integer>)
            TestUtils.jsonResponseToValue(response.getBody().asString(), "deleteVersions")
                .orElse(List.of());
    Assertions.assertThat(deletedVersions).isEmpty();

    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors).hasSize(1);

    // All 3 versions stay in DB.
    Assertions.assertThat(getRemainingVersionNumbers(nodeId)).containsExactly(1, 2, 3);

    // No tombstones created (nothing eligible to delete).
    Assertions.assertThat(tombstoneRowsForNode(nodeId)).isEqualTo(0);
  }

  // --- Test 4: Protective filter — keepForever version cannot be deleted ---

  @Test
  void
      givenFileWithKeepForeverVersionDeleteItThenKeepForeverVersionSkippedAndOnlyEligibleVersionDeleted()
          throws SQLException {
    // Given — version 2 is keepForever, version 3 is current
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

    // When — try to delete v1 (eligible) and v2 (keepForever, ineligible)
    Response response = executeDeleteVersions(nodeId, 1, 2);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);

    List<Integer> deletedVersions =
        (List<Integer>)
            TestUtils.jsonResponseToValue(response.getBody().asString(), "deleteVersions")
                .orElse(List.of());
    // Only v1 deleted; v2 is keepForever (skipped -> fileVersionNotFound error).
    Assertions.assertThat(deletedVersions).containsExactly(1);

    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors).hasSize(1); // error for v2

    // v2 (keepForever) and v3 (current) stay.
    Assertions.assertThat(getRemainingVersionNumbers(nodeId)).containsExactly(2, 3);

    // Tombstone for v1 cleaned up.
    Assertions.assertThat(tombstoneRowsForNode(nodeId)).isEqualTo(0);
  }
}
