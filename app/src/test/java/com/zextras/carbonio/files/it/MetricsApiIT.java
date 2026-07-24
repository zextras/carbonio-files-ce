// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code com.zextras.carbonio.files.acceptance.MetricsApiIT} rewritten as an out-of-process
 * {@code @QuarkusIntegrationTest} on {@link AbstractFilesIT}: {@code GET /metrics}, an
 * UNAUTHENTICATED route (no {@code auth-handler} in its pipeline, see {@code
 * HttpRoutingHandler#channelRead0}) that returns the Prometheus text-exposition scrape of the
 * out-of-process app's {@code PrometheusMeterRegistry}.
 *
 * <p><b>Pulled forward from Batch K to fix an exposed order-dependency (Batch D follow-up).</b>
 * The {@code files.upload} Micrometer counter (Prometheus-sanitized to {@code files_upload}) is
 * registered LAZILY by {@code BlobService#uploadFile}/{@code uploadFileVersion} on the FIRST real
 * upload — it does not exist in a fresh registry until some upload happens. The original seam
 * version relied on another {@code acceptance} class incidentally uploading first in the shared
 * in-process JVM; once {@code InternalBlobResourceApiIT} (Batch D) left that shared JVM for its
 * own out-of-process {@code it/} run, nothing primed the counter before this test any more. This
 * class is made self-contained instead: it seeds its OWN authenticated upload immediately before
 * scraping {@code /metrics}, so the counter's presence is guaranteed regardless of run order or
 * of which other {@code it/} classes have (or have not) run first — see {@code
 * UploadFileApiIT#uploadCounterValue()} for the sibling class that reads this same counter's
 * value (rather than merely its presence) via a before/after delta.
 */
class MetricsApiIT extends AbstractFilesIT {

  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String REQUESTER_COOKIE = "ZM_AUTH_TOKEN=fake-token";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", REQUESTER_ID);
  }

  @Test
  void givenNoAuthenticationTheMetricsEndpointShouldReturn200WithPrometheusTextExposition() {
    // Given — a real authenticated upload, purely to register the "files.upload" counter in THIS
    // process; the assertions below are on /metrics itself, not on the upload's response.
    upload(
        null,
        null,
        "metrics-seed".getBytes(StandardCharsets.UTF_8),
        "metrics-seed.txt",
        REQUESTER_COOKIE);

    // When — deliberately no cookie at all: /metrics has no auth-handler in its pipeline. The
    // explicit "Accept: text/plain" pins the classic Prometheus text exposition: quarkus-
    // micrometer-registry-prometheus's own handler defaults to the newer OpenMetrics format
    // whenever the request states ANY Accept preference (RestAssured's client always sends one),
    // and only serves the classic format when text/plain is explicitly requested — see {@code
    // MetricsAcceptFilter}'s javadoc for the full negotiation rationale.
    Response response = RestAssured.given().header("Accept", "text/plain").get("/metrics");

    // Then
    Assertions.assertThat(response.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(response.getContentType()).contains("text/plain");

    // The "files.upload" counter registered by PrometheusService is Prometheus-sanitized
    // (dots -> underscores); its presence proves this is the real scrape, not an empty body.
    Assertions.assertThat(response.getBody().asString()).contains("files_upload");
  }
}
