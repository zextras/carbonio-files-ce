// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.acceptance;

import com.zextras.carbonio.files.FilesStackTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

import com.zextras.carbonio.files.acceptance.seam.FilesTestApp;
import com.zextras.carbonio.files.acceptance.seam.impl.QuarkusFilesTestAppBuilder;
import com.zextras.carbonio.files.api.utilities.entities.PopulatorNode;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
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
 * Gap-closing pass: edge-branch coverage for {@code PreviewController}'s three {@code
 * thumbnailXxx} methods (image/pdf/document) that {@code ThumbnailApiIT} does not exercise (that
 * class covers only happy-path retrieval and version resolution). Each of the three methods shares
 * the same two-branch shape as their {@code previewXxx} siblings:
 *
 * <ul>
 *   <li>{@code tryCheckNode.isSuccess()} — {@code PreviewEdgeApiIT} covers the not-found/
 *       permission-denied direction only via the {@code previewXxx} methods (shared bytecode
 *       validation logic, but a DIFFERENT call site/method in {@code PreviewController}); the
 *       {@code thumbnailXxx} call sites themselves were never exercised with a failing {@code
 *       checkNodePermissionAndExistence}.
 *   <li>{@code isPreviewChanged(...)} — {@code PreviewEdgeApiIT} covers the ETag/304 direction for
 *       {@code previewPdf}/{@code previewDocument}/{@code previewImage}, but never for any of the
 *       three {@code thumbnailXxx} methods.
 * </ul>
 */
