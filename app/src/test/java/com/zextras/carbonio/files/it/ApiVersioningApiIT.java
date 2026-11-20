// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import com.zextras.carbonio.files.versioning.ArchiveNodeTypeConverter;
import com.zextras.carbonio.files.versioning.UploadParentIdConverter;
import com.zextras.carbonio.quarkus.extensions.apiversioning.test.ApiVersionBefore;
import com.zextras.carbonio.quarkus.extensions.apiversioning.test.ApiVersionTestContext;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * API versioning of the Files contract: the negotiation (REST and GraphQL) and one test per
 * synthetic change. Unannotated tests are current clients; {@code @ApiVersionBefore} makes the test
 * speak as the oldest supported client, on the call under test only.
 */
class ApiVersioningApiIT extends AbstractFilesIT {

  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String REQUESTER_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String DEBUG = "X-Api-Versioning-Applied";
  private static final byte[] CONTENT = "archive".getBytes(StandardCharsets.UTF_8);

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

  private static String getNodeTypeQuery(String nodeId) {
    return GraphqlCommandBuilder.aQueryBuilder("getNode")
        .withString("node_id", nodeId)
        .withWantedResultFormat("{ id type }")
        .build();
  }

  private static String copyNodesQuery(String nodeId) {
    return GraphqlCommandBuilder.aMutationBuilder("copyNodes")
        .withListOfStrings("node_ids", new String[] {nodeId})
        .withString("destination_id", LOCAL_ROOT)
        .withWantedResultFormat("{ id type }")
        .build();
  }

  private static Response graphqlAs(String version, String query) {
    RequestSpecification request = RestAssured.given();
    if (version != null) {
      request = request.header(versionHeader(), version);
    }
    return rejectUndeclared(
        request
            .contentType("application/json")
            .header("Cookie", REQUESTER_COOKIE)
            .body(TestUtils.queryPayload(query))
            .post("/graphql/"));
  }

  private static Response downloadAs(String version, String nodeId) {
    RequestSpecification request = RestAssured.given().header("Cookie", REQUESTER_COOKIE);
    if (version != null) {
      request = request.header(versionHeader(), version);
    }
    return request.get("/download/" + nodeId);
  }

  // ---------------------------------------------------------------------------------- C1

  @Test
  void currentClientSeesTheArchiveType() {
    String nodeId = seedFile("backup.zip", LOCAL_ROOT, CONTENT, REQUESTER_COOKIE);

    graphql(getNodeTypeQuery(nodeId), REQUESTER_COOKIE)
        .then()
        .statusCode(200)
        .body("data.getNode.type", equalTo("ARCHIVE"))
        .header(versionHeader(), equalTo(current()))
        .header("Vary", containsString(versionHeader()))
        .header(DEBUG, nullValue());
  }

  @Test
  @ApiVersionBefore(ArchiveNodeTypeConverter.class)
  void oldClientSeesArchivesAsOther() {
    String nodeId = seedFile("backup.zip", LOCAL_ROOT, CONTENT, REQUESTER_COOKIE);

    versionedGraphql(getNodeTypeQuery(nodeId), REQUESTER_COOKIE)
        .then()
        .statusCode(200)
        .body("data.getNode.type", equalTo("OTHER"))
        .header(DEBUG, containsString(ArchiveNodeTypeConverter.class.getName()))
        .header(versionHeader(), equalTo(ApiVersionTestContext.version()))
        .header("Vary", containsString(versionHeader()));
  }

  @Test
  @ApiVersionBefore(ArchiveNodeTypeConverter.class)
  void oldClientSeesArchivesAsOtherOnALongBlobOperation() {
    String nodeId = seedFile("backup.tar", LOCAL_ROOT, CONTENT, REQUESTER_COOKIE);

    Response response = versionedGraphql(copyNodesQuery(nodeId), REQUESTER_COOKIE);

    response
        .then()
        .statusCode(200)
        .body("data.copyNodes", hasSize(1))
        .body("data.copyNodes[0].type", equalTo("OTHER"))
        .header(versionHeader(), equalTo(ApiVersionTestContext.version()))
        .header("Vary", containsString(versionHeader()))
        .header(DEBUG, containsString(ArchiveNodeTypeConverter.class.getName()));
  }

  @Test
  void currentClientSeesTheArchiveTypeOnALongBlobOperation() {
    String nodeId = seedFile("backup.tar", LOCAL_ROOT, CONTENT, REQUESTER_COOKIE);

    graphql(copyNodesQuery(nodeId), REQUESTER_COOKIE)
        .then()
        .statusCode(200)
        .body("data.copyNodes[0].type", equalTo("ARCHIVE"))
        .header(versionHeader(), equalTo(current()))
        .header("Vary", containsString(versionHeader()))
        .header(DEBUG, nullValue());
  }

  // ---------------------------------------------------------------------------------- C2

