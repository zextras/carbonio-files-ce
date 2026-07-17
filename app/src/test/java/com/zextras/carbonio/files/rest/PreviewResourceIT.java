// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end integration test for the P5b preview passthrough: {@code
 * com.zextras.carbonio.files.rest.resources.PreviewResource} + {@code PreviewService} against a
 * WireMock-stubbed carbonio-preview (see {@code
 * FilesStackTestResource#setupPreviewAndMailboxStubs}, which serves a canned PNG for any {@code
 * /preview/*} GET). Real Postgres via {@link FilesStackTestResource}, no real carbonio-preview.
 *
 * <p>Status-code assertions intentionally match the legacy Netty {@code PreviewController}
 * semantics (see {@code PreviewResource} javadoc): node-not-found and no-permission both collapse
 * to 404; an unsupported mimetype for the requested preview type is 400.
 */
@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class PreviewResourceIT {

  /** Matches the fallback bytes served by {@code FilesStackTestResource}'s {@code /preview/*} stub. */
  private static final byte[] FALLBACK_PNG_BYTES = {
    (byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a
  };

  @Inject NodeRepository nodeRepository;
  @Inject FileVersionRepository fileVersionRepository;

  private String imageNodeId;
  private String otherOwnerNodeId;

  @BeforeEach
  void seed() {
    // A file OWNED by the stubbed test user: previewable.
    imageNodeId = UUID.randomUUID().toString();
    nodeRepository.createNewNode(
        imageNodeId,
        FilesStackTestResource.TEST_USER_ID,
        FilesStackTestResource.TEST_USER_ID,
        "LOCAL_ROOT",
        "photo-" + imageNodeId + ".png",
        "desc",
        NodeType.IMAGE,
        "LOCAL_ROOT",
        (long) FALLBACK_PNG_BYTES.length);
    fileVersionRepository.createNewFileVersion(
        imageNodeId,
        FilesStackTestResource.TEST_USER_ID,
        1,
        "image/png",
        FALLBACK_PNG_BYTES.length,
        "digest-" + imageNodeId,
        false);

    // A file owned by ANOTHER user and NOT shared: the test user has no permission on it.
    otherOwnerNodeId = UUID.randomUUID().toString();
    nodeRepository.createNewNode(
        otherOwnerNodeId,
        "other-owner",
        "other-owner",
        "LOCAL_ROOT",
        "secret-" + otherOwnerNodeId + ".png",
        "desc",
        NodeType.IMAGE,
        "LOCAL_ROOT",
        (long) FALLBACK_PNG_BYTES.length);
    fileVersionRepository.createNewFileVersion(
        otherOwnerNodeId, "other-owner", 1, "image/png", FALLBACK_PNG_BYTES.length, "digest-other", false);
  }

  @Test
  void previewImageStreamsBytes() {
    byte[] body =
        given()
            .cookie("ZM_AUTH_TOKEN", FilesStackTestResource.AUTH_TOKEN)
            .when()
            .get("/preview/image/" + imageNodeId + "/50x50")
            .then()
            .statusCode(200)
            .header("Content-Type", "image/png")
            .extract()
            .asByteArray();
    assertThat(body).isEqualTo(FALLBACK_PNG_BYTES);
  }

  @Test
  void thumbnailImageStreamsBytes() {
    given()
        .cookie("ZM_AUTH_TOKEN", FilesStackTestResource.AUTH_TOKEN)
        .when()
        .get("/preview/image/" + imageNodeId + "/50x50/thumbnail")
        .then()
        .statusCode(200)
        .header("Content-Type", "image/png");
  }

  @Test
  void previewOfNonPermittedNodeReturns404() {
    given()
        .cookie("ZM_AUTH_TOKEN", FilesStackTestResource.AUTH_TOKEN)
        .when()
        .get("/preview/image/" + otherOwnerNodeId + "/50x50")
        .then()
        .statusCode(404);
  }

  @Test
  void previewOfMissingNodeReturns404() {
    given()
        .cookie("ZM_AUTH_TOKEN", FilesStackTestResource.AUTH_TOKEN)
        .when()
        .get("/preview/image/" + UUID.randomUUID() + "/50x50")
        .then()
        .statusCode(404);
  }

  @Test
  void previewWithoutCookieReturns401() {
    given().when().get("/preview/image/" + imageNodeId + "/50x50").then().statusCode(401);
  }

  @Test
  void previewPdfOfImageNodeReturnsBadRequestForMimetypeMismatch() {
    // The node's stored file version is image/png; the pdf endpoint only allows application/pdf.
    given()
        .cookie("ZM_AUTH_TOKEN", FilesStackTestResource.AUTH_TOKEN)
        .when()
        .get("/preview/pdf/" + imageNodeId)
        .then()
        .statusCode(400);
  }
}
