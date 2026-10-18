// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Clients that reset the connection while getChildren runs must not leak pool connections: the
 * request-scoped Session lives until the operation completes, whatever the client does.
 */
@WithTestResource(
    value = GraphQLDisconnectLeakIT.SmallPool.class,
    scope = TestResourceScope.RESTRICTED_TO_CLASS)
class GraphQLDisconnectLeakIT extends AbstractFilesIT {

  private static final String COOKIE = "ZM_AUTH_TOKEN=" + FilesStackTestResource.AUTH_TOKEN;
  private static final int FILES = 40;
  private static final int RESETTING_CLIENTS = 12;
  private static final long STORM_MILLIS = 10_000;
  private static final long RECOVERY_MILLIS = 60_000;

  private static final String GET_CHILDREN =
      """
      query getChildren($node_id: ID!, $children_limit: Int!, $page_token: String,
          $sort: NodeSort!, $shares_limit: Int = 1) {
        getNode(node_id: $node_id) {
          ...Parent
          ... on Folder {
            children(limit: $children_limit, page_token: $page_token, sort: $sort) {
              nodes { ...Child __typename }
              page_token
              __typename
            }
            __typename
          }
          __typename
        }
      }
      fragment Parent on Node {
        id name type owner { id full_name email __typename } rootId ...Permissions __typename
      }
      fragment Permissions on Node {
        permissions {
          can_read can_write_file can_write_folder can_delete can_add_version can_read_link
          can_change_link can_share can_read_share can_change_share __typename
        }
        __typename
      }
      fragment Child on Node {
        ...BaseNode
        owner { id full_name email __typename }
        updated_at
        last_editor { id full_name email __typename }
        shares(limit: $shares_limit) { ...Share __typename }
        __typename
      }
      fragment BaseNode on Node {
        id name type ...Permissions
        ... on File { size mime_type extension version __typename }
        flagged rootId __typename
      }
      fragment Share on Share {
        permission
        share_target {
          ... on User { email full_name id __typename }
          ... on DistributionList { id name __typename }
          __typename
        }
        created_at
        __typename
      }
      """;

  public static class SmallPool implements QuarkusTestResourceLifecycleManager {
    @Override
    public Map<String, String> start() {
      return Map.of(
          "quarkus.datasource.jdbc.max-size", "6",
          "quarkus.datasource.jdbc.acquisition-timeout", "3s",
          "quarkus.datasource.jdbc.idle-removal-interval", "1s");
    }

    @Override
    public void stop() {}
  }

  private String getChildrenBody;

  @BeforeEach
  void seedFolderOfFiles() throws Exception {
    String folderId = seedFolder("disconnect-leak", LOCAL_ROOT, COOKIE);
    for (int i = 0; i < FILES; i++) {
      seedFile(
          "f" + i + ".txt", folderId, ("content " + i).getBytes(StandardCharsets.UTF_8), COOKIE);
    }
    getChildrenBody =
        new ObjectMapper()
            .writeValueAsString(
                Map.of(
                    "operationName",
                    "getChildren",
                    "query",
                    GET_CHILDREN,
                    "variables",
                    Map.of(
                        "node_id",
                        folderId,
                        "children_limit",
                        50,
                        "sort",
                        "NAME_ASC",
                        "shares_limit",
                        1)));
  }

  @Test
  void givenClientsResettingGetChildrenThenEveryPoolConnectionIsReturned() throws Exception {
    byte[] request = rawRequest(getChildrenBody);
    long stormEnd = System.currentTimeMillis() + STORM_MILLIS;
    Callable<Void> client = () -> resetRepeatedly(request, stormEnd);
    ExecutorService clients = Executors.newFixedThreadPool(RESETTING_CLIENTS);
    try {
      List<Future<Void>> storm = clients.invokeAll(Collections.nCopies(RESETTING_CLIENTS, client));
      for (Future<Void> finished : storm) {
        finished.get();
      }
    } finally {
      clients.shutdownNow();
    }

    long deadline = System.currentTimeMillis() + RECOVERY_MILLIS;
    Response children = getChildren();
    while (!isFullListing(children) && System.currentTimeMillis() < deadline) {
      Thread.sleep(250);
      children = getChildren();
    }
    Assertions.assertThat(children.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(TestUtils.jsonResponseToErrors(children.getBody().asString())).isEmpty();
    Assertions.assertThat(children.jsonPath().getList("data.getNode.children.nodes"))
        .hasSize(FILES);

    deadline = System.currentTimeMillis() + RECOVERY_MILLIS;
    int open = openPoolConnections();
    while (open > 0 && System.currentTimeMillis() < deadline) {
      Thread.sleep(250);
      open = openPoolConnections();
    }
    Assertions.assertThat(open).as("pool connections never returned").isZero();
  }

  private static byte[] rawRequest(String body) {
    return ("POST /graphql/ HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n"
            + "Accept: */*\r\nCookie: "
            + COOKIE
            + "\r\nContent-Length: "
            + body.getBytes(StandardCharsets.UTF_8).length
            + "\r\n\r\n"
            + body)
        .getBytes(StandardCharsets.UTF_8);
  }

  private static Void resetRepeatedly(byte[] request, long until) throws Exception {
    while (System.currentTimeMillis() < until) {
      try (Socket socket = new Socket("localhost", RestAssured.port)) {
        socket.getOutputStream().write(request);
        Thread.sleep(ThreadLocalRandom.current().nextInt(41));
        socket.setSoLinger(true, 0);
      }
    }
    return null;
  }

  private Response getChildren() {
    return RestAssured.given()
        .contentType("application/json")
        .header("Cookie", COOKIE)
        .body(getChildrenBody)
        .post("/graphql/");
  }

  private static boolean isFullListing(Response response) {
    if (response.getStatusCode() != 200
        || !TestUtils.jsonResponseToErrors(response.getBody().asString()).isEmpty()) {
      return false;
    }
    List<Object> nodes = response.jsonPath().getList("data.getNode.children.nodes");
    return nodes != null && nodes.size() == FILES;
  }

  private static int openPoolConnections() throws SQLException {
    try (Connection connection = jdbcConnection();
        Statement statement = connection.createStatement();
        ResultSet resultSet =
            statement.executeQuery(
                "SELECT count(*) FROM pg_stat_activity WHERE application_name = 'files'")) {
      resultSet.next();
      return resultSet.getInt(1);
    }
  }
}
