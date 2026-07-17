// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.acceptance;

import com.zextras.carbonio.files.FilesStackTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.acceptance.seam.FilesTestApp;
import com.zextras.carbonio.files.acceptance.seam.impl.QuarkusFilesTestAppBuilder;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.api.utilities.entities.SimplePopulatorTextFile;
import com.zextras.carbonio.files.utilities.http.HttpRequest;
import com.zextras.carbonio.files.utilities.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Task 2.6 (part 1) of the acceptance coverage-expansion plan: the {@code keepVersions} mutation
 * (bound to {@code NodeDataFetcher#keepVersionsFetcher}).
 *
 * <p><b>Cap plumbing:</b> {@code keepVersionsFetcher}'s cap ({@code maxNumberOfKeepVersions}) is
 * derived ONCE at {@code NodeDataFetcher} CONSTRUCTION from {@code maxNumberOfVersions - 2}
 * (floored at 0), which itself is read directly from Service-Discover, bypassing {@code
 * FilesConfig} entirely. This class therefore sets the cap via the builder's {@code
 * withMaxNumberOfVersions(3)} (giving {@code maxNumberOfKeepVersions == 1}) BEFORE {@code build()}
 * for the whole class — a post-build {@code Mocks#setMaxNumberOfVersions} call would be too late
 * to affect this fetcher (see that method's own javadoc). A keep-cap of 1 is generous enough to
 * let the mark/unmark/missing-version tests (which each mark at most one NEW version while no
 * other version is already kept-forever) succeed normally, while still letting the dedicated cap
 * test trip it by pre-seeding one already-kept-forever version.
 *
 * <p><b>FINDING (confirmed dead code — see plan §1/§9):</b> the missing-version error composition
 * at the end of {@code keepVersionsFetcher} filters {@code List<FileVersion>} against {@code
 * List<GraphQLError>} via {@code .contains(...)} — a {@code FileVersion} can never {@code .equals}
 * a {@code GraphQLError}, so that filter's predicate is always {@code false} and the branch that
 * would emit a {@code fileVersionNotFound} for a missing/non-existent requested version can NEVER
 * execute. In addition, {@code fileVersionRepository.getFileVersions(nodeId, versions)} is a plain
 * SQL {@code WHERE node_id = ? AND version IN (...)} query (see {@code
 * FileVersionRepositoryEbean#getFileVersions(String, Collection)}) — a version number with no
 * matching row is simply absent from the result list, not represented by a null placeholder. The
 * combined, observable effect: requesting a version that doesn't exist produces NEITHER a data
 * entry NOR any error for it — it vanishes without a trace. This is asserted below (not fixed —
 * Phase 1 forbids {@code src/main} changes).
 */
@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class KeepVersionsApiIT {

  static FilesTestApp app;
  static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private static final String OWNER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OTHER_USER_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String OWNER_COOKIE = "ZM_AUTH_TOKEN=fake-token";

  @BeforeAll
  static void init() {
    app =
        QuarkusFilesTestAppBuilder.aFilesTestApp()
            .withDatabase()
            .withServiceDiscover()
            .withMaxNumberOfVersions(3) // maxNumberOfKeepVersions == 1 (see class javadoc)
            .withUserManagement(
                Map.of(
                    "fake-token", OWNER_ID,
                    "fake-token-b", OTHER_USER_ID))
            .build();
  }

  @AfterEach
  void cleanUp() {
    app.backdoor().resetDatabase();
  }

  @AfterAll
  static void cleanUpAll() {
    app.close();
  }

  private HttpResponse keepVersions(String nodeId, boolean keepForever, int... versions) {
    String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("keepVersions")
            .withString("node_id", nodeId)
            .withListOfIntegers("versions", versions)
            .withBoolean("keep_forever", keepForever)
            .withWantedResultFormat("")
            .build();
    HttpRequest httpRequest = HttpRequest.of("POST", "/graphql/", OWNER_COOKIE, bodyPayload);
    return app.send(httpRequest);
  }

  @SuppressWarnings("unchecked")
  private List<Integer> keptVersions(HttpResponse httpResponse) {
    return (List<Integer>)
        TestUtils.jsonResponseToValue(httpResponse.getBodyPayload(), "keepVersions").orElse(List.of());
  }

  /** Raw {@code errors[].extensions.errorCode} values, in response order. */
  private List<String> errorCodes(String bodyPayload) throws Exception {
    JsonNode root = OBJECT_MAPPER.readTree(bodyPayload);
    List<String> codes = new ArrayList<>();
    if (root.has("errors")) {
      for (JsonNode error : root.get("errors")) {
        JsonNode extensions = error.get("extensions");
        codes.add(extensions != null && extensions.has("errorCode")
            ? extensions.get("errorCode").asText()
            : null);
      }
    }
    return codes;
  }

  private boolean keepForeverFlagOf(String nodeId, int version) {
    String bodyPayload =
        GraphqlCommandBuilder.aQueryBuilder("getVersions")
            .withString("node_id", nodeId)
            .withWantedResultFormat("{ version keep_forever }")
            .build();
    HttpResponse httpResponse =
        app.send(HttpRequest.of("POST", "/graphql/", OWNER_COOKIE, bodyPayload));
    List<Map<String, Object>> versions =
        TestUtils.jsonResponseToList(httpResponse.getBodyPayload(), "getVersions");
    return versions.stream()
        .filter(v -> version == (Integer) v.get("version"))
        .findFirst()
        .map(v -> (Boolean) v.get("keep_forever"))
        .orElseThrow();
  }

  @Test
  void givenANonKeptVersionMarkingItKeepForeverShouldSuceedAndPersistTheFlag() {
    // Given — v1 (non-current), v2 (current); no version is kept-forever yet
    String nodeId = "50000000-0000-0000-0000-000000000001";
    app.backdoor()
        .populator()
        .addNode(new SimplePopulatorTextFile(nodeId, OWNER_ID, "file.txt"))
        .addVersion(nodeId); // v2, current

    // When
    HttpResponse httpResponse = keepVersions(nodeId, true, 1);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Assertions.assertThat(keptVersions(httpResponse)).containsExactly(1);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload())).isEmpty();
    Assertions.assertThat(keepForeverFlagOf(nodeId, 1)).isTrue();
    Assertions.assertThat(keepForeverFlagOf(nodeId, 2)).isFalse();
  }

  @Test
  void givenAKeptForeverVersionUnmarkingItShouldSucceedAndClearTheFlag() {
    // Given — v2 is kept-forever (non-current), v3 is current
    String nodeId = "50000000-0000-0000-0000-000000000002";
    app.backdoor()
        .populator()
        .addNode(new SimplePopulatorTextFile(nodeId, OWNER_ID, "file.txt"))
        .addVersion(nodeId, true) // v2, keepForever=true
        .addVersion(nodeId); // v3, current

    // When — unmark v2 (keep_forever:false is never cap-checked, see keepVersionsFetcher's
    // `!keepForever || counter < cap` condition)
    HttpResponse httpResponse = keepVersions(nodeId, false, 2);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Assertions.assertThat(keptVersions(httpResponse)).containsExactly(2);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload())).isEmpty();
    Assertions.assertThat(keepForeverFlagOf(nodeId, 2)).isFalse();
  }

  @Test
  void givenTheKeepCapAlreadyReachedMarkingAnotherVersionShouldReturnTooManyVersionsError()
      throws Exception {
    // Given — cap is 1 (maxNumberOfKeepVersions, from withMaxNumberOfVersions(3)); v2 is ALREADY
    // kept-forever (keepForeverCounter starts at 1, which is NOT < cap(1))
    String nodeId = "50000000-0000-0000-0000-000000000003";
    app.backdoor()
        .populator()
        .addNode(new SimplePopulatorTextFile(nodeId, OWNER_ID, "file.txt"))
        .addVersion(nodeId, true) // v2, keepForever=true (fills the cap)
        .addVersion(nodeId); // v3, current

    // When — try to ALSO mark v1 as keep-forever
    HttpResponse httpResponse = keepVersions(nodeId, true, 1);

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Assertions.assertThat(keptVersions(httpResponse)).isEmpty();
    List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly("There was a problem while executing requested operation on node: " + nodeId);
    Assertions.assertThat(errorCodes(httpResponse.getBodyPayload()))
        .containsExactly("VERSIONS_LIMIT_REACHED");

    // v1 was NOT marked
    Assertions.assertThat(keepForeverFlagOf(nodeId, 1)).isFalse();
  }

  @Test
  void givenARequestedVersionThatDoesNotExistItVanishesWithNoDataAndNoError() {
    // Given — only v1 (current) exists; per the class javadoc's dead-filter finding, version 999
    // will produce NEITHER a data entry NOR a fileVersionNotFound error
    String nodeId = "50000000-0000-0000-0000-000000000004";
    app.backdoor().populator().addNode(new SimplePopulatorTextFile(nodeId, OWNER_ID, "file.txt"));

    // When — mix one real, existing version with one that does not exist
    HttpResponse httpResponse = keepVersions(nodeId, true, 1, 999);

    // Then — the real version IS processed normally...
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Assertions.assertThat(keptVersions(httpResponse)).containsExactly(1);
    Assertions.assertThat(keepForeverFlagOf(nodeId, 1)).isTrue();

    // ...while the missing version 999 produces NO error at all (confirmed dead code — NOT a
    // fileVersionNotFound, despite `deleteVersions`' analogous but reachable check for the same
    // scenario).
    Assertions.assertThat(TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload())).isEmpty();
  }
}
