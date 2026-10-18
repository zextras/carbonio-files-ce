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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UserLookupCountApiIT extends AbstractFilesIT {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final String A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
  private static final String C = "cccccccc-cccc-cccc-cccc-cccccccccccc";
  private static final String A_COOKIE = "ZM_AUTH_TOKEN=fake-token";
  private static final String B_COOKIE = "ZM_AUTH_TOKEN=fake-token-b";
  private static final String C_COOKIE = "ZM_AUTH_TOKEN=fake-token-c";

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
  private static final String NODE_PARENT = "fragment NodeParent on Node { parent { ...Parent } }";
  private static final String CHILD_WITH_PARENT =
      "fragment ChildWithParent on Node { ...Child ...NodeParent }";

  private static final String GET_CHILDREN =
      "query getChildren($node_id: ID!, $children_limit: Int!, $page_token: String, $sort:"
          + " NodeSort!, $shares_limit: Int = 1) { getNode(node_id: $node_id) { ...Parent ... on"
          + " Folder { children(limit: $children_limit, page_token: $page_token, sort: $sort) {"
          + " nodes { ...Child } page_token } } } } "
          + String.join(" ", CHILD, BASE_NODE, PERMISSIONS, SHARE, PARENT);

  private static final String GET_NODE =
      "query getNode($node_id: ID!, $children_limit: Int!, $page_token: String, $sort: NodeSort!,"
          + " $shares_limit: Int!, $shares_cursor: String, $shares_sorts: [ShareSort!]) {"
          + " getNode(node_id: $node_id) { ...BaseNode description owner { id email full_name }"
          + " creator { id email full_name } last_editor { id email full_name } created_at"
          + " updated_at parent { id name ...Permissions } shares(limit: $shares_limit, cursor:"
          + " $shares_cursor, sorts: $shares_sorts) { ...Share } ... on Folder {"
          + " children(limit: $children_limit, page_token: $page_token, sort: $sort) { nodes {"
          + " ...Child } page_token } } } } "
          + String.join(" ", CHILD, BASE_NODE, PERMISSIONS, SHARE);

  private static final String GET_CHILD =
      "query getChild($node_id: ID!, $shares_limit: Int = 1) { getNode(node_id: $node_id) {"
          + " ...ChildWithParent } } "
          + String.join(
              " ", CHILD_WITH_PARENT, CHILD, BASE_NODE, PERMISSIONS, SHARE, NODE_PARENT, PARENT);

  private static final String FIND_NODES =
      "query findNodes($shared_with_me: Boolean, $limit: Int!, $sort: NodeSort, $shares_limit: Int"
          + " = 1) { findNodes(shared_with_me: $shared_with_me, limit: $limit, sort: $sort) {"
          + " nodes { ...ChildWithParent } page_token } } "
          + String.join(
              " ", CHILD_WITH_PARENT, CHILD, BASE_NODE, PERMISSIONS, SHARE, NODE_PARENT, PARENT);

  private String folderId;
  private String sharedByCId;
  private String distinctFileId;
  private String firstChildId;

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", A);
    FilesStackTestResource.getUserManagementService().registerToken("fake-token-b", B);
    FilesStackTestResource.getUserManagementService().registerToken("fake-token-c", C);
  }

  @BeforeEach
  void seed() throws SQLException {
    folderId = seedFolder("UMCOUNT", LOCAL_ROOT, A_COOKIE);
    seedShare(folderId, B, ACL.SharePermission.READ_AND_WRITE, A_COOKIE);
    seedShare(folderId, C, ACL.SharePermission.READ_AND_WRITE, A_COOKIE);
    for (int i = 0; i < 6; i++) {
      String id =
          seedFile(
              "a" + i + ".txt", folderId, ("a" + i).getBytes(StandardCharsets.UTF_8), A_COOKIE);
      firstChildId = firstChildId == null ? id : firstChildId;
    }
    for (int i = 0; i < 4; i++) {
      seedFile("b" + i + ".txt", folderId, ("b" + i).getBytes(StandardCharsets.UTF_8), B_COOKIE);
    }
    for (int i = 0; i < 6; i++) {
      seedFolder("d" + i, folderId, i % 2 == 0 ? A_COOKIE : B_COOKIE);
    }
    distinctFileId =
        seedFile("distinct.txt", folderId, "v1".getBytes(StandardCharsets.UTF_8), A_COOKIE);
    seedVersion(distinctFileId, "v2".getBytes(StandardCharsets.UTF_8), "distinct.txt", B_COOKIE);
    setOwner(distinctFileId, C);
    sharedByCId = seedFolder("SHAREDBYC", LOCAL_ROOT, C_COOKIE);
    seedShare(sharedByCId, B, ACL.SharePermission.READ_ONLY, C_COOKIE);
  }

  private static void setOwner(String nodeId, String ownerId) throws SQLException {
    try (Connection connection = jdbcConnection();
        PreparedStatement statement =
            connection.prepareStatement("UPDATE node SET owner_id = ? WHERE node_id = ?")) {
      statement.setString(1, ownerId);
      statement.setString(2, nodeId);
      statement.executeUpdate();
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
    return run(query, variables, cookie, expectedCalls, true);
  }

  private static JsonNode runWithAtMostCalls(
      String query, ObjectNode variables, String cookie, int maxCalls) throws Exception {
    return run(query, variables, cookie, maxCalls, false);
  }

  private static JsonNode run(
      String query, ObjectNode variables, String cookie, int calls, boolean exact)
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
    if (exact) {
      Assertions.assertThat(batchUserLookups()).as("POST /internal/users calls").isEqualTo(calls);
    } else {
      Assertions.assertThat(batchUserLookups())
          .as("POST /internal/users calls")
          .isLessThanOrEqualTo(calls);
    }
    return body.path("data");
  }

  private static String batchUserLookupBodies() {
    return RestAssured.given()
        .contentType("application/json")
        .body("{\"method\":\"POST\",\"urlPath\":\"/internal/users\"}")
        .post(userManagementUrl() + "/__admin/requests/find")
        .getBody()
        .asString();
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

  @Test
  void getChildrenDoesNotFetchUsersThatOnlyAppearAsCreator() throws Exception {
    seedFolder("CREATORONLY", folderId, C_COOKIE);

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

    Assertions.assertThat(data.path("getNode").path("children").path("nodes")).hasSize(17);
    Assertions.assertThat(batchUserLookupBodies()).doesNotContain(C);
  }

  @Test
  void getNodeOnAFolderResolvesEveryUserFieldWithAtMostTwoLookups() throws Exception {
    JsonNode data =
        runWithAtMostCalls(
            GET_NODE,
            vars()
                .put("node_id", folderId)
                .put("children_limit", 50)
                .put("sort", "NAME_ASC")
                .put("shares_limit", 6),
            A_COOKIE,
            2);

    JsonNode node = data.path("getNode");
    assertUser(node.path("owner"), A);
    assertUser(node.path("creator"), A);
    Assertions.assertThat(node.path("last_editor").isNull()).isTrue();
    Assertions.assertThat(node.path("shares")).hasSize(2);
    Assertions.assertThat(node.path("children").path("nodes")).hasSize(16);
  }

  @Test
  void getNodeOnAFileWithDistinctUsersResolvesThemWithOneLookup() throws Exception {
    JsonNode data =
        run(
            GET_NODE,
            vars()
                .put("node_id", distinctFileId)
                .put("children_limit", 50)
                .put("sort", "NAME_ASC")
                .put("shares_limit", 6),
            B_COOKIE,
            1);

    JsonNode node = data.path("getNode");
    assertUser(node.path("owner"), C);
    assertUser(node.path("creator"), A);
    assertUser(node.path("last_editor"), B);
  }

  @Test
  void getChildResolvesOwnerAndLastEditorWithAtMostTwoLookups() throws Exception {
    JsonNode data =
        runWithAtMostCalls(
            GET_CHILD, vars().put("node_id", distinctFileId).put("shares_limit", 1), B_COOKIE, 2);

    JsonNode node = data.path("getNode");
    assertUser(node.path("owner"), C);
    assertUser(node.path("last_editor"), B);
    assertUser(node.path("parent").path("owner"), A);
  }

  @Test
  void getChildDoesNotFetchTheParentsUnselectedLastEditor() throws Exception {
    String renamed =
        graphql(
                "mutation { updateNode(node_id: \\\""
                    + folderId
                    + "\\\", name: \\\"RENAMED\\\") { id } }",
                C_COOKIE)
            .getBody()
            .asString();
    Assertions.assertThat(renamed).contains(folderId);

    JsonNode data =
        runWithAtMostCalls(
            GET_CHILD, vars().put("node_id", firstChildId).put("shares_limit", 1), A_COOKIE, 2);

    assertUser(data.path("getNode").path("parent").path("owner"), A);
  }

  @Test
  void findNodesSharedWithMeResolvesUsersWithOneLookup() throws Exception {
    JsonNode data =
        run(
            FIND_NODES,
            vars().put("shared_with_me", true).put("limit", 50).put("sort", "NAME_ASC"),
            B_COOKIE,
            1);

    JsonNode nodes = data.path("findNodes").path("nodes");
    Assertions.assertThat(nodes).hasSize(19);
    boolean sawSharedByC = false;
    boolean sawFolder = false;
    for (JsonNode node : nodes) {
      String id = node.path("id").asText();
      boolean ownedByC = id.equals(sharedByCId) || id.equals(distinctFileId);
      assertUser(node.path("owner"), ownedByC ? C : A);
      sawSharedByC |= id.equals(sharedByCId);
      sawFolder |= id.equals(folderId);
    }
    Assertions.assertThat(sawSharedByC && sawFolder).isTrue();
  }

  @Test
  void theUserCacheDoesNotOutliveTheRequest() throws Exception {
    ObjectNode variables = vars().put("node_id", firstChildId).put("shares_limit", 1);
    runWithAtMostCalls(GET_CHILD, variables, A_COOKIE, 2);
    runWithAtMostCalls(GET_CHILD, variables, A_COOKIE, 2);
    Assertions.assertThat(batchUserLookups()).isBetween(1, 2);
  }
}
