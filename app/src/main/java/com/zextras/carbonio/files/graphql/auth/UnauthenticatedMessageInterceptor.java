// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.auth;

import io.quarkus.security.Authenticated;
import io.quarkus.security.UnauthorizedException;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.smallrye.graphql.execution.context.SmallRyeContext;
import io.smallrye.graphql.execution.context.SmallRyeContextManager;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Wraps Quarkus' AuthenticatedInterceptor (priority 150): its exception has no message.
@Authenticated
@Interceptor
@Priority(Interceptor.Priority.PLATFORM_BEFORE + 100)
public class UnauthenticatedMessageInterceptor {

  private static final Logger logger =
      LoggerFactory.getLogger(UnauthenticatedMessageInterceptor.class);

  public static final String MESSAGE = "Authentication required";

  private static final String LOGGED_ATTRIBUTE = "files.auth.rejection.logged";

  @Inject CurrentVertxRequest currentRequest;

  @AroundInvoke
  Object requireMessage(InvocationContext context) throws Exception {
    try {
      return context.proceed();
    } catch (UnauthorizedException e) {
      if (e.getMessage() != null) {
        throw e;
      }
      try {
        logOncePerRequest();
      } catch (RuntimeException ignored) {
        // Logging must never replace the client-visible authentication error.
      }
      throw new UnauthorizedException(MESSAGE, e);
    }
  }

  private void logOncePerRequest() {
    RoutingContext request = currentRequest.getCurrent();
    if (request != null) {
      if (request.get(LOGGED_ATTRIBUTE) != null) {
        return;
      }
      request.put(LOGGED_ATTRIBUTE, Boolean.TRUE);
    }
    SmallRyeContext graphQLContext = SmallRyeContextManager.getCurrentSmallRyeContext();
    String operation =
        graphQLContext == null
            ? "anonymous"
            : graphQLContext.getOperationName().orElse("anonymous");
    logger.warn("Authentication rejected: reason=missing credentials operation={}", operation);
  }
}
