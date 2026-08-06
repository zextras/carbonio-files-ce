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
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code com.zextras.carbonio.files.acceptance.CloneVersionApiIT} rewritten as an out-of-process
 * {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}. The {@code cloneVersion} mutation
 * (bound to {@code NodeDataFetcher#cloneVersionFetcher}) is exercised on the DEFAULT (unbounded)
 * version cap; the total-version-cap scenario is split into the sibling {@link
 * CloneVersionCountCapIT} (Batch G / D3), since the cap is a boot-time config snapshot that must be
 * set for the WHOLE launched process, not per-method.
 *
 * <p><b>FINDING (carried over) — the graceful "version not found" branch is DEAD CODE, killed by an
 * eager debug-log {@code Optional#get()}:</b> immediately before the safe {@code
 * fileVersionRepository.getFileVersion(nodeId, versionToClone).map(...).orElse(
 * fileVersionNotFound(...))} chain, {@code cloneVersionFetcher} builds a debug log line via an
 * unconditional {@code .get()} on that same lookup — evaluated eagerly by {@code
 * MessageFormat.format}'s argument list regardless of whether DEBUG logging is enabled. When {@code
 * versionToClone} does not correspond to any {@code FileVersion} row, this throws a plain {@code
 * NoSuchElementException("No value present")}, which graphql-java's default {@code
 * SimpleDataFetcherExceptionHandler} wraps into an {@code ExceptionWhileDataFetching} error
 * (message {@code "Exception while fetching data (/cloneVersion) : No value present"}). The
 * well-formed {@code fileVersionNotFound(...)} error a few lines below can therefore NEVER be
 * produced by a genuinely non-existent version — asserted below as the REAL (crash-shaped)
 * behaviour, not fixed (test-only task; no {@code src/main} changes).
 *
 * <p><b>FINDING (carried over) — hard abort, surfacing as a SINGLE error, not doubled:</b> the
 * filestore-copy failure path throws an {@code AbortExecutionException} from INSIDE the async
 * {@code CompletableFuture} callback, so it is caught by the same fetch-exception path as above,
 * producing ONE {@code ExceptionWhileDataFetching} error and a null {@code data} — unlike {@code
 * CopyNodesApiIT}'s blocked-destination cases (a deliberately-null LIST item, a different
 * graphql-java mechanism), a top-level non-list field failing via a thrown fetch exception does NOT
 * also trip a redundant null-propagation error.
 */
class CloneVersionApiIT extends AbstractFilesIT {

  private static final String OWNER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OWNER_COOKIE = "ZM_AUTH_TOKEN=fake-token";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", OWNER_ID);
  }

  private static Response cloneVersion(String nodeId, int version) {
    String mutation =
        GraphqlCommandBuilder.aMutationBuilder("cloneVersion")
            .withString("node_id", nodeId)
            .withInteger("version", version)
            .withWantedResultFormat("{ id version keep_forever cloned_from_version }")
            .build();
    return graphql(mutation, OWNER_COOKIE);
  }

  @Test
  void givenAnExistingVersionCloningItShouldAppendANewVersionMarkedWithTheSourceItWasClonedFrom()
      throws SQLException {
    // Given — v2 is kept-forever AND current; cloning it proves the NEW version does NOT inherit
    // the keep-forever flag from its source (createNewFileVersion(..., false) hardcodes it false).
    // keepForever is NOT settable via seedVersion (see AbstractFilesIT#seedVersion javadoc), so v2
    // is seeded as a normal version then marked keep-forever via the public keepVersions mutation.
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

    // When — clone v2 (source == current); new version must be current(2) + 1 == 3
    Response response = cloneVersion(nodeId, 2);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(response.getBody().asString())).isEmpty();
    Map<String, Object> cloned =
        TestUtils.jsonResponseToMap(response.getBody().asString(), "cloneVersion");
    Assertions.assertThat(cloned)
        .containsEntry("id", nodeId)
        .containsEntry("version", 3)
        .containsEntry("cloned_from_version", 2)
        .containsEntry(
            "keep_forever", false); // hardcoded false by createNewFileVersion(..., false)

    Assertions.assertThat(versionRows(nodeId)).containsExactly(1, 2, 3);
  }

  @Test
  void
      givenAVersionThatDoesNotExistCloningCrashesOnAnEagerDebugLogGetInsteadOfReturningFileVersionNotFound()
          throws SQLException {
    // Given — only v1 (current) exists
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);

    // When
    Response response = cloneVersion(nodeId, 999);

    // Then — see class javadoc: the well-formed `fileVersionNotFound` branch is dead code; the
    // REAL behaviour is a NoSuchElementException from the eager debug-log `.get()`, wrapped by
    // graphql-java into a plain ExceptionWhileDataFetching error.
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("Exception while fetching data (/cloneVersion) : No value present");
    Assertions.assertThat(
            TestUtils.jsonResponseToValue(response.getBody().asString(), "cloneVersion"))
        .isEmpty();
    Assertions.assertThat(versionRows(nodeId)).containsExactly(1);
  }

  @Test
  void givenAFilestoreCopyFailureCloningShouldHardAbortWithAnExceptionWhileFetchingDataError()
      throws SQLException {
    // Given — v1 (source to clone), v2 (current)
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "v1".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    seedVersion(nodeId, "v2".getBytes(StandardCharsets.UTF_8), "file.txt", OWNER_COOKIE);
    FilesStackTestResource.getStoragesService().setCopyFails(true);

    // When
    Response response = cloneVersion(nodeId, 1);

    // Then — see class javadoc: the AbortExecutionException surfaces as ONE ordinary
    // ExceptionWhileDataFetching error (NOT a distinct HTTP status, NOT doubled like
    // CopyNodesApiIT's blocked-destination cases).
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly(
            "Exception while fetching data (/cloneVersion) : Copy error with nodeId: "
                + nodeId
                + " and version 1");
    Assertions.assertThat(
            TestUtils.jsonResponseToValue(response.getBody().asString(), "cloneVersion"))
        .isEmpty();

    // no new version row was created (createNewFileVersion only runs inside Try#onSuccess, which
    // never fires on a copy failure) — unlike copyFile's Try#onFailure, cloneVersion's failure
    // path does not need to roll anything back because nothing was ever written
    Assertions.assertThat(versionRows(nodeId)).containsExactly(1, 2);
  }
}
