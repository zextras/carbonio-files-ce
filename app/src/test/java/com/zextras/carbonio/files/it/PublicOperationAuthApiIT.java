// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.response.Response;
import java.util.stream.Stream;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Public operations do not authenticate: the {@code ZM_AUTH_TOKEN} cookie is never resolved for a
 * document that selects only public root fields, whatever the client sends. Every other document
 * with a cookie is still authenticated before execution, with the legacy 401/403 bodies.
 */
class PublicOperationAuthApiIT extends AbstractFilesIT {

  private static final String OWNER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1";
  private static final String OWNER_COOKIE = "ZM_AUTH_TOKEN=public-op-owner-token";
  private static final String UNRESOLVABLE_401 =
      "Failed to authenticate request /graphql/: Unable to find requested user";

  private static String folderId;
  private static String publicId;

  @BeforeAll
  static void registerUsers() {
    var userManagement = FilesStackTestResource.getUserManagementService();
    userManagement.registerToken("public-op-owner-token", OWNER_ID);
    userManagement.registerToken(
        "public-op-maintenance-token",
        "cccccccc-cccc-cccc-cccc-ccccccccccc1",
        "maintenance",
        false,
        true);
    userManagement.registerToken(
        "public-op-guest-token", "cccccccc-cccc-cccc-cccc-ccccccccccc2", "active", true, true);
    userManagement.registerToken(
        "public-op-flag-off-token", "cccccccc-cccc-cccc-cccc-ccccccccccc3", "active", false, false);
  }

  @BeforeEach
  void seedPublicFolder() {
    folderId = seedFolder("public-op-folder", LOCAL_ROOT, OWNER_COOKIE);
    String createLink =
        GraphqlCommandBuilder.aMutationBuilder("createLink")
            .withString("node_id", folderId)
            .withWantedResultFormat("{ url }")
            .build();
    String url =
        (String)
            TestUtils.jsonResponseToMap(
                    graphql(createLink, OWNER_COOKIE).getBody().asString(), "createLink")
                .get("url");
    publicId = url.substring(url.length() - 50);
  }

  static Stream<Arguments> anyCookie() {
    return Stream.of(
        Arguments.of("unresolvable token", "ZM_AUTH_TOKEN=public-op-unknown-token"),
        Arguments.of("inactive user", "ZM_AUTH_TOKEN=public-op-maintenance-token"),
        Arguments.of("guest user", "ZM_AUTH_TOKEN=public-op-guest-token"),
        Arguments.of("Files disabled", "ZM_AUTH_TOKEN=public-op-flag-off-token"),
        Arguments.of("valid owner token", OWNER_COOKIE));
  }

  private static String getPublicNode() {
    return "query { getPublicNode(node_link_id: \\\"" + publicId + "\\\") { id } }";
  }

  private static void assertServesThePublicFolder(Response response) {
    String body = response.getBody().asString();
    Assertions.assertThat(response.getStatusCode()).as(body).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToMap(body, "getPublicNode").get("id"))
        .as(body)
        .isEqualTo(folderId);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("anyCookie")
  void givenAnyCookieThenGetPublicNodeIgnoresIt(String scenario, String cookie) {
    assertServesThePublicFolder(graphql(getPublicNode(), cookie));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("anyCookie")
  void givenAnyCookieThenFindPublicNodesIgnoresIt(String scenario, String cookie) {
    String query =
        "query { findPublicNodes(folder_id: \\\""
            + folderId
            + "\\\", node_link_id: \\\""
            + publicId
            + "\\\", limit: 10) { nodes { id } } }";

    Response response = graphql(query, cookie);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(response.getBody().asString()).doesNotContain("\"errors\"");
  }

  @Test
  void givenUserManagementDownThenPublicOperationsStillWorkAndProtectedOnesAreRejected() {
    var userManagement = FilesStackTestResource.getUserManagementService();
    userManagement.setDown(true);
    try {
      assertServesThePublicFolder(graphql(getPublicNode(), OWNER_COOKIE));

      Response protectedResponse = graphql("query { getRootsList { id } }", OWNER_COOKIE);
      Assertions.assertThat(protectedResponse.getStatusCode()).isEqualTo(401);
      Assertions.assertThat(protectedResponse.getBody().asString()).isEqualTo(UNRESOLVABLE_401);
    } finally {
      userManagement.setDown(false);
    }
  }

  @Test
  void givenPublicFieldsThroughFragmentsThenTheCookieIsIgnored() {
    String query =
        "query { ...P } fragment P on Query { ... on Query { getPublicNode(node_link_id: \\\""
            + publicId
            + "\\\") { id } } }";

    assertServesThePublicFolder(graphql(query, "ZM_AUTH_TOKEN=public-op-unknown-token"));
  }

  @Test
  void givenSeveralOperationsThenTheSelectedOneDecides() {
    String document =
        "query pub { getPublicNode(node_link_id: \\\""
            + publicId
            + "\\\") { id } } query priv { getRootsList { id } }";
    String publicRequest = "{\"query\":\"" + document + "\",\"operationName\":\"pub\"}";
    String protectedRequest = "{\"query\":\"" + document + "\",\"operationName\":\"priv\"}";

    assertServesThePublicFolder(rawGraphql(publicRequest, "ZM_AUTH_TOKEN=public-op-unknown-token"));
    Response response = rawGraphql(protectedRequest, "ZM_AUTH_TOKEN=public-op-unknown-token");
    Assertions.assertThat(response.getStatusCode()).isEqualTo(401);
    Assertions.assertThat(response.getBody().asString()).isEqualTo(UNRESOLVABLE_401);
  }

  @Test
  void givenAPublicAndAProtectedRootFieldThenTheRequestIsAuthenticated() {
    String query =
        "query { getPublicNode(node_link_id: \\\""
            + publicId
            + "\\\") { id } getRootsList { id } }";

    Response response = graphql(query, "ZM_AUTH_TOKEN=public-op-unknown-token");

    Assertions.assertThat(response.getStatusCode()).isEqualTo(401);
    Assertions.assertThat(response.getBody().asString()).isEqualTo(UNRESOLVABLE_401);
  }

  @Test
  void givenAnUnparseableDocumentWithACookieThenTheRequestIsAuthenticated() {
    Response response = graphql("query { getPublicNode(", "ZM_AUTH_TOKEN=public-op-unknown-token");

    Assertions.assertThat(response.getStatusCode()).isEqualTo(401);
    Assertions.assertThat(response.getBody().asString()).isEqualTo(UNRESOLVABLE_401);
  }

  @Test
  void givenAProtectedOperationWithAForbiddenUserThenTheRequestIsRejectedWith403() {
    Response response =
        graphql("query { getRootsList { id } }", "ZM_AUTH_TOKEN=public-op-guest-token");

    Assertions.assertThat(response.getStatusCode()).isEqualTo(403);
    Assertions.assertThat(response.getBody().asString())
        .isEqualTo("Failed to authorize request /graphql/: User is not internal");
  }

  private static Response rawGraphql(String body, String cookie) {
    return io.restassured.RestAssured.given()
        .contentType("application/json")
        .header("Cookie", cookie)
        .body(body)
        .post("/graphql/");
  }
}
