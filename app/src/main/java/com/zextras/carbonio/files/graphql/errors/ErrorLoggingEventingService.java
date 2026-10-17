// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.errors;

import graphql.GraphQL;
import graphql.execution.DataFetcherExceptionHandler;
import graphql.execution.DataFetcherExceptionHandlerParameters;
import graphql.execution.DataFetcherExceptionHandlerResult;
import io.quarkus.security.AuthenticationCompletionException;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import io.smallrye.graphql.execution.error.ExceptionHandler;
import io.smallrye.graphql.spi.EventingService;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logs every failed data fetch exactly once. It decorates SmallRye's own exception handler instead
 * of using {@code errorDataFetch}, which is not fired for batch ({@code @Source List}) resolvers;
 * the delegate still builds the client-visible error, so responses are unchanged.
 */
public class ErrorLoggingEventingService implements EventingService {

  private static final Logger logger = LoggerFactory.getLogger(ErrorLoggingEventingService.class);

  @Override
  public String getConfigKey() {
    return null;
  }

  @Override
  public GraphQL.Builder beforeGraphQLBuild(GraphQL.Builder builder) {
    return builder.defaultDataFetcherExceptionHandler(new LoggingExceptionHandler());
  }

  static boolean isExpected(Throwable throwable) {
    Throwable current = throwable;
    while (current != null) {
      if (current instanceof FilesGraphQLException
          || current instanceof UnauthorizedException
          || current instanceof ForbiddenException
          || current instanceof AuthenticationFailedException
          || current instanceof AuthenticationCompletionException) {
        return true;
      }
      if (!isWrapper(current)) {
        return false;
      }
      current = current.getCause();
    }
    return false;
  }

  private static boolean isWrapper(Throwable throwable) {
    return throwable instanceof CompletionException
        || throwable instanceof ExecutionException
        || throwable instanceof InvocationTargetException
        || throwable instanceof UndeclaredThrowableException;
  }

  static void log(String operationName, String path, Throwable throwable) {
    if (isExpected(throwable)) {
      logger.debug(
          "GraphQL operation '{}' failed at '{}': {}", operationName, path, throwable.toString());
    } else {
      logger.error(
          "Unexpected error in GraphQL operation '{}' at '{}'", operationName, path, throwable);
    }
  }

  private static final class LoggingExceptionHandler implements DataFetcherExceptionHandler {

    private final DataFetcherExceptionHandler delegate = new ExceptionHandler();

    @Override
    public CompletableFuture<DataFetcherExceptionHandlerResult> handleException(
        DataFetcherExceptionHandlerParameters parameters) {
      try {
        String operationName =
            parameters.getDataFetchingEnvironment().getOperationDefinition().getName();
        log(
            operationName == null ? "anonymous" : operationName,
            parameters.getPath().toString(),
            parameters.getException());
      } catch (RuntimeException e) {
        logger.warn("Unable to log a GraphQL data fetch error", e);
      }
      return delegate.handleException(parameters);
    }
  }
}
