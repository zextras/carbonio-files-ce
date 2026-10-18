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

  static Stream<Arguments> rejectedCookies() {
    return Stream.of(
        Arguments.of("ZM_AUTH_TOKEN=public-op-unknown-token", 401, UNRESOLVABLE_401),
        Arguments.of(
            "ZM_AUTH_TOKEN=public-op-maintenance-token",
            403,
            "Failed to authorize request /graphql/: User is not active"),
        Arguments.of(
            "ZM_AUTH_TOKEN=public-op-guest-token",
            403,
            "Failed to authorize request /graphql/: User is not internal"),
        Arguments.of(
            "ZM_AUTH_TOKEN=public-op-flag-off-token",
            403,
            "Failed to authorize request /graphql/: Files feature is not enabled for user"));
  }

  private static String getPublicNode() {
    return "query { getPublicNode(node_link_id: \\\"" + publicId + "\\\") { id } }";
  }

  private static String findPublicNodes() {
    return "query { findPublicNodes(folder_id: \\\""
        + folderId
        + "\\\", node_link_id: \\\""
        + publicId
        + "\\\", limit: 10) { nodes { id } } }";
  }

  private static void assertServesThePublicFolder(Response response) {
    String body = response.getBody().asString();
    Assertions.assertThat(response.getStatusCode()).as(body).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToMap(body, "getPublicNode").get("id"))
        .as(body)
        .isEqualTo(folderId);
  }

  @Test
  void givenNoCookieThenGetPublicNodeServesThePublicFolder() {
    assertServesThePublicFolder(graphql(getPublicNode(), null));
  }

  @Test
  void givenNoCookieThenFindPublicNodesServesTheFolderContent() {
    Response response = graphql(findPublicNodes(), null);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(response.getBody().asString()).doesNotContain("\"errors\"");
  }

  @Test
  void givenAValidCookieThenPublicOperationsServeThePublicFolder() {
    assertServesThePublicFolder(graphql(getPublicNode(), OWNER_COOKIE));

    Response response = graphql(findPublicNodes(), OWNER_COOKIE);
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(response.getBody().asString()).doesNotContain("\"errors\"");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("rejectedCookies")
  void givenARejectedCookieThenPublicAndProtectedOperationsAreRejectedAlike(
      String cookie, int status, String message) {
    for (String query :
        new String[] {getPublicNode(), findPublicNodes(), "query { getRootsList { id } }"}) {
      Response response = graphql(query, cookie);

      Assertions.assertThat(response.getStatusCode()).as(query).isEqualTo(status);
      Assertions.assertThat(response.getBody().asString()).as(query).isEqualTo(message);
    }
  }

  @Test
  void givenUserManagementDownThenPublicOperationsWithoutCookieStillWork() {
    var userManagement = FilesStackTestResource.getUserManagementService();
    userManagement.setDown(true);
    try {
      assertServesThePublicFolder(graphql(getPublicNode(), null));

      Response withCookie = graphql(getPublicNode(), OWNER_COOKIE);
      Assertions.assertThat(withCookie.getStatusCode()).isEqualTo(401);
      Assertions.assertThat(withCookie.getBody().asString()).isEqualTo(UNRESOLVABLE_401);
    } finally {
      userManagement.setDown(false);
    }
  }
}
