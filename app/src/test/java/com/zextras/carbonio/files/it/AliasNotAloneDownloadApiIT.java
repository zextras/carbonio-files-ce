// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code com.zextras.carbonio.files.acceptance.AliasNotAloneDownloadApiIT} rewritten as an
 * out-of-process {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}: the {@code LOCAL_ROOT}
 * alias branch of {@code BlobService#checkDownloadMultipleInternal} on {@code POST
 * /download-multiple} — {@code LOCAL_ROOT} combined with any other id is rejected with {@code
 * AliasNotAloneInDownload} (400), while {@code LOCAL_ROOT} passed alone resolves to its children.
 *
 * <p>NOTE (ported verbatim from the original): the negative scenario substantially overlaps {@link
 * DownloadMultipleApiIT#givenLocalRootWithOtherNodesTheDownloadMultipleShouldReturn400}, which
 * already asserts the 400 status. This class adds the exact response-body assertion (the {@code
 * ExceptionsHandler} generic-body branch) and pairs it with the resolves-to-children positive case.
 */
class AliasNotAloneDownloadApiIT extends AbstractFilesIT {

  private static final String OWNER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OWNER_COOKIE = "ZM_AUTH_TOKEN=fake-token";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", OWNER_ID);
  }

  @Test
  void givenLocalRootAndAnotherNodeIdThenDownloadMultipleReturns400WithGenericBadRequestBody() {
    // Given
    String fileId =
        seedFile("file.txt", LOCAL_ROOT, "content".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);

    // When — LOCAL_ROOT is not the only id passed -> AliasNotAloneInDownload
    Response response = downloadMultiple(List.of(LOCAL_ROOT, fileId), OWNER_COOKIE);

    // Then — ExceptionsHandler maps AliasNotAloneInDownload to a generic 400 body (the actual
    // exception message is discarded, per ExceptionsHandler's BadRequestException-like branch).
    Assertions.assertThat(response.getStatusCode()).isEqualTo(400);
    Assertions.assertThat(response.getBody().asString()).isEqualTo("400 Bad Request");
  }

  @Test
  void givenLocalRootAloneThenDownloadMultipleResolvesToItsChildrenAndReturnsZipWith200() {
    // Given
    String fileId1 =
        seedFile(
            "file1.txt", LOCAL_ROOT, "content-1".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);
    String fileId2 =
        seedFile(
            "file2.txt", LOCAL_ROOT, "content-2".getBytes(StandardCharsets.UTF_8), OWNER_COOKIE);

    // When — LOCAL_ROOT passed alone resolves to its children (both files).
    Response response = downloadMultiple(List.of(LOCAL_ROOT), OWNER_COOKIE);

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(response.getHeader("Content-Type")).contains("application/zip");

    FilesStackTestResource.getStoragesService().verifyDownloaded(fileId1, 1);
    FilesStackTestResource.getStoragesService().verifyDownloaded(fileId2, 1);
  }
}
