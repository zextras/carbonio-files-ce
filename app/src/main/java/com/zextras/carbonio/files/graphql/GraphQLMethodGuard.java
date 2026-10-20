// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql;

import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/** Answers 405 to non-POST/OPTIONS and upgrade requests on /graphql before any body handler. */
@ApplicationScoped
public class GraphQLMethodGuard {

  private static final String ALLOWED_METHODS = "POST, OPTIONS";

  public void register(@Observes Router router) {
    router.route("/graphql").order(-201).handler(GraphQLMethodGuard::guard);
  }

  private static void guard(RoutingContext ctx) {
    HttpMethod method = ctx.request().method();
    boolean upgrade = ctx.request().headers().contains(HttpHeaders.UPGRADE);
    if (!upgrade && (HttpMethod.POST.equals(method) || HttpMethod.OPTIONS.equals(method))) {
      ctx.next();
      return;
    }
    ctx.response().setStatusCode(405).putHeader(HttpHeaders.ALLOW, ALLOWED_METHODS).end();
    if (!ctx.request().isEnded()) {
      // Quarkus pauses requests and never resumes them without a body-size limit; drain and discard
      ctx.request().resume();
    }
  }
}
