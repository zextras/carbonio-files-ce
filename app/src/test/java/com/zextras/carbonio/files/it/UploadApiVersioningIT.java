// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import com.zextras.carbonio.files.versioning.UploadDestinationIdConverter;
import com.zextras.carbonio.files.versioning.UploadResponseConverter;
import com.zextras.carbonio.quarkus.extensions.apiversioning.test.ApiVersionBefore;
import com.zextras.carbonio.quarkus.extensions.apiversioning.test.ApiVersionTestContext;
import io.restassured.RestAssured;
import io.restassured.specification.RequestSpecification;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** {@code POST /upload}: {@code DestinationId} required, {@code {node_id, version}} answered. */
class UploadApiVersioningIT extends AbstractFilesIT {

  private static final String REQUESTER_ID = "cccccccc-0000-0000-0000-000000000001";
  private static final String COOKIE = "ZM_AUTH_TOKEN=upload-versioning-token";
  private static final byte[] CONTENT = "content".getBytes(StandardCharsets.UTF_8);

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService()
        .registerToken("upload-versioning-token", REQUESTER_ID);
  }

  private static String base64(String value) {
    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private static RequestSpecification uploadAs(String version, String filename) {
    RequestSpecification request =
        RestAssured.given().header("Cookie", COOKIE).header("Filename", base64(filename));
    return version == null ? request : request.header(ApiVersionTestContext.headerName(), version);
  }

  @Test
  void aCurrentClientMustSendDestinationId() {
    uploadAs(null, "no-destination.txt").body(CONTENT).post("/upload").then().statusCode(400);
  }

  @Test
  void aCurrentClientGetsNodeIdAndVersionOne() {
    String folder = seedFolder("current-destination", LOCAL_ROOT, COOKIE);

    String nodeId =
        uploadAs(null, "current.txt")
            .header("DestinationId", folder)
            .body(CONTENT)
            .post("/upload")
            .then()
            .statusCode(200)
            .body("node_id", notNullValue())
            .body("version", equalTo(1))
            .body("$", not(hasKey("nodeId")))
            .extract()
            .path("node_id");

    Assertions.assertThat(childIds(folder, COOKIE)).containsExactly(nodeId);
  }

  @Test
  void aClientWithoutTheVersionHeaderIsServedAsCurrentSoParentIdIsNotEnough() {
    uploadAs(null, "headerless.txt")
        .header("ParentId", LOCAL_ROOT)
        .body(CONTENT)
        .post("/upload")
        .then()
        .statusCode(400);
  }

  @Test
  @ApiVersionBefore(UploadDestinationIdConverter.class)
  void anOldClientUploadsIntoItsParentId() {
    String folder = seedFolder("old-destination", LOCAL_ROOT, COOKIE);

    String nodeId =
        uploadAs(ApiVersionTestContext.version(), "old.txt")
            .header("ParentId", folder)
            .body(CONTENT)
            .post("/upload")
            .then()
            .statusCode(200)
            .extract()
            .path("nodeId");

    Assertions.assertThat(childIds(folder, COOKIE)).containsExactly(nodeId);
  }

  @Test
  @ApiVersionBefore(UploadDestinationIdConverter.class)
  void anOldClientWithoutParentIdUploadsIntoTheAccountRoot() {
    String nodeId =
        uploadAs(ApiVersionTestContext.version(), "old-root.txt")
            .body(CONTENT)
            .post("/upload")
            .then()
            .statusCode(200)
            .extract()
            .path("nodeId");

    Assertions.assertThat(childIds(LOCAL_ROOT, COOKIE)).contains(nodeId);
  }

  @Test
  @ApiVersionBefore(UploadResponseConverter.class)
  void anOldClientReadsNodeIdWithoutTheVersion() {
    uploadAs(ApiVersionTestContext.version(), "old-shape.txt")
        .header("ParentId", LOCAL_ROOT)
        .body(CONTENT)
        .post("/upload")
        .then()
        .statusCode(200)
        .body("nodeId", notNullValue())
        .body("$", not(hasKey("node_id")))
        .body("$", not(hasKey("version")));
  }
}
