// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;

import com.zextras.carbonio.files.FilesStackTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * End-to-end integration test for the P5b legacy-path health endpoints ({@code
 * com.zextras.carbonio.files.rest.resources.HealthResource}) against a real Postgres via {@link
 * FilesStackTestResource}.
 *
 * <p>Proves the SELF-ONLY house pattern (see {@code HealthService}/{@code HealthResource}
 * javadoc): {@code /health/live} and {@code /health/ready} report UP purely from the database
 * connection being up, with no real user-management/storages/preview/message-broker required —
 * {@code FilesStackTestResource} only stands up Postgres + WireMock + an in-process gRPC stub, no
 * message broker or storages, yet these endpoints still report healthy.
 */
@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class HealthResourceIT {

  @Test
  void healthLiveReturnsUpWhenDatabaseIsUp() {
    given().when().get("/health/live").then().statusCode(204);
  }

  @Test
  void healthReadyReturnsUpWhenDatabaseIsUp() {
    given().when().get("/health/ready").then().statusCode(204);
  }

  @Test
  void healthReturnsReadyJsonShapeWhenDatabaseIsUp() {
    given()
        .when()
        .get("/health")
        .then()
        .statusCode(200)
        .body("ready", equalTo(true))
        .body("dependencies.size()", greaterThan(0))
        .body("dependencies.find { it.name == 'database' }.type", equalTo("REQUIRED"))
        .body("dependencies.find { it.name == 'database' }.live", equalTo(true));
  }
}
