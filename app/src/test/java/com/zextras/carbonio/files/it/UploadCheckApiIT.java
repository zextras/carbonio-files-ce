// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.RestAssured;
import io.restassured.specification.RequestSpecification;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** {@code POST /upload/check}: the pre-flight of an upload, without the bytes. */
class UploadCheckApiIT extends AbstractFilesIT {

  private static final String MAX_UPLOADABLE_SIZE_IN_MB = "max-uploadable-size-in-mb";
  private static final String OWNER_ID = "cccccccc-0000-0000-0000-000000000002";
  private static final String OTHER_ID = "cccccccc-0000-0000-0000-000000000003";
  private static final String OWNER_COOKIE = "ZM_AUTH_TOKEN=upload-check-owner";
  private static final String OTHER_COOKIE = "ZM_AUTH_TOKEN=upload-check-other";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService()
        .registerToken("upload-check-owner", OWNER_ID);
    FilesStackTestResource.getUserManagementService()
        .registerToken("upload-check-other", OTHER_ID);
  }

  @AfterEach
  void restoreUncappedUploads() {
    clearApplicationConfig(MAX_UPLOADABLE_SIZE_IN_MB, null);
  }

  private static RequestSpecification check(String cookie, String destinationId, long size) {
    return RestAssured.given()
        .header("Cookie", cookie)
        .header(
            "Filename",
            Base64.getEncoder().encodeToString("report.pdf".getBytes(StandardCharsets.UTF_8)))
        .header("DestinationId", destinationId)
        .header("FileSize", String.valueOf(size));
  }

  @Test
  void anUploadIntoAWritableFolderWouldBeAccepted() {
    String folder = seedFolder("check-writable", LOCAL_ROOT, OWNER_COOKIE);

    check(OWNER_COOKIE, folder, 1024).post("/upload/check").then().statusCode(204);
  }

  @Test
  void anUploadIntoAFolderOfAnotherUserIsNotFound() {
    String folder = seedFolder("check-foreign", LOCAL_ROOT, OTHER_COOKIE);

    check(OWNER_COOKIE, folder, 1024).post("/upload/check").then().statusCode(404);
  }

  @Test
  void anUploadOverTheSizeCapIsTooLarge() {
    setApplicationConfig(MAX_UPLOADABLE_SIZE_IN_MB, "1");

    check(OWNER_COOKIE, LOCAL_ROOT, 2L * 1024 * 1024).post("/upload/check").then().statusCode(413);
  }

  @Test
  void aCheckWithoutFileSizeIsRejected() {
    RestAssured.given()
        .header("Cookie", OWNER_COOKIE)
        .header(
            "Filename", Base64.getEncoder().encodeToString("a.txt".getBytes(StandardCharsets.UTF_8)))
        .header("DestinationId", LOCAL_ROOT)
        .post("/upload/check")
        .then()
        .statusCode(400);
  }

  @Test
  void aCheckWithoutDestinationIdIsRejected() {
    RestAssured.given()
        .header("Cookie", OWNER_COOKIE)
        .header(
            "Filename", Base64.getEncoder().encodeToString("a.txt".getBytes(StandardCharsets.UTF_8)))
        .header("FileSize", "10")
        .post("/upload/check")
        .then()
        .statusCode(400);
  }
}
