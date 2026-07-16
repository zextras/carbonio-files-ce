// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.acceptance;

import com.zextras.carbonio.files.acceptance.seam.FilesTestApp;
import com.zextras.carbonio.files.acceptance.seam.impl.GuiceNettyFilesTestAppBuilder;
import com.zextras.carbonio.files.utilities.http.HttpRequest;
import com.zextras.carbonio.files.utilities.http.HttpResponse;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Covers {@code GET /metrics} (Task 1.7 of the acceptance coverage-expansion plan): an
 * unauthenticated route (no {@code auth-handler} in its pipeline, see {@code
 * HttpRoutingHandler#channelRead0}) that returns the Prometheus text-exposition scrape of the
 * in-process {@code PrometheusMeterRegistry}.
 */
class MetricsApiIT {

  @Test
  void givenNoAuthenticationTheMetricsEndpointShouldReturn200WithPrometheusTextExposition() {
    // Given — deliberately no withUserManagement()/cookie: /metrics has no auth-handler.
    try (FilesTestApp app =
        GuiceNettyFilesTestAppBuilder.aFilesTestApp()
            .withDatabase()
            .withServiceDiscover()
            .build()) {
      HttpRequest httpRequest = HttpRequest.of("GET", "/metrics", null, null);

      // When
      HttpResponse httpResponse = app.send(httpRequest);

      // Then
      Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);
      Assertions.assertThat(httpResponse.getHeaders())
          .anyMatch(
              header ->
                  header.getKey().equalsIgnoreCase("content-type")
                      && header.getValue().contains("text/plain"));

      // The "files.upload" counter registered by PrometheusService is Prometheus-sanitized
      // (dots -> underscores); its presence proves this is the real scrape, not an empty body.
      Assertions.assertThat(httpResponse.getBodyPayload()).contains("files_upload");
    }
  }
}
