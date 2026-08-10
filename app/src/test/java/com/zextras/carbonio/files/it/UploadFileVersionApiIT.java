// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code com.zextras.carbonio.files.acceptance.UploadFileVersionApiIT} rewritten as an
 * out-of-process {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}: {@code POST
 * /upload-version}.
 *
 * <p><b>Config-split (D3/Batch D):</b> the original class held 9 methods. Four scenarios need a
 * config cap that cannot share this class's default (uncapped/unlimited-versions) stack, since
 * {@code FilesConfig}'s tunables are boot-time snapshots on the launched out-of-process app:
 *
 * <ul>
 *   <li>{@code givenABodyOverTheConfiguredSizeCapUploadVersionShouldReturn413} (upload-size-cap=0)
 *       moved to {@link UploadFileVersionSizeCapIT} (upload-size cap set at runtime on the shared stack).
 *   <li>{@code givenTheVersionCapIsExceededUploadVersionShouldReturn405}, {@code
 *       givenTheVersionCountAtTheCapUploadVersionShouldSucceedAndEvictTheOldestVersion} and {@code
 *       givenStoragesBulkDeleteReturnsANullResponseTheEvictedVersionIsStillDeleted} (all three set
 *       max-number-of-versions=2 in the original) moved to {@link UploadFileVersionCountCapIT}
 *       (version cap set at runtime on the shared stack).
 * </ul>
 *
 * <p>This class keeps the remaining 5 methods on the shared default stack. Mapping: 5 (here) + 1
 * ({@code UploadFileVersionSizeCapIT}) + 3 ({@code UploadFileVersionCountCapIT}) = 9 (unchanged
 * from the original).
 */
class UploadFileVersionApiIT extends AbstractFilesIT {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  protected static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  protected static final String OTHER_USER_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  protected static final String REQUESTER_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  protected static final String OTHER_COOKIE = "ZM_AUTH_TOKEN=fake-token-b";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", REQUESTER_ID);
    FilesStackTestResource.getUserManagementService().registerToken("fake-token-b", OTHER_USER_ID);
  }

  @Test
  void givenANewVersionUploadShouldSucceedAndReturnTheIncrementedVersion() throws Exception {
    // Given — a single existing version (v1)
    String nodeId =
        seedFile(
            "fake.txt",
            LOCAL_ROOT,
            "v1 content".getBytes(StandardCharsets.UTF_8),
            REQUESTER_COOKIE);

    // When
    Response response =
        uploadVersion(
            nodeId,
            "v2 content".getBytes(StandardCharsets.UTF_8),
            "fake.txt",
            false,
            REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Map<String, Object> json = OBJECT_MAPPER.readValue(response.getBody().asString(), Map.class);
    Assertions.assertThat(json).containsEntry("nodeId", nodeId);
    Assertions.assertThat(json).containsEntry("version", 2);
    Assertions.assertThat(versionRows(nodeId)).containsExactly(1, 2);
  }

  @Test
  void givenOverwriteTrueUploadShouldReplaceTheCurrentVersionInPlace() throws Exception {
    // Given — two existing versions (v1, v2); current version is 2
    String nodeId =
        seedFile(
            "fake.txt",
            LOCAL_ROOT,
            "v1 content".getBytes(StandardCharsets.UTF_8),
            REQUESTER_COOKIE);
    seedVersion(
        nodeId, "v2 content".getBytes(StandardCharsets.UTF_8), "fake.txt", REQUESTER_COOKIE);

    // When
    Response response =
        uploadVersion(
            nodeId,
            "overwritten content".getBytes(StandardCharsets.UTF_8),
            "fake.txt",
            true,
            REQUESTER_COOKIE);

    // Then — the response's version (2) IS > 1, so (unlike a fresh v1 upload) it IS present
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Map<String, Object> json = OBJECT_MAPPER.readValue(response.getBody().asString(), Map.class);
    Assertions.assertThat(json).containsEntry("nodeId", nodeId);
    Assertions.assertThat(json).containsEntry("version", 2);
    // Same version count/numbers as before -- v2's row was replaced in place, not appended to
    Assertions.assertThat(versionRows(nodeId)).containsExactly(1, 2);
    FilesStackTestResource.getStoragesService().verifyUploaded(nodeId, 2);
  }

  @Test
  void givenNoWritePermissionUploadVersionShouldReturn404() {
    // Given — node owned by OTHER_USER_ID, shared READ_ONLY (no write) with the requester
    String nodeId =
        seedFile(
            "notMine.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OTHER_COOKIE);
    seedShare(nodeId, REQUESTER_ID, ACL.SharePermission.READ_ONLY, OTHER_COOKIE);

    // When
    Response response =
        uploadVersion(
            nodeId,
            "content".getBytes(StandardCharsets.UTF_8),
            "notMine.txt",
            false,
            REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(404);
    Assertions.assertThat(response.getBody().asString()).isEqualTo("404 Not Found");
  }

  @Test
  void givenANonExistentNodeIdUploadVersionShouldReturnTheSame404AsNoPermission() {
    // Given — see class-level FINDING (carried over from the seam original): a syntactically-valid
    // but non-existent id fails the SAME early permission gate as "no permission", not the deeper
    // (dead) not-found branch.
    String nonExistentId = "10000000-0000-0000-0000-00000000ffff";

    // When
    Response response =
        uploadVersion(
            nonExistentId,
            "content".getBytes(StandardCharsets.UTF_8),
            "ghost.txt",
            false,
            REQUESTER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(404);
    Assertions.assertThat(response.getBody().asString()).isEqualTo("404 Not Found");
  }

  @Test
  void givenAMimeTypeMismatchUploadVersionShouldReturn400() {
    // Given — existing node is TEXT (fake.txt); new filename maps to IMAGE
    String nodeId =
        seedFile(
            "fake.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);

    // When
    Response response =
        uploadVersion(
            nodeId,
            "content".getBytes(StandardCharsets.UTF_8),
            "photo.png",
            false,
            REQUESTER_COOKIE);

    // Then — FileTypeMismatchException groups with the GENERIC-body BAD_REQUEST branch in
    // ExceptionsHandler (same group as BadRequestException/IllegalArgumentException), so the
    // actual descriptive message is discarded just like FileSizeException's.
    Assertions.assertThat(response.getStatusCode()).isEqualTo(400);
    Assertions.assertThat(response.getBody().asString()).isEqualTo("400 Bad Request");
  }
}
