// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.acceptance;

import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.acceptance.seam.FilesTestApp;
import com.zextras.carbonio.files.acceptance.seam.impl.GuiceNettyFilesTestAppBuilder;
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
 * Task 2.6 (part 2) of the acceptance coverage-expansion plan: the {@code cloneVersion} mutation
 * (bound to {@code NodeDataFetcher#cloneVersionFetcher}), driven through {@code
 * Mocks#storagesCopySucceeds}/{@code storagesCopyFails} and the builder's {@code
 * withMaxNumberOfVersions(int)} (the total-version cap, {@code maxNumberOfVersions}, read ONCE at
 * {@code NodeDataFetcher} construction — same seam constraint documented on {@code
 * KeepVersionsApiIT}). A class-wide cap of 3 is generous enough for the happy/not-found/hard-abort
 * fixtures (which each seed at most 2 pre-existing versions) while still letting the dedicated cap
 * test trip it by seeding exactly 3.
 *
 * <p><b>FINDING (NEW, more severe than the plan anticipated) — the graceful "version not found"
 * branch is DEAD CODE, killed by an eager debug-log {@code Optional#get()}:</b> immediately before
 * the safe {@code fileVersionRepository.getFileVersion(nodeId, versionToClone).map(...).orElse(
 * fileVersionNotFound(...))} chain, {@code cloneVersionFetcher} builds a debug log line via:
 *
 * <pre>{@code
 * logger.debug(MessageFormat.format(
 *   "Version to clone {0}, id fetched to clone {1}, node current version {2}",
 *   versionToClone,
 *   fileVersionRepository.getFileVersion(nodeId, versionToClone).get().getNodeId(), // unconditional .get()
 *   node.getCurrentVersion()
 * ));
 * }</pre>
 *
 * <p>{@code MessageFormat.format}'s arguments are plain Java method arguments, evaluated EAGERLY
 * regardless of whether DEBUG logging is even enabled — so this {@code .get()} runs on every
 * call, unconditionally, BEFORE the graceful chain below it ever executes. When {@code
 * versionToClone} does not correspond to any {@code FileVersion} row, this throws a plain {@code
 * NoSuchElementException("No value present")} straight out of the {@code
 * CompletableFuture.supplyAsync} callback. graphql-java's default {@code
 * SimpleDataFetcherExceptionHandler} (no custom handler is registered — see {@code
 * GraphQLProvider#setup}) wraps ANY such fetch exception into a field-scoped {@code
 * ExceptionWhileDataFetching} error, message {@code "Exception while fetching data (/cloneVersion)
 * : No value present"} (verified empirically and against graphql-java 22.4 bytecode; the same
 * message shape is asserted elsewhere in this suite, e.g. {@code PublicFindNodesApiIT}'s {@code
 * "Exception while fetching data (/findNodes) : Invalid token signature"}). The DELIBERATE,
 * well-formed {@code fileVersionNotFound(nodeId, versionToClone, path)} error on the {@code
 * .orElse(...)} branch a few lines below can therefore NEVER be produced by a genuinely
 * non-existent version — asserted below as the REAL (crash-shaped) behaviour, not fixed (Phase 1
 * forbids {@code src/main} changes).
 *
 * <p><b>FINDING — hard abort, unlike copy/move/delete's graceful degradation (see plan §1/§9),
 * but surfacing as a SINGLE error, not doubled:</b> {@code cloneVersionFetcher}'s filestore-copy
 * failure path ({@code Try#onFailure(failure -> { ...; throw new AbortExecutionException(error);
 * })}) THROWS rather than degrading, exactly like the dead-code case above: the exception is
 * thrown from INSIDE the async {@code CompletableFuture} callback, so it never reaches
 * graphql-java's synchronous, TOP-LEVEL {@code AbortExecutionException} handling in {@code
 * ExecutionStrategy#handleNonNullException} — instead it is caught by the SAME {@code
 * handleFetchingException}/{@code SimpleDataFetcherExceptionHandler} path, producing ONE {@code
 * ExceptionWhileDataFetching} error (message {@code "Exception while fetching data (/cloneVersion)
 * : Copy error with nodeId: <id> and version <v>"}) and a null {@code data}. Verified empirically
 * that — UNLIKE {@code CopyNodesApiIT}'s blocked-destination/filestore-copy-fail cases (which
 * return a deliberately-null LIST ITEM through a normal {@code DataFetcherResult}, tripping
 * graphql-java's separate per-item non-null-list-element check for a SECOND error) — a top-level,
 * non-list {@code File!} field failing via a THROWN fetch exception does NOT also trip a
 * redundant null-propagation error here: the field is already in an "errored" state from the fetch
 * exception itself, so graphql-java does not additionally report "wrongly returned a null value"
 * for it. So "hard abort" is real in the sense that the DATA-FETCHER code deliberately throws
 * instead of degrading like {@code copyFile}'s {@code Try#onFailure} does — but it surfaces as
 * exactly ONE ordinary GraphQL error, not a distinct HTTP status, not a doubled error count, and
 * not a full top-level {@code AbortExecutionException} envelope.
 */
class CloneVersionApiIT {

  static FilesTestApp app;

  private static final String OWNER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OWNER_COOKIE = "ZM_AUTH_TOKEN=fake-token";

  @BeforeAll
  static void init() {
    app =
        GuiceNettyFilesTestAppBuilder.aFilesTestApp()
            .withDatabase()
            .withServiceDiscover()
            .withMaxNumberOfVersions(3) // see class javadoc
            .withUserManagement(Map.of("fake-token", OWNER_ID))
            .withStorages()
            .build();
  }

  @AfterEach
  void cleanUp() {
    app.backdoor().resetDatabase();
    app.mocks().reset();
  }

  @AfterAll
  static void cleanUpAll() {
    app.close();
  }

  private HttpResponse cloneVersion(String nodeId, int version) {
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("cloneVersion")
            .withString("node_id", nodeId)
            .withInteger("version", version)
            .withWantedResultFormat("{ id version keep_forever cloned_from_version }")
            .build();
    HttpRequest httpRequest = HttpRequest.of("POST", "/graphql/", OWNER_COOKIE, bodyPayload);
    return app.send(httpRequest);
  }

  @Test
  void givenAnExistingVersionCloningItShouldAppendANewVersionMarkedWithTheSourceItWasClonedFrom() {
    // Given — v2 is kept-forever AND current; cloning it proves the NEW version does NOT inherit
    // the keep-forever flag from its source (createNewFileVersion(..., false) hardcodes it false)
    String nodeId = "60000000-0000-0000-0000-000000000001";
    app.backdoor()
        .populator()
        .addNode(new SimplePopulatorTextFile(nodeId, OWNER_ID, "file.txt")) // v1
        .addVersion(nodeId, true); // v2, keepForever=true, current
    app.mocks().storagesCopySucceeds();

    // When — clone v2 (source == current); new version must be current(2) + 1 == 3
    HttpResponse httpResponse = cloneVersion(nodeId, 2);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload())).isEmpty();
    Map<String, Object> cloned = TestUtils.jsonResponseToMap(httpResponse.getBodyPayload(), "cloneVersion");
    Assertions.assertThat(cloned)
        .containsEntry("id", nodeId)
        .containsEntry("version", 3)
        .containsEntry("cloned_from_version", 2)
        .containsEntry("keep_forever", false); // hardcoded false by createNewFileVersion(..., false)

    Assertions.assertThat(app.backdoor().remainingVersionNumbers(nodeId)).containsExactly(1, 2, 3);
  }

  @Test
  void givenTheTotalVersionCapAlreadyReachedCloningShouldReturnTooManyVersionsError() {
    // Given — cap is 3 (maxNumberOfVersions); node already has exactly 3 versions
    String nodeId = "60000000-0000-0000-0000-000000000002";
    app.backdoor()
        .populator()
        .addNode(new SimplePopulatorTextFile(nodeId, OWNER_ID, "file.txt"))
        .addVersion(nodeId)
        .addVersion(nodeId);

    // When — the cap check (`size() >= maxNumberOfVersions`) runs BEFORE the copy attempt, so no
    // storages mock is required for this scenario to reach its error
    HttpResponse httpResponse = cloneVersion(nodeId, 1);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("There was a problem while executing requested operation on node: " + nodeId);
    Assertions.assertThat(TestUtils.jsonResponseToValue(httpResponse.getBodyPayload(), "cloneVersion")).isEmpty();

    // no new version was created
    Assertions.assertThat(app.backdoor().remainingVersionNumbers(nodeId)).containsExactly(1, 2, 3);
  }

  @Test
  void givenAVersionThatDoesNotExistCloningCrashesOnAnEagerDebugLogGetInsteadOfReturningFileVersionNotFound() {
    // Given — only v1 (current) exists
    String nodeId = "60000000-0000-0000-0000-000000000003";
    app.backdoor().populator().addNode(new SimplePopulatorTextFile(nodeId, OWNER_ID, "file.txt"));

    // When
    HttpResponse httpResponse = cloneVersion(nodeId, 999);

    // Then — see class javadoc: the well-formed `fileVersionNotFound` branch is dead code; the
    // REAL behaviour is a NoSuchElementException from the eager debug-log `.get()`, wrapped by
    // graphql-java into a plain ExceptionWhileDataFetching error.
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("Exception while fetching data (/cloneVersion) : No value present");
    Assertions.assertThat(TestUtils.jsonResponseToValue(httpResponse.getBodyPayload(), "cloneVersion")).isEmpty();
    Assertions.assertThat(app.backdoor().remainingVersionNumbers(nodeId)).containsExactly(1);
  }

  @Test
  void givenAFilestoreCopyFailureCloningShouldHardAbortWithAnExceptionWhileFetchingDataError() {
    // Given — v1 (source to clone), v2 (current)
    String nodeId = "60000000-0000-0000-0000-000000000004";
    app.backdoor()
        .populator()
        .addNode(new SimplePopulatorTextFile(nodeId, OWNER_ID, "file.txt"))
        .addVersion(nodeId);
    app.mocks().storagesCopyFails();

    // When
    HttpResponse httpResponse = cloneVersion(nodeId, 1);

    // Then — see class javadoc: the AbortExecutionException surfaces as ONE ordinary
    // ExceptionWhileDataFetching error (NOT a distinct HTTP status, NOT doubled like
    // CopyNodesApiIT's blocked-destination cases — those are a deliberately-null LIST item, a
    // different graphql-java mechanism than a thrown fetch exception on a single object field).
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("Exception while fetching data (/cloneVersion) : Copy error with nodeId: " + nodeId + " and version 1");
    Assertions.assertThat(TestUtils.jsonResponseToValue(httpResponse.getBodyPayload(), "cloneVersion")).isEmpty();

    // no new version row was created (createNewFileVersion only runs inside Try#onSuccess, which
    // never fires on a copy failure) — unlike copyFile's Try#onFailure, cloneVersion's failure
    // path does not need to roll anything back because nothing was ever written
    Assertions.assertThat(app.backdoor().remainingVersionNumbers(nodeId)).containsExactly(1, 2);
  }
}
