// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ErrorLoggingApiIT extends AbstractFilesIT {

  private static final Path APP_LOG = Path.of("target", "quarkus.log");
  private static final String OWNER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String OWNER_COOKIE = "ZM_AUTH_TOKEN=fake-token";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", OWNER_ID);
  }

  private static Response execute(String operationName, String query, String cookie) {
    var request =
        RestAssured.given()
            .contentType("application/json")
            .body(Map.of("operationName", operationName, "query", query));
    if (cookie != null) {
      request = request.header("Cookie", cookie);
    }
    return request.post("/graphql/");
  }

  private static List<String> appLogLinesContaining(String marker) throws Exception {
    long deadline = System.currentTimeMillis() + 5000;
    List<String> lines = List.of();
    do {
      lines = readLog().stream().filter(line -> line.contains(marker)).toList();
      if (!lines.isEmpty()) {
        return lines;
      }
      Thread.sleep(100);
    } while (System.currentTimeMillis() < deadline);
    return lines;
  }

  private static List<String> readLog() throws IOException {
    return Files.exists(APP_LOG) ? Files.readAllLines(APP_LOG) : List.of();
  }

  @Test
  void
      givenAnUnexpectedResolverFailureThenItIsLoggedOnceAtErrorWithStackAndTheClientSeesSystemError()
          throws Exception {
    String operation = "R06UnexpectedFailure";

    Response response =
        execute(
            operation,
            "query R06UnexpectedFailure { findNodes(folder_id: \"LOCAL_ROOT\", limit: 0,"
                + " sort: NAME_ASC) { nodes { id } } }",
            OWNER_COOKIE);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(response.jsonPath().getString("errors[0].message"))
        .isEqualTo("System error");
    List<String> errorLines =
        appLogLinesContaining("Unexpected error in GraphQL operation '" + operation + "'");
    Assertions.assertThat(errorLines).hasSize(1);
    Assertions.assertThat(errorLines.get(0)).contains("ERROR").contains("at '/findNodes'");
    String log = String.join("\n", readLog());
    int start = log.indexOf(errorLines.get(0));
    Assertions.assertThat(log.substring(start))
        .containsPattern("(?s)'/findNodes'.*?\\n\\s+at com\\.zextras\\.carbonio\\.files\\.");
  }

  @Test
  void givenAnExpectedBusinessErrorThenItIsNotLoggedAtError() throws Exception {
    String operation = "R06ExpectedNotFound";

    Response response =
        execute(
            operation,
            "query R06ExpectedNotFound { getNode(node_id: \"00000000-0000-0000-0000-000000000000\")"
                + " { id } }",
            OWNER_COOKIE);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(response.jsonPath().getString("errors[0].message"))
        .contains("Could not find node");
    Thread.sleep(500);
    Assertions.assertThat(readLog())
        .noneMatch(line -> line.contains("ERROR") && line.contains(operation));
  }

  @Test
  void givenAnUnresolvableTokenThenTheRejectionIsLoggedAsWarnWithoutTheToken() throws Exception {
    String marker = "Authentication rejected: status=401 uri=/graphql/";
    int before = readLog().stream().filter(line -> line.contains(marker)).toList().size();

    Response response =
        execute(
            "R06Unauthorized",
            "query R06Unauthorized { getNode(node_id: \"x\") { id } }",
            "ZM_AUTH_TOKEN=r06-secret-token");

    Assertions.assertThat(response.getStatusCode()).isEqualTo(401);
    List<String> warnLines = appLogLinesContaining(marker);
    Assertions.assertThat(warnLines).hasSize(before + 1);
    Assertions.assertThat(warnLines.get(before))
        .contains("WARN")
        .contains("reason=Unable to find requested user");
    Assertions.assertThat(String.join("\n", readLog())).doesNotContain("r06-secret-token");
  }

  @Test
  void givenNoCredentialsThenTheRejectionIsLoggedAsWarn() throws Exception {
    Response response =
        execute(
            "R06NoCredentials", "query R06NoCredentials { getNode(node_id: \"x\") { id } }", null);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(response.jsonPath().getString("errors[0].message"))
        .isEqualTo("Authentication required");
    List<String> warnLines =
        appLogLinesContaining("Authentication rejected: reason=missing credentials operation=");
    Assertions.assertThat(warnLines).isNotEmpty();
    Assertions.assertThat(warnLines.get(0)).contains("WARN");
  }

  @Test
  void givenAnAnonymousDocumentWithSeveralProtectedFieldsThenTheRejectionIsLoggedOnce()
      throws Exception {
    String marker = "Authentication rejected: reason=missing credentials operation=R06Multi";

    Response response =
        execute(
            "R06Multi",
            "query R06Multi { a: getNode(node_id: \"x\") { id } b: getNode(node_id: \"y\") { id }"
                + " c: getUserById(user_id: \"z\") { id } }",
            null);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(response.jsonPath().getList("errors")).hasSize(3);
    Assertions.assertThat(appLogLinesContaining(marker)).hasSize(1);
    Thread.sleep(500);
    Assertions.assertThat(appLogLinesContaining(marker)).hasSize(1);
  }

  @Test
  void givenAFailureInABatchResolverThenItIsLoggedOnceAtErrorAndTheClientSeesSystemError()
      throws Exception {
    String parentId = seedFolder("r06-parent", LOCAL_ROOT, OWNER_COOKIE);
    String childId = seedFolder("r06-child", parentId, OWNER_COOKIE);
    String operation = "R06BatchFailure";

    Response response;
    try (Connection connection = jdbcConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(
          "UPDATE node SET node_type = 'R06_BOGUS' WHERE node_id = '" + parentId + "'");
      response =
          execute(
              operation,
              "query R06BatchFailure { getNode(node_id: \""
                  + childId
                  + "\") { id parent { id } } }",
              OWNER_COOKIE);
      statement.execute("UPDATE node SET node_type = 'FOLDER' WHERE node_id = '" + parentId + "'");
    }

    Assertions.assertThat(response.jsonPath().getString("errors[0].message"))
        .isEqualTo("System error");
    Assertions.assertThat(response.jsonPath().getList("errors[0].path"))
        .containsExactly("getNode", "parent");
    assertLoggedOnceWithStack(operation, "/getNode/parent");
  }

  @Test
  void givenAFailureInAMutationBodyThenItIsLoggedOnceAtErrorAndTheClientSeesSystemError()
      throws Exception {
    String operation = "R06MutationFailure";

    Response response;
    try (Connection connection = jdbcConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(
          "ALTER TABLE node ADD CONSTRAINT r06_boom CHECK (name <> 'r06-boom') NOT VALID");
      try {
        response =
            execute(
                operation,
                "mutation R06MutationFailure { createFolder(destination_id: \"LOCAL_ROOT\","
                    + " name: \"r06-boom\") { id } }",
                OWNER_COOKIE);
      } finally {
        statement.execute("ALTER TABLE node DROP CONSTRAINT r06_boom");
      }
    }

    Assertions.assertThat(response.jsonPath().getString("errors[0].message"))
        .isEqualTo("System error");
    Assertions.assertThat(response.jsonPath().getList("errors[0].path"))
        .containsExactly("createFolder");
    assertLoggedOnceWithStack(operation, "/createFolder");
  }

  private static void assertLoggedOnceWithStack(String operation, String path) throws Exception {
    String marker = "Unexpected error in GraphQL operation '" + operation + "' at '" + path + "'";
    List<String> errorLines = appLogLinesContaining(marker);
    Assertions.assertThat(errorLines).hasSize(1);
    Assertions.assertThat(errorLines.get(0)).contains("ERROR");
    Thread.sleep(500);
    List<String> log = readLog();
    Assertions.assertThat(log.stream().filter(line -> line.contains(marker)).count()).isEqualTo(1);
    int start = log.indexOf(errorLines.get(0));
    Assertions.assertThat(log.subList(start + 1, Math.min(start + 40, log.size())))
        .anyMatch(line -> line.startsWith("\tat com.zextras.carbonio.files."));
  }
}
