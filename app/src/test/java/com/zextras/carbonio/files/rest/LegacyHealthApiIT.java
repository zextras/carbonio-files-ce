// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import org.junit.jupiter.api.Test;

class LegacyHealthApiIT extends AbstractFilesIT {

  @Test
  void healthLiveReturns204() {
    given().when().get("/health/live").then().statusCode(204);
  }

  @Test
  void healthReadyReturns204WhenRequiredDependenciesAreUp() {
    given().when().get("/health/ready").then().statusCode(204);
  }

  @Test
  void healthReturns200WithDependencyList() {
    given()
        .when()
        .get("/health")
        .then()
        .statusCode(200)
        .body("ready", equalTo(true))
        .body("dependencies", hasSize(6))
        .body("dependencies[0].name", equalTo("database"))
        .body("dependencies[0].ready", equalTo(true))
        .body("dependencies[0].live", equalTo(true))
        .body("dependencies[0].type", equalTo("REQUIRED"))
        .body("dependencies[1].name", equalTo("carbonio-user-management"))
        .body("dependencies[1].type", equalTo("REQUIRED"))
        .body("dependencies[2].name", equalTo("carbonio-storages"))
        .body("dependencies[2].type", equalTo("REQUIRED"))
        .body("dependencies[3].name", equalTo("carbonio-preview"))
        .body("dependencies[3].type", equalTo("OPTIONAL"))
        .body("dependencies[4].name", equalTo("carbonio-docs-connector"))
        .body("dependencies[4].type", equalTo("OPTIONAL"))
        .body("dependencies[5].name", equalTo("carbonio-message-broker"))
        .body("dependencies[5].type", equalTo("OPTIONAL"));
  }
}
