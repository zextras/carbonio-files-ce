// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * On main a folder listing cost 3 user-management calls (the identity lookup plus one batch lookup
 * shared by every user field through USER_BATCH_LOADER). SmallRye has one batch loader per
 * type/field, so without the request-scoped user cache the same listing cost 8.
 */
class UserLookupCountApiIT extends AbstractFilesIT {

  private static final int MAIN_USER_MANAGEMENT_CALLS = 3;

  private static final String A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String C = "cccccccc-cccc-cccc-cccc-cccccccccccc";
  private static final String A_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String B_COOKIE = "ZM_AUTH_TOKEN=fake-token-b";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", A);
    FilesStackTestResource.getUserManagementService().registerToken("fake-token-b", B);
    FilesStackTestResource.getUserManagementService().registerToken("fake-token-c", C);
  }

  private static String userManagementUrl() {
    return "http://localhost:" + FilesStackTestResource.getUserManagementService().getPort();
  }

  private static int requestCount(String matcherJson) throws Exception {
    String body =
        RestAssured.given()
            .contentType("application/json")
            .body(matcherJson)
            .post(userManagementUrl() + "/__admin/requests/count")
            .getBody()
            .asString();
    return new ObjectMapper().readTree(body).get("count").asInt();
  }

  @Test
  void givenAFolderListingWithEveryUserFieldTheUserManagementCallsStayAtMainsLevel()
      throws Exception {
    String folderId = seedFolder("UMCOUNT", LOCAL_ROOT, A_COOKIE);
    seedShare(folderId, B, ACL.SharePermission.READ_AND_WRITE, A_COOKIE);
    seedShare(folderId, C, ACL.SharePermission.READ_ONLY, A_COOKIE);
    for (int i = 0; i < 8; i++) {
      seedFile("a" + i + ".txt", folderId, ("a" + i).getBytes(StandardCharsets.UTF_8), A_COOKIE);
    }
    for (int i = 0; i < 5; i++) {
      seedFile("b" + i + ".txt", folderId, ("b" + i).getBytes(StandardCharsets.UTF_8), B_COOKIE);
    }
    for (int i = 0; i < 12; i++) {
      seedFolder("d" + i, folderId, i % 2 == 0 ? A_COOKIE : B_COOKIE);
    }
    String query =
        "query { getNode(node_id: \\\""
            + folderId
            + "\\\") { id owner { id } creator { id } last_editor { id }"
            + " shares(limit: 6) { share_target { ... on User { id } } }"
            + " ... on Folder { children(limit: 25, sort: NAME_ASC) { nodes { id owner { id }"
            + " creator { id } last_editor { id } shares(limit: 6) { share_target { ... on User {"
            + " id } } } } } } } }";
    graphql(query, A_COOKIE);
    RestAssured.given().delete(userManagementUrl() + "/__admin/requests");

    Response response = graphql(query, A_COOKIE);

    JsonNode body = new ObjectMapper().readTree(response.getBody().asString());
    Assertions.assertThat(body.path("errors").isMissingNode()).isTrue();
    JsonNode node = body.path("data").path("getNode");
    Assertions.assertThat(node.path("owner").path("id").asText()).isEqualTo(A);
    Assertions.assertThat(node.path("shares")).hasSize(2);
    Assertions.assertThat(node.path("children").path("nodes")).hasSize(25);
    for (JsonNode child : node.path("children").path("nodes")) {
      Assertions.assertThat(child.path("owner").path("id").asText()).isEqualTo(A);
      Assertions.assertThat(child.path("creator").path("id").asText()).isIn(A, B);
    }
    Assertions.assertThat(requestCount("{\"method\":\"ANY\",\"urlPathPattern\":\"/.*\"}"))
        .isLessThanOrEqualTo(MAIN_USER_MANAGEMENT_CALLS);
  }
}
