// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import com.zextras.carbonio.files.versioning.UploadVersionNumberConverter;
import com.zextras.carbonio.quarkus.extensions.apiversioning.test.ApiVersionBefore;
import com.zextras.carbonio.quarkus.extensions.apiversioning.test.ApiVersionTestContext;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** {@code POST /upload-version} answers {@code version} also when it is 1. */
class UploadVersionApiVersioningIT extends AbstractFilesIT {

  private static final String REQUESTER_ID = "cccccccc-0000-0000-0000-000000000004";
  private static final String COOKIE = "ZM_AUTH_TOKEN=upload-version-versioning-token";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService()
        .registerToken("upload-version-versioning-token", REQUESTER_ID);
  }

  private static Response overwriteFirstVersionAs(String version, String nodeId) {
    RequestSpecification request =
        RestAssured.given()
            .header("Cookie", COOKIE)
            .header("NodeId", nodeId)
            .header(
                "Filename",
                Base64.getEncoder().encodeToString("first.txt".getBytes(StandardCharsets.UTF_8)))
            .header("OverwriteVersion", "true");
    if (version != null) {
      request = request.header(ApiVersionTestContext.headerName(), version);
    }
    return request.body("overwritten".getBytes(StandardCharsets.UTF_8)).post("/upload-version");
  }

  @Test
  void aCurrentClientOverwritingTheFirstVersionGetsVersionOne() {
    String nodeId =
        seedFile("first.txt", LOCAL_ROOT, "v1".getBytes(StandardCharsets.UTF_8), COOKIE);

    overwriteFirstVersionAs(null, nodeId)
        .then()
        .statusCode(200)
        .body("nodeId", equalTo(nodeId))
        .body("version", equalTo(1));
  }

  @Test
  @ApiVersionBefore(UploadVersionNumberConverter.class)
  void anOldClientOverwritingTheFirstVersionGetsNoVersion() {
    String nodeId =
        seedFile("first.txt", LOCAL_ROOT, "v1".getBytes(StandardCharsets.UTF_8), COOKIE);

    overwriteFirstVersionAs(ApiVersionTestContext.version(), nodeId)
        .then()
        .statusCode(200)
        .body("nodeId", equalTo(nodeId))
        .body("$", not(hasKey("version")));
  }
}
