// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.resources;

import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * Legacy parity for {@code GET /metrics}: quarkus-micrometer-registry-prometheus's own handler
 * ({@code io.quarkus.micrometer.runtime.export.handlers.PrometheusHandler#chooseContentType})
 * defaults to serving the newer OpenMetrics text exposition ({@code application/openmetrics-text;
 * version=1.0.0}) whenever the request carries no {@code Accept} header, and only falls back to the
 * classic Prometheus text format ({@code text/plain; version=0.0.4}) when the client explicitly
 * asks for {@code text/plain} (or {@code text/html}). The legacy Netty scrape endpoint only ever
 * served the classic text format, and real scrapers (and this acceptance suite) hit {@code
 * /metrics} with no {@code Accept} header at all.
 *
 * <p>There is no build-time config knob for the default content type (verified against the
 * extension's {@code PrometheusHandler} bytecode), so this filter runs immediately before the
 * metrics route and injects {@code Accept: text/plain} only when the client did not already state a
 * preference — an explicit {@code Accept} (e.g. a scraper that really wants OpenMetrics) is left
 * untouched.
 */
@ApplicationScoped
public class MetricsAcceptFilter {

  public void registerRoutes(@Observes Router router) {
    router.route("/metrics").order(-100).handler(this::defaultToPrometheusTextFormat);
  }

  private void defaultToPrometheusTextFormat(RoutingContext ctx) {
    if (ctx.request().getHeader("Accept") == null) {
      ctx.request().headers().set("Accept", "text/plain");
    }
    ctx.next();
  }
}
