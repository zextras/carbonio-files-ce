// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.support;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.CurrentIdentityAssociation;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.graphql.execution.context.SmallRyeContext;
import io.smallrye.graphql.execution.context.SmallRyeContextManager;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import java.util.concurrent.ExecutionException;
import org.eclipse.microprofile.graphql.Mutation;

/**
 * Runs every GraphQL mutation to completion, as the legacy stack did: SmallRye terminates the HTTP
 * request context as soon as the connection closes (client gone, proxy timeout), which would stop a
 * mutation half-way. The authorization checks run first on the request; the mutation body then runs
 * on its own thread, in its own request context, with the same identity and GraphQL context.
 */
@Authenticated
@Interceptor
@Priority(Interceptor.Priority.APPLICATION)
public class MutationCompletionInterceptor {

  private static final ThreadLocal<Boolean> DETACHED = new ThreadLocal<>();

  @Inject MutationExecutor executor;
  @Inject CurrentIdentityAssociation identityAssociation;

  @AroundInvoke
  Object runToCompletion(InvocationContext invocation) throws Exception {
    if (!invocation.getMethod().isAnnotationPresent(Mutation.class)
        || Boolean.TRUE.equals(DETACHED.get())) {
      return invocation.proceed();
    }
    SecurityIdentity identity = identityAssociation.getIdentity();
    SmallRyeContext graphQLContext = SmallRyeContextManager.getCurrentSmallRyeContext();
    var completion =
        executor.submit(
            () -> {
              ManagedContext requestContext = Arc.container().requestContext();
              requestContext.activate();
              DETACHED.set(true);
              SmallRyeContextManager.restore(graphQLContext);
              try {
                Arc.container()
                    .instance(CurrentIdentityAssociation.class)
                    .get()
                    .setIdentity(identity);
                return invocation.proceed();
              } finally {
                SmallRyeContextManager.clearCurrentSmallRyeContext();
                DETACHED.remove();
                requestContext.terminate();
              }
            });
    try {
      return completion.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw e;
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof Exception exception) {
        throw exception;
      }
      if (cause instanceof Error error) {
        throw error;
      }
      throw e;
    }
  }
}
