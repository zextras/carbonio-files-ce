// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql;

import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * Preserves the legacy public GraphQL endpoint path {@code POST /public/graphql} after the
 * code-first cutover.
 *
 * <p>The legacy stack served two separate schemas: authenticated ops on {@code POST /graphql} and a
 * distinct unauthenticated subset on {@code POST /public/graphql} (via {@code
 * PublicGraphQLProvider} / {@code FilesGraphQLRoutes}). The code-first engine unifies both into the
 * single quarkus-smallrye-graphql schema served at {@code /graphql}, where the public operations
 * ({@code getPublicNode}/{@code findPublicNodes}) are {@code @PermitAll}. Existing public clients
 * still call {@code /public/graphql}, so this thin Vert.x route — registered exactly as the legacy
 * {@code FilesGraphQLRoutes} registered its routes, on the {@code @Observes Router} startup event —
 * forwards those requests to the SmallRye {@code /graphql} handler.
 *
 * <p>The forward is a Vert.x {@code reroute} performed BEFORE the request body is read, so the
 * SmallRye route owns body reading exactly as for a direct {@code /graphql} call (its execution
 * handler reads the buffered {@code RoutingContext.body()}). Authentication ran once against the
 * original {@code /public/graphql} path — which the {@code FilesGraphQLAuthMechanism} deliberately
 * ignores (it only turns the cookie into an identity for {@code /graphql}) — so the rerouted
 * request carries an anonymous identity and only the {@code @PermitAll} public operations succeed,
 * matching the legacy unauthenticated {@code /public/graphql} contract.
 */
@ApplicationScoped
public class PublicGraphQLRoute {

  public void register(@Observes Router router) {
    router.post("/public/graphql").handler(ctx -> ctx.reroute(HttpMethod.POST, "/graphql"));
  }
}
