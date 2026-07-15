// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zextras.carbonio.files.Constants;
import com.zextras.carbonio.files.acceptance.seam.FilesTestApp;
import com.zextras.carbonio.files.acceptance.seam.impl.GuiceNettyFilesTestAppBuilder;
import com.zextras.carbonio.files.api.utilities.entities.PopulatorNode;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.utilities.http.HttpRequest;
import com.zextras.carbonio.files.utilities.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Covers the {@code LOCAL_ROOT} alias branch of {@code BlobService#checkDownloadMultipleInternal}
 * on {@code POST /download-multiple} (Task 1.9 of the acceptance coverage-expansion plan):
 * {@code LOCAL_ROOT} combined with any other id is rejected with {@code AliasNotAloneInDownload}
 * (400), while {@code LOCAL_ROOT} passed alone is resolved to its children.
 *
 * <p>NOTE: the negative scenario substantially overlaps an existing test in {@code
 * DownloadMultipleApiIT} ({@code givenLocalRootWithOtherNodesTheDownloadMultipleShouldReturn400}),
 * which already asserts the 400 status. This file adds the exact response-body assertion (the
 * {@code ExceptionsHandler} generic-body branch for {@code BadRequestException}-like causes) and
 * pairs it with the resolves-to-children positive case, per the plan's Task 1.9 scope.
 */
class AliasNotAloneDownloadApiIT {

  static FilesTestApp app;
  static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private static final String OWNER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

  @BeforeAll
  static void init() {
    app =
        GuiceNettyFilesTestAppBuilder.aFilesTestApp()
            .withDatabase()
            .withServiceDiscover()
            .withUserManagement(Map.of("fake-token", OWNER_ID))
            .withStorages()
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

  private HttpResponse executeDownloadMultiple(List<String> nodeIds) throws Exception {
    String jsonArray = OBJECT_MAPPER.writeValueAsString(nodeIds);
    String requestBody = "nodeIds=" + URLEncoder.encode(jsonArray, StandardCharsets.UTF_8);

    List<Map.Entry<String, String>> headers =
        List.of(Map.entry("Content-Type", "application/x-www-form-urlencoded"));

    HttpRequest httpRequest =
        HttpRequest.of(
            "POST", "/download-multiple", "ZM_AUTH_TOKEN=fake-token", headers, requestBody);

    return app.sendForm(httpRequest);
  }

  @Test
  void givenLocalRootAndAnotherNodeIdThenDownloadMultipleReturns400WithGenericBadRequestBody()
      throws Exception {
    // Given
    String fileId = "00000000-0000-0000-0000-000000000601";
    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                fileId,
                OWNER_ID,
                OWNER_ID,
                Constants.Db.RootId.LOCAL_ROOT,
                "file.txt",
                "",
                NodeType.TEXT,
                Constants.Db.RootId.LOCAL_ROOT,
                10L,
                "text/plain"));

    // When — LOCAL_ROOT is not the only id passed -> AliasNotAloneInDownload
    HttpResponse httpResponse =
        executeDownloadMultiple(List.of(Constants.Db.RootId.LOCAL_ROOT, fileId));

    // Then — ExceptionsHandler maps AliasNotAloneInDownload to a generic 400 body (the actual
    // exception message is discarded, per ExceptionsHandler's BadRequestException-like branch).
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(400);
    Assertions.assertThat(httpResponse.getBodyPayload()).isEqualTo("400 Bad Request");
  }

  @Test
  void givenLocalRootAloneThenDownloadMultipleResolvesToItsChildrenAndReturnsZipWith200()
      throws Exception {
    // Given
    String fileId1 = "00000000-0000-0000-0000-000000000602";
    String fileId2 = "00000000-0000-0000-0000-000000000603";

    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                fileId1,
                OWNER_ID,
                OWNER_ID,
                Constants.Db.RootId.LOCAL_ROOT,
                "file1.txt",
                "",
                NodeType.TEXT,
                Constants.Db.RootId.LOCAL_ROOT,
                10L,
                "text/plain"))
        .addNode(
            new PopulatorNode(
                fileId2,
                OWNER_ID,
                OWNER_ID,
                Constants.Db.RootId.LOCAL_ROOT,
                "file2.txt",
                "",
                NodeType.TEXT,
                Constants.Db.RootId.LOCAL_ROOT,
                20L,
                "text/plain"));

    app.mocks().storagesServesBlob(fileId1, 1);
    app.mocks().storagesServesBlob(fileId2, 1);

    // When — LOCAL_ROOT passed alone resolves to its children (both files).
    HttpResponse httpResponse =
        executeDownloadMultiple(List.of(Constants.Db.RootId.LOCAL_ROOT));

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
    Assertions.assertThat(httpResponse.getHeaders())
        .anyMatch(
            header ->
                header.getKey().equalsIgnoreCase("content-type")
                    && header.getValue().contains("application/zip"));

    app.mocks().verifyStoragesDownloaded(fileId1, 1);
    app.mocks().verifyStoragesDownloaded(fileId2, 1);
  }
}
