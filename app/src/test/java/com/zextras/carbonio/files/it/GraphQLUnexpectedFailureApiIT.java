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
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

/** A numeric operationName makes SmallRye throw a ClassCastException before the execution. */
class GraphQLUnexpectedFailureApiIT extends AbstractFilesIT {

  private static final Path APP_LOG = Path.of("target", "quarkus.log");
  private static final String LOGGED_FAILURE = "HTTP Request to /graphql/ failed, error id";

  private static long loggedFailures() throws IOException {
    return Files.exists(APP_LOG)
        ? Files.readAllLines(APP_LOG).stream().filter(line -> line.contains(LOGGED_FAILURE)).count()
        : 0;
  }

  @Test
  void givenAnUnexpectedFailureTheResponseIsA500WithAnEmptyBodyAndQuarkusLogsIt() throws Exception {
    long before = loggedFailures();

    Response response =
        RestAssured.given()
            .contentType("application/json")
            .accept("*/*")
            .header("Cookie", "ZM_AUTH_TOKEN=" + FilesStackTestResource.AUTH_TOKEN)
            .body("{\"query\":\"query { getConfigs { name } }\",\"operationName\":1}")
            .post("/graphql/");

    Assertions.assertThat(response.getStatusCode()).isEqualTo(500);
    Assertions.assertThat(response.getBody().asString()).isEmpty();
    long deadline = System.currentTimeMillis() + 5000;
    while (loggedFailures() == before && System.currentTimeMillis() < deadline) {
      Thread.sleep(100);
    }
    Assertions.assertThat(loggedFailures()).isEqualTo(before + 1);
  }
}
