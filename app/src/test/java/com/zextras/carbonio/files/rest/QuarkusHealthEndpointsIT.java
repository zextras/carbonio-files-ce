// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import org.junit.jupiter.api.Test;

/**
 * Out-of-process {@code @QuarkusIntegrationTest} (via {@link AbstractFilesIT}) for the health
 * surface carbonio-files exposes: the built-in Quarkus SmallRye endpoints under {@code /q/health/*}
 * (provided by {@code carbonio-quarkus-extensions-bootstrap} -> {@code quarkus-smallrye-health}).
 * The legacy custom JAX-RS {@code /health/*} routes were removed so files serves ONLY {@code
 * /q/health/*} (plus {@code /metrics}), matching carbonio-tasks.
 *
 * <p><b>SELF-ONLY liveness (house pattern):</b> the launched app's only reachable dependencies are
 * Postgres + WireMock fakes — no real storages, message-broker or preview. {@code /q/health/live}
 * still reports {@code UP} because no dependency readiness check is wired into liveness (liveness
 * has no custom checks; dependency checks belong to readiness). This proves a downstream dependency
 * being down can never fail the mesh liveness probe.
 */
class QuarkusHealthEndpointsIT extends AbstractFilesIT {

  @Test
  void healthLiveReturnsUpAndIsSelfOnly() {
    // No dependency checks are registered for liveness, so this is UP purely from the JVM being up.
    given().when().get("/q/health/live").then().statusCode(200).body("status", equalTo("UP"));
  }

  @Test
  void healthReadyReturnsUpWhenDatabaseIsUp() {
    given().when().get("/q/health/ready").then().statusCode(200).body("status", equalTo("UP"));
  }

  @Test
  void aggregateHealthReturnsUp() {
    given().when().get("/q/health").then().statusCode(200).body("status", equalTo("UP"));
  }
}
