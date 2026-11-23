// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import com.zextras.carbonio.quarkus.extensions.apiversioning.test.ApiVersionTestContext;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** API versioning of the Files contract: the negotiation on REST and GraphQL. */
class ApiVersioningApiIT extends AbstractFilesIT {

  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String REQUESTER_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final byte[] CONTENT = "content".getBytes(StandardCharsets.UTF_8);

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", REQUESTER_ID);
  }

  private static String versionHeader() {
    return ApiVersionTestContext.headerName();
  }

  private static String current() {
    return ApiVersionTestContext.current();
  }

  private static String getConfigsQuery() {
    return "query { getConfigs { name } }";
  }

  private static Response graphqlAs(String version, String query) {
    RequestSpecification request = RestAssured.given();
    if (version != null) {
      request = request.header(versionHeader(), version);
    }
    return request
        .contentType("application/json")
        .header("Cookie", REQUESTER_COOKIE)
        .body(TestUtils.queryPayload(query))
        .post("/graphql/");
  }

  private static Response downloadAs(String version, String nodeId) {
    RequestSpecification request = RestAssured.given().header("Cookie", REQUESTER_COOKIE);
    if (version != null) {
      request = request.header(versionHeader(), version);
    }
    return request.get("/download/" + nodeId);
  }

  // --------------------------------------------------------------------------- negotiation

  @Test
  void noHeaderCurrentAndTheExactOwnVersionAllGetTheCurrentVersion() {
    String own = current();

    for (String sent : new String[] {null, "current", own}) {
      graphqlAs(sent, getConfigsQuery())
          .then()
          .statusCode(200)
          .body("data.getConfigs", notNullValue())
          .header(versionHeader(), equalTo(current()))
          .header("Vary", containsString(versionHeader()));
    }
  }

  @Test
  void theOldestSupportedVersionIsEchoedOnGraphqlAndRest() {
    String nodeId = seedFile("file.bin", LOCAL_ROOT, CONTENT, REQUESTER_COOKIE);

    graphqlAs(ApiVersionTestContext.oldestSupported(), getConfigsQuery())
        .then()
        .statusCode(200)
        .body("data.getConfigs", notNullValue())
        .header(versionHeader(), equalTo(ApiVersionTestContext.oldestSupported()))
        .header("Vary", containsString(versionHeader()));
    downloadAs(ApiVersionTestContext.oldestSupported(), nodeId)
        .then()
        .statusCode(200)
        .header(versionHeader(), equalTo(ApiVersionTestContext.oldestSupported()))
        .header("Vary", containsString(versionHeader()));
  }

  @Test
  void aVersionBelowTheOldestSupportedIsRejectedOnGraphqlAndRest() {
    String nodeId = seedFile("file.bin", LOCAL_ROOT, CONTENT, REQUESTER_COOKIE);

    graphqlAs(ApiVersionTestContext.belowSupported(), getConfigsQuery())
        .then()
        .statusCode(400)
        .body("code", equalTo("UNSUPPORTED_API_VERSION"))
        .body("received", equalTo(ApiVersionTestContext.belowSupported()))
        .body("oldestSupported", equalTo(ApiVersionTestContext.oldestSupported()))
        .body("current", equalTo(current()))
        .header("Vary", containsString(versionHeader()));
    downloadAs(ApiVersionTestContext.belowSupported(), nodeId)
        .then()
        .statusCode(400)
        .body("code", equalTo("UNSUPPORTED_API_VERSION"))
        .body("received", equalTo(ApiVersionTestContext.belowSupported()))
        .body("oldestSupported", equalTo(ApiVersionTestContext.oldestSupported()))
        .body("current", equalTo(current()))
        .header("Vary", containsString(versionHeader()));
  }

  @Test
  void aMalformedVersionIsRejectedOnGraphqlAndRest() {
    String nodeId = seedFile("file.bin", LOCAL_ROOT, CONTENT, REQUESTER_COOKIE);

    graphqlAs("not-a-version", getConfigsQuery())
        .then()
        .statusCode(400)
        .body("code", equalTo("INVALID_API_VERSION"))
        .body("received", equalTo("not-a-version"))
        .body("oldestSupported", equalTo(ApiVersionTestContext.oldestSupported()))
        .body("current", equalTo(current()))
        .header("Vary", containsString(versionHeader()));
    downloadAs("not-a-version", nodeId)
        .then()
        .statusCode(400)
        .body("code", equalTo("INVALID_API_VERSION"))
        .body("received", equalTo("not-a-version"))
        .body("oldestSupported", equalTo(ApiVersionTestContext.oldestSupported()))
        .body("current", equalTo(current()))
        .header("Vary", containsString(versionHeader()));
  }

  @Test
  void aVersionAboveTheCurrentOneIsServedAtCurrentOnGraphqlAndRest() {
    String nodeId = seedFile("file.bin", LOCAL_ROOT, CONTENT, REQUESTER_COOKIE);
    graphqlAs(ApiVersionTestContext.aboveCurrent(), getConfigsQuery())
        .then()
        .statusCode(200)
        .body("data.getConfigs", notNullValue())
        .header(versionHeader(), equalTo(current()))
        .header("Vary", containsString(versionHeader()));
    downloadAs(ApiVersionTestContext.aboveCurrent(), nodeId)
        .then()
        .statusCode(200)
        .header(versionHeader(), equalTo(current()))
        .header("Vary", containsString(versionHeader()));
  }

  @Test
  void theVersionsEndpointIsPublic() {
    RestAssured.given()
        .get("/versions")
        .then()
        .statusCode(200)
        .body("name", equalTo("carbonio-files"))
        .body("edition", equalTo("ce"))
        .body("oldestSupported", equalTo(ApiVersionTestContext.oldestSupported()))
        .body("header", equalTo(versionHeader()))
        .body("current", equalTo(current()));
  }
}
