// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.security.AuthenticationException;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import io.quarkus.security.identity.CurrentIdentityAssociation;
import io.quarkus.smallrye.graphql.runtime.SmallRyeGraphQLExecutionHandler;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.quarkus.vertx.http.runtime.security.QuarkusHttpUser;
import io.smallrye.graphql.execution.context.SmallRyeContextManager;
import io.smallrye.mutiny.CompositeException;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;

/**
 * Serves POST /graphql like a blocking REST endpoint: the request context lives until the operation
 * ends, also when the client disconnects (SmallRye's handler ends it as soon as the connection
 * closes, under the running resolvers).
 *
 * <p>Removable once every resolver, reads included, runs in its own transaction (Quarkus ≥ 3.38
 * adds them) and the long blob operations run as asynchronous jobs: {@code
 * quarkus.smallrye-graphql.nonblocking.enabled=false} then covers the threading, and only the empty
 * 500 for unexpected failures still needs a plain route failure handler.
 */
@ApplicationScoped
public class GraphQLExecutionRoute {

  @Inject CurrentIdentityAssociation identityAssociation;
  @Inject CurrentVertxRequest currentVertxRequest;

  public void register(@Observes Router router) {
    ExecutionHandler handler = new ExecutionHandler(identityAssociation, currentVertxRequest);
    router
        .post("/graphql")
        .order(-198)
        .blockingHandler(handler, false)
        .failureHandler(GraphQLExecutionRoute::endWithoutBody);
  }

  // The UI cannot decode QuarkusErrorHandler's JSON body; it still logs the failure.
  private static void endWithoutBody(RoutingContext ctx) {
    Throwable failure = ctx.failure();
    Throwable cause = unwrapCompletion(failure);
    boolean answeredByQuarkus =
        cause instanceof AuthenticationException
            || cause instanceof UnauthorizedException
            || cause instanceof ForbiddenException
            || cause instanceof RejectedExecutionException;
    if (answeredByQuarkus && cause != failure) {
      // QuarkusErrorHandler maps only the unwrapped exception to its 401/403/503.
      ctx.fail(cause);
      return;
    }
    if (failure != null && !answeredByQuarkus && !ctx.response().headWritten()) {
      ctx.response().setStatusCode(ctx.statusCode()).end();
    }
    ctx.next();
  }

  private static Throwable unwrapCompletion(Throwable failure) {
    Throwable cause = failure;
    while ((cause instanceof CompletionException && cause.getCause() != null)
        || cause instanceof CompositeException) {
      cause =
          cause instanceof CompositeException composite
              ? composite.getCauses().get(0)
              : cause.getCause();
    }
    return cause;
  }

  private static final class ExecutionHandler extends SmallRyeGraphQLExecutionHandler {

    private final CurrentIdentityAssociation identityAssociation;
    private final CurrentVertxRequest currentVertxRequest;

    ExecutionHandler(
        CurrentIdentityAssociation identityAssociation, CurrentVertxRequest currentVertxRequest) {
      super(false, false, true, identityAssociation, currentVertxRequest);
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
        doHandle(ctx);
      } finally {
        SmallRyeContextManager.clearCurrentSmallRyeContext();
        requestContext.terminate();
      }
    }
  }
}
