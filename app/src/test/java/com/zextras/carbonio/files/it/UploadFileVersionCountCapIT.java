// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Config-split sibling of {@link UploadFileVersionApiIT} (Batch D / D3): carries the THREE
 * scenarios that need {@code application-config.max-number-of-versions} capped to {@code 2}
 * (published at runtime via setApplicationConfig on the shared stack). See {@link
 * UploadFileVersionApiIT}'s javadoc for the full split mapping (5 base + 1 in {@link
 * UploadFileVersionSizeCapIT} + 3 here = 9).
 */
class UploadFileVersionCountCapIT extends AbstractFilesIT {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String REQUESTER_COOKIE = "ZM_AUTH_TOKEN=fake-token";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", REQUESTER_ID);
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
  void givenTheVersionCapIsExceededUploadVersionShouldReturn405() throws Exception {
    // Given — cap = 2 (max-number-of-versions=2, set in @BeforeEach), node already has 3 versions
    // (3 > 2).
    // NOTE: v2/v3 are seeded via RAW JDBC (seedVersionRawJdbc), NOT the seedVersion API helper:
    // with the cap ACTIVELY enforced for this whole class, BlobService#uploadFileVersion evicts
    // the oldest surviving version as soon as the existing count reaches the cap, so calling the
    // real upload-version API repeatedly can NEVER accumulate more than 2 concurrently-existing
    // versions while the cap is live — this pre-state (more existing versions than the currently
    // configured cap allows, e.g. after an admin LOWERS the cap) is the rare API-observable-but-
    // not-API-creatable case (D1 rule 4). See seedVersionRawJdbc's javadoc for detail.
    String nodeId =
        seedFile("fake.txt", LOCAL_ROOT, "v1".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    seedVersionRawJdbc(nodeId, 2, "text/plain", 2L, REQUESTER_ID);
    seedVersionRawJdbc(nodeId, 3, "text/plain", 2L, REQUESTER_ID);

    // When
    Response response =
        uploadVersion(
            nodeId,
            "content".getBytes(StandardCharsets.UTF_8),
            "fake.txt",
            false,
            REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(405);
    Assertions.assertThat(response.getBody().asString())
        .isEqualTo(
            String.format(
                "Node %s has reached max number of versions (2), cannot add more versions",
                nodeId));
  }

  @Test
  void givenTheVersionCountAtTheCapUploadVersionShouldSucceedAndEvictTheOldestVersion()
      throws Exception {
    // Given — cap = 2, node has exactly 2 versions (2 > 2 is false -> upload succeeds; 2 >= 2 is
    // true -> the oldest non-keptForever version is evicted after the new one lands)
    String nodeId =
        seedFile("fake.txt", LOCAL_ROOT, "v1".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    seedVersion(nodeId, "v2".getBytes(StandardCharsets.UTF_8), "fake.txt", REQUESTER_COOKIE);

    // When
    Response response =
        uploadVersion(
            nodeId,
            "v3 content".getBytes(StandardCharsets.UTF_8),
            "fake.txt",
            false,
            REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Map<String, Object> json = OBJECT_MAPPER.readValue(response.getBody().asString(), Map.class);
    Assertions.assertThat(json).containsEntry("version", 3);
    // v1 (the oldest) was evicted; v2 and the new v3 remain
    Assertions.assertThat(versionRows(nodeId)).containsExactly(2, 3);
  }

  @Test
  void givenStoragesBulkDeleteReturnsANullResponseTheEvictedVersionIsStillDeleted()
      throws Exception {
    // Given — same cap/eviction setup as the successful-eviction test above, but the bulk-delete
    // endpoint returns a body the SDK deserialises as ids=null, which throws an NPE that
    // production explicitly catches and treats as "all deletes succeeded" (documents the SDK-bug
    // current behaviour, per the plan's finding — NOT fixed here).
    String nodeId =
        seedFile("fake.txt", LOCAL_ROOT, "v1".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    seedVersion(nodeId, "v2".getBytes(StandardCharsets.UTF_8), "fake.txt", REQUESTER_COOKIE);
    FilesStackTestResource.getStoragesService().setBulkDeleteReturnsNull(true);

    // When
    Response response =
        uploadVersion(
            nodeId,
            "v3 content".getBytes(StandardCharsets.UTF_8),
            "fake.txt",
            false,
            REQUESTER_COOKIE);

    // Then — upload succeeds, and despite the SDK-null bug the oldest version is STILL deleted
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Map<String, Object> json = OBJECT_MAPPER.readValue(response.getBody().asString(), Map.class);
    Assertions.assertThat(json).containsEntry("version", 3);
    Assertions.assertThat(versionRows(nodeId)).containsExactly(2, 3);
  }
}
