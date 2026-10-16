// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.security.identity.CurrentIdentityAssociation;
import io.quarkus.smallrye.graphql.runtime.SmallRyeGraphQLExecutionHandler;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.quarkus.vertx.http.runtime.security.QuarkusHttpUser;
import io.smallrye.graphql.execution.context.SmallRyeContextManager;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpHeaders;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class GraphQLExecutionRoute {

  private static final Pattern GRAPHQL_JSON_MEDIA_TYPE =
      Pattern.compile("^application/graphql(-response)?\\+json");

  @Inject CurrentIdentityAssociation identityAssociation;
  @Inject CurrentVertxRequest currentVertxRequest;

  @ConfigProperty(name = "quarkus.smallrye-graphql.http.get.enabled", defaultValue = "false")
  boolean allowGet;

  @ConfigProperty(
      name = "quarkus.smallrye-graphql.http.post.queryparameters.enabled",
      defaultValue = "false")
  boolean allowPostWithQueryParameters;

  public void register(@Observes Router router) {
    ExecutionHandler handler =
        new ExecutionHandler(
            allowGet, allowPostWithQueryParameters, identityAssociation, currentVertxRequest);
    // Ahead of SmallRye's /graphql websocket route (-99): it ends the request context on close.
    router.post("/graphql").order(-198).blockingHandler(handler, false);
    router.post("/graphql/").order(-198).blockingHandler(handler, false);
  }

  private static final class ExecutionHandler extends SmallRyeGraphQLExecutionHandler {

    private final CurrentIdentityAssociation identityAssociation;
    private final CurrentVertxRequest currentVertxRequest;

    ExecutionHandler(
        boolean allowGet,
        boolean allowPostWithQueryParameters,
        CurrentIdentityAssociation identityAssociation,
        CurrentVertxRequest currentVertxRequest) {
      super(allowGet, allowPostWithQueryParameters, true, identityAssociation, currentVertxRequest);
      this.identityAssociation = identityAssociation;
      this.currentVertxRequest = currentVertxRequest;
    }

    @Override
    public void handle(RoutingContext ctx) {
      ManagedContext requestContext = Arc.container().requestContext();
      requestContext.activate();
      try {
        if (ctx.user() instanceof QuarkusHttpUser user) {
          identityAssociation.setIdentity(user.getSecurityIdentity());
        } else {
          identityAssociation.setIdentity(QuarkusHttpUser.getSecurityIdentity(ctx, null));
        }
        currentVertxRequest.setCurrent(ctx);
        ctx.addHeadersEndHandler(ignored -> useJsonMediaType(ctx.response().headers()));
        doHandle(ctx);
      } finally {
        SmallRyeContextManager.clearCurrentSmallRyeContext();
        requestContext.terminate();
      }
    }

    private static void useJsonMediaType(MultiMap headers) {
      String contentType = headers.get(HttpHeaders.CONTENT_TYPE);
      if (contentType != null) {
        headers.set(
            HttpHeaders.CONTENT_TYPE,
            GRAPHQL_JSON_MEDIA_TYPE.matcher(contentType).replaceFirst("application/json"));
      }
    }
  }
}
