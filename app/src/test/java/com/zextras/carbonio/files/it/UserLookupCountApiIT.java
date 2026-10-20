// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.RestAssured;
import java.nio.charset.StandardCharsets;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UserLookupCountApiIT extends AbstractFilesIT {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final String A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String A_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String B_COOKIE = "ZM_AUTH_TOKEN=fake-token-b";

  private static final String PERMISSIONS =
      "fragment Permissions on Node { permissions { can_read can_write_file can_write_folder"
          + " can_delete can_add_version can_read_link can_change_link can_share can_read_share"
          + " can_change_share } }";
  private static final String BASE_NODE =
      "fragment BaseNode on Node { id name type ...Permissions ... on File { size mime_type"
          + " extension version } flagged rootId }";
  private static final String SHARE =
      "fragment Share on Share { permission share_target { ... on User { email full_name id }"
          + " ... on DistributionList { id name } } created_at }";
  private static final String CHILD =
      "fragment Child on Node { ...BaseNode owner { id full_name email } updated_at last_editor {"
          + " id full_name email } shares(limit: $shares_limit) { ...Share } }";
  private static final String PARENT =
      "fragment Parent on Node { id name type owner { id full_name email } rootId ...Permissions }";

  private static final String GET_CHILDREN =
      "query getChildren($node_id: ID!, $children_limit: Int!, $page_token: String, $sort:"
          + " NodeSort!, $shares_limit: Int = 1) { getNode(node_id: $node_id) { ...Parent ... on"
          + " Folder { children(limit: $children_limit, page_token: $page_token, sort: $sort) {"
          + " nodes { ...Child } page_token } } } } "
          + String.join(" ", CHILD, BASE_NODE, PERMISSIONS, SHARE, PARENT);

  private String folderId;

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", A);
    FilesStackTestResource.getUserManagementService().registerToken("fake-token-b", B);
  }

  @BeforeEach
  void seed() {
    folderId = seedFolder("UMCOUNT", LOCAL_ROOT, A_COOKIE);
    seedShare(folderId, B, ACL.SharePermission.READ_AND_WRITE, A_COOKIE);
    for (int i = 0; i < 6; i++) {
      seedFile("a" + i + ".txt", folderId, ("a" + i).getBytes(StandardCharsets.UTF_8), A_COOKIE);
    }
    for (int i = 0; i < 4; i++) {
      seedFile("b" + i + ".txt", folderId, ("b" + i).getBytes(StandardCharsets.UTF_8), B_COOKIE);
    }
    for (int i = 0; i < 6; i++) {
      seedFolder("d" + i, folderId, i % 2 == 0 ? A_COOKIE : B_COOKIE);
    }
  }

  private static String userManagementUrl() {
    return "http://localhost:" + FilesStackTestResource.getUserManagementService().getPort();
  }

  private static int batchUserLookups() throws Exception {
    String body =
        RestAssured.given()
            .contentType("application/json")
            .body("{\"method\":\"POST\",\"urlPath\":\"/internal/users\"}")
            .post(userManagementUrl() + "/__admin/requests/count")
            .getBody()
            .asString();
    return MAPPER.readTree(body).get("count").asInt();
  }

  private static JsonNode run(String query, ObjectNode variables, String cookie, int expectedCalls)
      throws Exception {
    RestAssured.given().delete(userManagementUrl() + "/__admin/requests");
    ObjectNode payload = MAPPER.createObjectNode().put("query", query);
    payload.set("variables", variables);
    String raw =
        RestAssured.given()
            .contentType("application/json")
            .header("Cookie", cookie)
            .body(MAPPER.writeValueAsString(payload))
            .post("/graphql")
            .getBody()
            .asString();
    JsonNode body = MAPPER.readTree(raw);
    Assertions.assertThat(body.path("errors").isMissingNode()).as(raw).isTrue();
    Assertions.assertThat(batchUserLookups())
        .as("POST /internal/users calls")
        .isEqualTo(expectedCalls);
    return body.path("data");
  }

  private ObjectNode vars() {
    return MAPPER.createObjectNode();
  }

  private static void assertUser(JsonNode user, String id) {
    Assertions.assertThat(user.path("id").asText()).isEqualTo(id);
    Assertions.assertThat(user.path("email").asText()).isEqualTo("fake-email@example.com");
    Assertions.assertThat(user.path("full_name").asText()).isEqualTo("Fake User");
  }

  @Test
  void getChildrenResolvesOwnerAndLastEditorWithOneLookup() throws Exception {
    JsonNode data =
        run(
            GET_CHILDREN,
            vars()
                .put("node_id", folderId)
                .put("children_limit", 50)
                .put("sort", "NAME_ASC")
                .put("shares_limit", 1),
            A_COOKIE,
            1);

    JsonNode node = data.path("getNode");
    assertUser(node.path("owner"), A);
    JsonNode children = node.path("children").path("nodes");
    Assertions.assertThat(children).hasSize(16);
    for (JsonNode child : children) {
      String name = child.path("name").asText();
      assertUser(child.path("owner"), A);
      if (name.startsWith("d")) {
        Assertions.assertThat(child.path("last_editor").isNull()).isTrue();
      } else {
        assertUser(child.path("last_editor"), name.startsWith("b") ? B : A);
      }
    }
    Assertions.assertThat(children.get(0).path("shares")).hasSize(1);
    assertUser(children.get(0).path("shares").get(0).path("share_target"), B);
  }
}
