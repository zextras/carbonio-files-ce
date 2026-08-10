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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Config-split sibling of {@link CloneVersionApiIT} (Batch G / D3): carries the ONE scenario that
 * needs {@code application-config.max-number-of-versions} capped (cap=2, published at runtime via
 * setApplicationConfig on the shared stack) — {@code cloneVersionFetcher}'s total-version-cap check ({@code
 * fileVersionRepository.getFileVersions(...).size() >= maxNumberOfVersions}) runs BEFORE any
 * filestore copy, so no storages stub is needed for this scenario to reach its error.
 */
class CloneVersionCountCapIT extends AbstractFilesIT {

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

  @Test
  void givenTheTotalVersionCapAlreadyReachedCloningShouldReturnTooManyVersionsError()
      throws SQLException {
    // Given — cap is 2 (max-number-of-versions=2, set in @BeforeEach); node already has exactly 2 versions
    // (2 >= 2 -> the cap check trips before any copy attempt is made)
    String nodeId =
        seedFile("file.txt", LOCAL_ROOT, "v1".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    seedVersion(nodeId, "v2".getBytes(StandardCharsets.UTF_8), "file.txt", OWNER_COOKIE);

    // When
    String mutation =
        GraphqlCommandBuilder.aMutationBuilder("cloneVersion")
            .withString("node_id", nodeId)
            .withInteger("version", 1)
            .withWantedResultFormat("{ id version keep_forever cloned_from_version }")
            .build();
    Response response = graphql(mutation, OWNER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    List<String> errors = TestUtils.jsonResponseToErrors(response.getBody().asString());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly(
            "There was a problem while executing requested operation on node: " + nodeId);
    Assertions.assertThat(
            TestUtils.jsonResponseToValue(response.getBody().asString(), "cloneVersion"))
        .isEmpty();

    // no new version was created
    Assertions.assertThat(versionRows(nodeId)).containsExactly(1, 2);
  }
}
