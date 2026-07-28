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
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code com.zextras.carbonio.files.acceptance.KeepVersionsApiIT} rewritten as an out-of-process
 * {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}. The {@code keepVersions} mutation
 * (bound to {@code NodeDataFetcher#keepVersionsFetcher}) is exercised here on the DEFAULT
 * (unbounded) version cap — {@code maxNumberOfKeepVersions} is derived from {@code
 * maxNumberOfVersions - 2} (see {@code Constants.Config.DIFF_MAX_VERSION_AND_MAX_KEEP_VERSION}),
 * so the default cap (30) leaves 28 keep-forever slots, far more than these 3 scenarios (which
 * each mark/unmark at most one version) ever need. The cap-reached scenario is split into the
 * sibling {@link KeepVersionsCountCapIT}, since the cap is a boot-time config snapshot for the
 * WHOLE launched process, not settable per-method.
 *
 * <p><b>FINDING (carried over, confirmed dead code):</b> the missing-version error composition at
 * the end of {@code keepVersionsFetcher} filters {@code List<FileVersion>} against {@code
 * List<GraphQLError>} via {@code .contains(...)} — a {@code FileVersion} can never {@code .equals}
 * a {@code GraphQLError}, so that filter's predicate is always {@code false} and the branch that
 * would emit a {@code fileVersionNotFound} for a missing/non-existent requested version can NEVER
 * execute. {@code fileVersionRepository.getFileVersions(nodeId, versions)} is a plain SQL {@code
 * WHERE node_id = ? AND version IN (...)} query, so a version number with no matching row is
 * simply absent from the result list, not represented by a null placeholder. The combined,
 * observable effect: requesting a version that doesn't exist produces NEITHER a data entry NOR
 * any error for it — it vanishes without a trace. Asserted below, not fixed (test-only task).
 */
class KeepVersionsApiIT extends AbstractFilesIT {

  private static final String OWNER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OWNER_COOKIE = "ZM_AUTH_TOKEN=fake-token";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", OWNER_ID);
  }

  private static Response keepVersions(String nodeId, boolean keepForever, int... versions) {
    String mutation =
        GraphqlCommandBuilder.aMutationBuilder("keepVersions")
            .withString("node_id", nodeId)
            .withListOfIntegers("versions", versions)
            .withBoolean("keep_forever", keepForever)
            .withWantedResultFormat("")
            .build();
    return graphql(mutation, OWNER_COOKIE);
  }

  @SuppressWarnings("unchecked")
  private static List<Integer> keptVersions(Response response) {
    return (List<Integer>)
        TestUtils.jsonResponseToValue(response.getBody().asString(), "keepVersions").orElse(List.of());
  }

  private static boolean keepForeverFlagOf(String nodeId, int version) {
    String query =
        GraphqlCommandBuilder.aQueryBuilder("getVersions")
            .withString("node_id", nodeId)
            .withWantedResultFormat("{ version keep_forever }")
            .build();
    Response response = graphql(query, OWNER_COOKIE);
    List<Map<String, Object>> versions =
        TestUtils.jsonResponseToList(response.getBody().asString(), "getVersions");
    return versions.stream()
        .filter(v -> version == (Integer) v.get("version"))
        .findFirst()
        .map(v -> (Boolean) v.get("keep_forever"))
        .orElseThrow();
  }

  @Test
  void givenANonKeptVersionMarkingItKeepForeverShouldSuceedAndPersistTheFlag() {
    // Given — v1 (non-current), v2 (current); no version is kept-forever yet
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "v1".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    seedVersion(nodeId, "v2".getBytes(StandardCharsets.UTF_8), "file.txt", OWNER_COOKIE); // v2, current

    // When
    Response response = keepVersions(nodeId, true, 1);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(keptVersions(response)).containsExactly(1);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(response.getBody().asString())).isEmpty();
    Assertions.assertThat(keepForeverFlagOf(nodeId, 1)).isTrue();
    Assertions.assertThat(keepForeverFlagOf(nodeId, 2)).isFalse();
  }

  @Test
  void givenAKeptForeverVersionUnmarkingItShouldSucceedAndClearTheFlag() {
    // Given — v2 is kept-forever (non-current), v3 is current
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "v1".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    seedVersion(nodeId, "v2".getBytes(StandardCharsets.UTF_8), "file.txt", OWNER_COOKIE); // v2
    keepVersions(nodeId, true, 2); // mark v2 keep-forever
    seedVersion(nodeId, "v3".getBytes(StandardCharsets.UTF_8), "file.txt", OWNER_COOKIE); // v3, current

    // When — unmark v2 (keep_forever:false is never cap-checked, see keepVersionsFetcher's
    // `!keepForever || counter < cap` condition)
    Response response = keepVersions(nodeId, false, 2);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(keptVersions(response)).containsExactly(2);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(response.getBody().asString())).isEmpty();
    Assertions.assertThat(keepForeverFlagOf(nodeId, 2)).isFalse();
  }

  @Test
  void givenARequestedVersionThatDoesNotExistItVanishesWithNoDataAndNoError() {
    // Given — only v1 (current) exists; per the class javadoc's dead-filter finding, version 999
    // will produce NEITHER a data entry NOR a fileVersionNotFound error
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);

    // When — mix one real, existing version with one that does not exist
    Response response = keepVersions(nodeId, true, 1, 999);

    // Then — the real version IS processed normally...
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(keptVersions(response)).containsExactly(1);
    Assertions.assertThat(keepForeverFlagOf(nodeId, 1)).isTrue();

    // ...while the missing version 999 produces NO error at all (confirmed dead code — NOT a
    // fileVersionNotFound, despite `deleteVersions`' analogous but reachable check for the same
    // scenario).
    Assertions.assertThat(TestUtils.jsonResponseToErrors(response.getBody().asString())).isEmpty();
  }
}
