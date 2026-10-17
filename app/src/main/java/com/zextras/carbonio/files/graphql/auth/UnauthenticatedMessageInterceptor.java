// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.auth;

import io.quarkus.security.Authenticated;
import io.quarkus.security.UnauthorizedException;
import jakarta.annotation.Priority;
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

  @AroundInvoke
  Object requireMessage(InvocationContext context) throws Exception {
    try {
      return context.proceed();
    } catch (UnauthorizedException e) {
      if (e.getMessage() != null) {
        throw e;
      }
      logger.warn(
          "Authentication rejected: reason=missing credentials operation={}",
          context.getMethod().getName());
      throw new UnauthorizedException(MESSAGE, e);
    }
  }
}
