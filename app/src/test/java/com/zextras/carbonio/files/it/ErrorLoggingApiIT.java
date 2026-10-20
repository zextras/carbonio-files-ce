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

  private static List<String> logLinesContaining(String marker) throws IOException {
    return readLog().stream().filter(line -> line.contains(marker)).toList();
  }

  private static List<String> awaitLogLinesContaining(String marker) throws Exception {
    eventually("log line containing '" + marker + "'", () -> !logLinesContaining(marker).isEmpty());
    return logLinesContaining(marker);
  }

  private static List<String> readLog() throws IOException {
    return Files.exists(APP_LOG) ? Files.readAllLines(APP_LOG) : List.of();
  }

  @Test
  void
      givenAnUnexpectedResolverFailureThenItIsLoggedOnceAtErrorWithStackAndTheClientSeesSystemError()
          throws Exception {
    String operation = "R06UnexpectedFailure";

    Response response = executeWithCorruptNodeType(operation, "r06-unexpected", 3);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(response.jsonPath().getList("errors")).hasSize(3);
    for (int i = 0; i < 3; i++) {
      Assertions.assertThat(response.jsonPath().getString("errors[" + i + "].message"))
          .isEqualTo("System error");
    }
    assertLoggedOnceWithStack(operation, "/a0");
  }

  private Response executeWithCorruptNodeType(String operation, String keyword, int aliases)
      throws Exception {
    String parentId = seedFolder(keyword + "-parent", LOCAL_ROOT, OWNER_COOKIE);
    seedFolder(keyword + "-one", parentId, OWNER_COOKIE);
    seedFolder(keyword + "-two", parentId, OWNER_COOKIE);
    StringBuilder fields = new StringBuilder();
    for (int i = 0; i < aliases; i++) {
      fields
          .append("a")
          .append(i)
          .append(": findNodes(keywords: [\"")
          .append(keyword)
          .append("\"], limit: 10) { nodes { id parent { id } } } ");
    }
    try (Connection connection = jdbcConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(
          "UPDATE node SET node_type = 'R06_BOGUS' WHERE node_id = '" + parentId + "'");
      try {
        return execute(operation, "query " + operation + " { " + fields + "}", OWNER_COOKIE);
      } finally {
        statement.execute(
            "UPDATE node SET node_type = 'FOLDER' WHERE node_id = '" + parentId + "'");
      }
    }
  }

  @Test
  void givenAnUnresolvableTokenThenTheRejectionIsLoggedAsWarnWithoutTheToken() throws Exception {
    String marker = "Authentication rejected: status=401 uri=/graphql/";
    int before = logLinesContaining(marker).size();

    Response response =
        execute(
            "R06Unauthorized",
            "query R06Unauthorized { getNode(node_id: \"x\") { id } }",
            "ZM_AUTH_TOKEN=r06-secret-token");

    Assertions.assertThat(response.getStatusCode()).isEqualTo(401);
    eventually("rejection logged", () -> logLinesContaining(marker).size() > before);
    List<String> warnLines = logLinesContaining(marker);
    Assertions.assertThat(warnLines).hasSize(before + 1);
    Assertions.assertThat(warnLines.get(before))
        .contains("WARN")
        .contains("reason=Unable to find requested user");
    Assertions.assertThat(String.join("\n", readLog())).doesNotContain("r06-secret-token");
  }

  @Test
  void givenNoCredentialsThenTheRejectionIsLoggedOnceAsWarnForTheWholeDocument() throws Exception {
    String operation = "R06NoCredentials";
    String marker = "Authentication rejected: reason=missing credentials operation=" + operation;

    Response response =
        execute(
            operation,
            "query R06NoCredentials { a: getNode(node_id: \"x\") { id } b: getNode(node_id: \"y\")"
                + " { id } c: getUserById(user_id: \"z\") { id } }",
            null);

    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(response.jsonPath().getString("errors[0].message"))
        .isEqualTo("Authentication required");
    Assertions.assertThat(response.jsonPath().getList("errors")).hasSize(3);
    List<String> warnLines = awaitLogLinesContaining(marker);
    Assertions.assertThat(warnLines).hasSize(1);
    Assertions.assertThat(warnLines.get(0)).contains("WARN");
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
    String marker = "Unexpected error in GraphQL operation '" + operation + "'";
    List<String> errorLines = awaitLogLinesContaining(marker);
    Assertions.assertThat(errorLines).hasSize(1);
    Assertions.assertThat(errorLines.get(0)).contains("ERROR").contains("at '" + path + "'");
    List<String> log = readLog();
    int start = log.indexOf(errorLines.get(0));
    Assertions.assertThat(log.subList(start + 1, Math.min(start + 40, log.size())))
        .anyMatch(line -> line.startsWith("\tat com.zextras.carbonio.files."));
  }
}