@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class ThumbnailEdgeApiIT {

  static FilesTestApp app;

  private static final String OWNER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OTHER_USER_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String OWNER_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String OTHER_USER_COOKIE = "ZM_AUTH_TOKEN=fake-token-other";

  @BeforeAll
  static void init() {
    app =
        QuarkusFilesTestAppBuilder.aFilesTestApp()
            .withDatabase()
            .withServiceDiscover()
            .withPreview()
            .withUserManagement(
                Map.of(
                    "fake-token", OWNER_ID,
                    "fake-token-other", OTHER_USER_ID))
            .build();
  }

  @AfterEach
  void cleanUp() {
    app.backdoor().resetDatabase();
    app.backdoor().clearFileVersionCache();
    app.mocks().reset();
  }

  @AfterAll
  static void cleanUpAll() {
    app.close();
  }

  private static String extractHeader(HttpResponse response, String name) {
    return response.getHeaders().stream()
        .filter(header -> header.getKey().equalsIgnoreCase(name))
        .map(Map.Entry::getValue)
        .findFirst()
        .orElse(null);
  }

  // --- tryCheckNode.isSuccess() == false (permission-denied), one per thumbnail family ----------

  @Test
  void givenARequesterWithoutPermissionTheImageThumbnailApiShouldReturnA404StatusCode() {
    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                "00000000-0000-0000-0000-000000000220",
                OWNER_ID,
                OWNER_ID,
                "LOCAL_ROOT",
                "private.png",
                "",
                NodeType.IMAGE,
                "LOCAL_ROOT",
                10L,
                "image/png"));
    // No share created for OTHER_USER_ID.

    final HttpRequest httpRequest =
        HttpRequest.of(
            "GET",
            "/preview/image/00000000-0000-0000-0000-000000000220/5x5/thumbnail",
            OTHER_USER_COOKIE,
            null);

    final HttpResponse httpResponse = app.send(httpRequest);

    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(404);
  }

  @Test
  void givenARequesterWithoutPermissionThePdfThumbnailApiShouldReturnA404StatusCode() {
    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                "00000000-0000-0000-0000-000000000221",
                OWNER_ID,
                OWNER_ID,
                "LOCAL_ROOT",
                "private.pdf",
                "",
                NodeType.APPLICATION,
                "LOCAL_ROOT",
                10L,
                "application/pdf"));

    final HttpRequest httpRequest =
        HttpRequest.of(
            "GET",
            "/preview/pdf/00000000-0000-0000-0000-000000000221/5x5/thumbnail",
            OTHER_USER_COOKIE,
            null);

    final HttpResponse httpResponse = app.send(httpRequest);

    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(404);
  }

  @Test
  void givenANonExistentNodeTheDocumentThumbnailApiShouldReturnA404StatusCode() {
    final HttpRequest httpRequest =
        HttpRequest.of(
            "GET",
            "/preview/document/00000000-0000-0000-0000-00000000ff01/5x5/thumbnail",
            OWNER_COOKIE,
            null);

    final HttpResponse httpResponse = app.send(httpRequest);

    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(404);
  }

  // --- isPreviewChanged() == false (ETag match -> 304), one per thumbnail family -----------------

  @Test
  void givenAMatchingIfNoneMatchHeaderTheRepeatedImageThumbnailRequestShouldReturnA304StatusCode() {
    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                "00000000-0000-0000-0000-000000000222",
                OWNER_ID,
                OWNER_ID,
                "LOCAL_ROOT",
                "cacheable-thumb.png",
                "",
                NodeType.IMAGE,
                "LOCAL_ROOT",
                10L,
                "image/png"));

    String expectationId =
        app.mocks()
            .previewServes(
                "/preview/image/00000000-0000-0000-0000-000000000222/1/5x5/thumbnail/",
                OWNER_ID,
                "content".getBytes(),
                "image/jpeg");

    final HttpRequest firstRequest =
        HttpRequest.of(
            "GET",
            "/preview/image/00000000-0000-0000-0000-000000000222/5x5/thumbnail",
            OWNER_COOKIE,
            null);
    final HttpResponse firstResponse = app.send(firstRequest);

    Assertions.assertThat(firstResponse.getStatus()).isEqualTo(200);
    String etag = extractHeader(firstResponse, "etag");
    Assertions.assertThat(etag).isNotNull();

    final HttpRequest secondRequest =
        HttpRequest.of(
            "GET",
            "/preview/image/00000000-0000-0000-0000-000000000222/5x5/thumbnail",
            OWNER_COOKIE,
            List.of(Map.entry("If-None-Match", etag)),
            null);
    final HttpResponse secondResponse = app.send(secondRequest);

    Assertions.assertThat(secondResponse.getStatus()).isEqualTo(304);

    app.mocks().verifyPreviewServed(expectationId);
  }

  @Test
  void givenAMatchingIfNoneMatchHeaderTheRepeatedPdfThumbnailRequestShouldReturnA304StatusCode() {
    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                "00000000-0000-0000-0000-000000000223",
                OWNER_ID,
                OWNER_ID,
                "LOCAL_ROOT",
                "cacheable-thumb.pdf",
                "",
                NodeType.APPLICATION,
                "LOCAL_ROOT",
                10L,
                "application/pdf"));

    String expectationId =
        app.mocks()
            .previewServes(
                "/preview/pdf/00000000-0000-0000-0000-000000000223/1/5x5/thumbnail/",
                OWNER_ID,
                "content".getBytes(),
                "image/jpeg");

    final HttpRequest firstRequest =
        HttpRequest.of(
            "GET",
            "/preview/pdf/00000000-0000-0000-0000-000000000223/5x5/thumbnail",
            OWNER_COOKIE,
            null);
    final HttpResponse firstResponse = app.send(firstRequest);

    Assertions.assertThat(firstResponse.getStatus()).isEqualTo(200);
    String etag = extractHeader(firstResponse, "etag");
    Assertions.assertThat(etag).isNotNull();

    final HttpRequest secondRequest =
        HttpRequest.of(
            "GET",
            "/preview/pdf/00000000-0000-0000-0000-000000000223/5x5/thumbnail",
            OWNER_COOKIE,
            List.of(Map.entry("If-None-Match", etag)),
            null);
    final HttpResponse secondResponse = app.send(secondRequest);

    Assertions.assertThat(secondResponse.getStatus()).isEqualTo(304);

    app.mocks().verifyPreviewServed(expectationId);
  }

  @Test
  void givenAMatchingIfNoneMatchHeaderTheRepeatedDocumentThumbnailRequestShouldReturnA304StatusCode() {
    app.backdoor()
        .populator()
        .addNode(
            new PopulatorNode(
                "00000000-0000-0000-0000-000000000224",
                OWNER_ID,
                OWNER_ID,
                "LOCAL_ROOT",
                "cacheable-thumb.xls",
                "",
                NodeType.SPREADSHEET,
                "LOCAL_ROOT",
                7L,
                "application/vnd.ms-excel"));

    String expectationId =
        app.mocks()
            .previewServes(
                "/preview/document/00000000-0000-0000-0000-000000000224/1/5x5/thumbnail/",
                OWNER_ID,
                "0".getBytes(),
                "image/jpeg");

    final HttpRequest firstRequest =
        HttpRequest.of(
            "GET",
            "/preview/document/00000000-0000-0000-0000-000000000224/5x5/thumbnail",
            OWNER_COOKIE,
            null);
    final HttpResponse firstResponse = app.send(firstRequest);

    Assertions.assertThat(firstResponse.getStatus()).isEqualTo(200);
    String etag = extractHeader(firstResponse, "etag");
    Assertions.assertThat(etag).isNotNull();

    final HttpRequest secondRequest =
        HttpRequest.of(
            "GET",
            "/preview/document/00000000-0000-0000-0000-000000000224/5x5/thumbnail",
            OWNER_COOKIE,
            List.of(Map.entry("If-None-Match", etag)),
            null);
    final HttpResponse secondResponse = app.send(secondRequest);

    Assertions.assertThat(secondResponse.getStatus()).isEqualTo(304);

    app.mocks().verifyPreviewServed(expectationId);
  }
}
