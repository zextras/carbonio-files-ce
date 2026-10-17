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
}
