// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Config-split sibling of {@link KeepVersionsApiIT} (Batch G / D3): carries the ONE scenario that
 * needs the keep-forever cap ({@code maxNumberOfKeepVersions}) already reached. This class uses the
 * same version cap of 2 (published at runtime via setApplicationConfig on the shared stack) as
 * {@link CloneVersionCountCapIT} — {@code maxNumberOfKeepVersions = maxNumberOfVersions -
 * Constants.Config.DIFF_MAX_VERSION_AND_MAX_KEEP_VERSION} (2), so a total-version cap of 2 yields a
 * keep-forever cap of EXACTLY 0. Unlike the original seam (class-wide cap of 3, giving a
 * keep-forever cap of 1, requiring one version to be PRE-marked keep-forever to fill it), a cap of
 * 0 means the counter (starts at 0) is never {@code < 0} — so the FIRST attempt to mark ANY version
 * keep-forever already trips {@code keepVersionsFetcher}'s {@code !keepForever || counter < cap}
 * guard, without needing to pre-seed an already-kept-forever version. Same code branch, same {@code
 * VERSIONS_LIMIT_REACHED} error shape, one fewer setup step.
 */
class KeepVersionsCountCapIT extends AbstractFilesIT {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private static final String OWNER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OWNER_COOKIE = "ZM_AUTH_TOKEN=fake-token";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", OWNER_ID);
  }

  private static final String MAX_NUMBER_OF_VERSIONS = "max-number-of-versions";

  @BeforeEach
  void capVersionsToTwo() {
    setApplicationConfig(MAX_NUMBER_OF_VERSIONS, "2");
  }

  @AfterEach
  void restoreVersionCap() {
    clearApplicationConfig(MAX_NUMBER_OF_VERSIONS, "30");
  }

  /** Raw {@code errors[].extensions.errorCode} values, in response order. */
  private static List<String> errorCodes(String bodyPayload) throws Exception {
    JsonNode root = OBJECT_MAPPER.readTree(bodyPayload);
    List<String> codes = new ArrayList<>();
    if (root.has("errors")) {
      for (JsonNode error : root.get("errors")) {
        JsonNode extensions = error.get("extensions");
        codes.add(
            extensions != null && extensions.has("errorCode")
                ? extensions.get("errorCode").asText()
                : null);
      }
    }
    return codes;
  }

  @Test
  void givenTheKeepCapAlreadyReachedMarkingAVersionShouldReturnTooManyVersionsError()
      throws Exception {
    // Given — cap is 0 (maxNumberOfKeepVersions, derived from max-number-of-versions=2 set in @BeforeEach); v1
    // (current) exists, no version is kept-forever yet
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);

    // When — try to mark v1 as keep-forever (counter(0) < cap(0) is false -> blocked)
    String mutation =
        GraphqlCommandBuilder.aMutationBuilder("keepVersions")
            .withString("node_id", nodeId)
            .withListOfIntegers("versions", new int[] {1})
            .withBoolean("keep_forever", true)
            .withWantedResultFormat("")
            .build();
    Response response = graphql(mutation, OWNER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(
            (List<Integer>)
                TestUtils.jsonResponseToValue(response.getBody().asString(), "keepVersions")
                    .orElse(List.of()))
        .isEmpty();
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly(
            "There was a problem while executing requested operation on node: " + nodeId);
    Assertions.assertThat(errorCodes(response.getBody().asString()))
        .containsExactly("VERSIONS_LIMIT_REACHED");

    // v1 was NOT marked
    String query =
        GraphqlCommandBuilder.aQueryBuilder("getVersions")
            .withString("node_id", nodeId)
            .withWantedResultFormat("{ version keep_forever }")
            .build();
    Response versionsResponse = graphql(query, OWNER_COOKIE);
    List<java.util.Map<String, Object>> versions =
        TestUtils.jsonResponseToList(versionsResponse.getBody().asString(), "getVersions");
    Assertions.assertThat(versions)
        .filteredOn(v -> (Integer) v.get("version") == 1)
        .extracting(v -> v.get("keep_forever"))
        .containsExactly(false);
  }
}