  @Test
  void currentClientMustSendTheParentIdHeader() {
    versionedUploadWithoutParentId(CONTENT, "no-parent.bin", REQUESTER_COOKIE)
        .then()
        .statusCode(400)
        .header(versionHeader(), equalTo(current()))
        .header(DEBUG, nullValue());
  }

  @Test
  @ApiVersionBefore(UploadParentIdConverter.class)
  void oldClientUploadWithoutParentIdLandsInTheRoot() {
    Response response = versionedUploadWithoutParentId(CONTENT, "no-parent.bin", REQUESTER_COOKIE);

    response
        .then()
        .statusCode(200)
        .header(DEBUG, containsString(UploadParentIdConverter.class.getName()))
        .header(versionHeader(), equalTo(ApiVersionTestContext.version()));
    String nodeId = response.jsonPath().getString("nodeId");
    org.assertj.core.api.Assertions.assertThat(childIds(LOCAL_ROOT, REQUESTER_COOKIE))
        .contains(nodeId);
  }

  // ---------------------------------------------------------------------------------- C4

  private static String getConfigsQuery(String... names) {
    if (names.length == 0) {
      return "query { getConfigs { name } }";
    }
    return GraphqlCommandBuilder.aQueryBuilder("getConfigs")
        .withListOfStrings("names", names)
        .withWantedResultFormat("{ name }")
        .build();
  }

  @Test
  void currentClientCanFilterTheConfigsByName() {
    graphql(getConfigsQuery("max-number-of-versions"), REQUESTER_COOKIE)
        .then()
        .statusCode(200)
        .body("data.getConfigs", hasSize(1))
        .body("data.getConfigs[0].name", equalTo("max-number-of-versions"))
        .header(versionHeader(), equalTo(current()))
        .header(DEBUG, nullValue());
  }

  @Test
  void oldClientStillGetsAllTheConfigs() {
    graphqlAs(ApiVersionTestContext.oldestSupported(), getConfigsQuery())
        .then()
        .statusCode(200)
        .body("data.getConfigs", hasSize(4))
        .header(DEBUG, nullValue())
        .header(versionHeader(), equalTo(ApiVersionTestContext.oldestSupported()));
  }

  // --------------------------------------------------------------------------- negotiation

  @Test
  void noHeaderCurrentAndTheExactOwnVersionAllGetTheNewBehaviour() {
    String nodeId = seedFile("backup.zip", LOCAL_ROOT, CONTENT, REQUESTER_COOKIE);
    String own = current();

    for (String sent : new String[] {null, "current", own}) {
      graphqlAs(sent, getNodeTypeQuery(nodeId))
          .then()
          .statusCode(200)
          .body("data.getNode.type", equalTo("ARCHIVE"))
          .header(versionHeader(), equalTo(current()))
          .header("Vary", containsString(versionHeader()))
          .header(DEBUG, nullValue());
    }
  }

  @Test
  @ApiVersionBefore(ArchiveNodeTypeConverter.class)
  void theOldestSupportedVersionIsConvertedAndEchoedOnGraphqlAndRest() {
    String nodeId = seedFile("backup.zip", LOCAL_ROOT, CONTENT, REQUESTER_COOKIE);

    graphqlAs(ApiVersionTestContext.oldestSupported(), getNodeTypeQuery(nodeId))
        .then()
        .statusCode(200)
        .body("data.getNode.type", equalTo("OTHER"))
        .header(versionHeader(), equalTo(ApiVersionTestContext.oldestSupported()))
        .header("Vary", containsString(versionHeader()))
        .header(DEBUG, containsString(ArchiveNodeTypeConverter.class.getName()));
    downloadAs(ApiVersionTestContext.oldestSupported(), nodeId)
        .then()
        .statusCode(200)
        .header(versionHeader(), equalTo(ApiVersionTestContext.oldestSupported()))
        .header("Vary", containsString(versionHeader()));
  }

  @Test
  void aVersionBelowTheOldestSupportedIsRejectedOnGraphqlAndRest() {
    String nodeId = seedFile("backup.zip", LOCAL_ROOT, CONTENT, REQUESTER_COOKIE);

    graphqlAs(ApiVersionTestContext.belowSupported(), getNodeTypeQuery(nodeId))
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
    String nodeId = seedFile("backup.zip", LOCAL_ROOT, CONTENT, REQUESTER_COOKIE);

    graphqlAs("not-a-version", getNodeTypeQuery(nodeId))
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
    String nodeId = seedFile("backup.zip", LOCAL_ROOT, CONTENT, REQUESTER_COOKIE);
    graphqlAs(ApiVersionTestContext.aboveCurrent(), getNodeTypeQuery(nodeId))
        .then()
        .statusCode(200)
        .body("data.getNode.type", equalTo("ARCHIVE"))
        .header(versionHeader(), equalTo(current()))
        .header("Vary", containsString(versionHeader()))
        .header(DEBUG, nullValue());
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
