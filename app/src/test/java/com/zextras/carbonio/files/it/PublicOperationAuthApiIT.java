// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.response.Response;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A public operation sent without a cookie is served even while user management is down. A {@code
 * ZM_AUTH_TOKEN} cookie that is sent is still resolved: an unresolvable one is rejected with the
 * legacy 401 body, for public operations as well.
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
    FilesStackTestResource.getUserManagementService()
        .registerToken("public-op-owner-token", OWNER_ID);
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
  void givenAnUnresolvableCookieThenPublicOperationsAreRejectedWith401() {
    for (String query : new String[] {getPublicNode(), findPublicNodes()}) {
      Response response = graphql(query, "ZM_AUTH_TOKEN=public-op-unknown-token");

      Assertions.assertThat(response.getStatusCode()).as(query).isEqualTo(401);
      Assertions.assertThat(response.getBody().asString()).as(query).isEqualTo(UNRESOLVABLE_401);
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
