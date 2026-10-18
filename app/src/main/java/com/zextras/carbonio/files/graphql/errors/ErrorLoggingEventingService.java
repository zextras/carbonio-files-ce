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
import io.smallrye.graphql.execution.datafetcher.DataFetcherException;
import io.smallrye.graphql.execution.error.ExceptionHandler;
import io.smallrye.graphql.spi.EventingService;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ErrorLoggingEventingService implements EventingService {

  private static final Logger logger = LoggerFactory.getLogger(ErrorLoggingEventingService.class);

  private static final String LOGGED_ERRORS_KEY = "files.logged.errors";

  @Override
  public String getConfigKey() {
    return null;
  }

  @Override
  public GraphQL.Builder beforeGraphQLBuild(GraphQL.Builder builder) {
    return builder.defaultDataFetcherExceptionHandler(new LoggingExceptionHandler());
  }

  static boolean isExpected(Throwable throwable) {
    Throwable cause = unwrap(throwable);
    return cause instanceof FilesGraphQLException
        || cause instanceof UnauthorizedException
        || cause instanceof ForbiddenException
        || cause instanceof AuthenticationFailedException
        || cause instanceof AuthenticationCompletionException;
  }

  static Throwable unwrap(Throwable throwable) {
    Throwable current = throwable;
    while (isWrapper(current) && current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }

  private static Throwable rootCause(Throwable throwable) {
    Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    Throwable current = throwable;
    while (current.getCause() != null && visited.add(current)) {
      current = current.getCause();
    }
    return current;
  }

  private static String signature(Throwable throwable) {
    Throwable root = rootCause(throwable);
    return root.getClass().getName() + ": " + root.getMessage();
  }

  private static boolean isWrapper(Throwable throwable) {
    return throwable instanceof CompletionException
        || throwable instanceof ExecutionException
        || throwable instanceof InvocationTargetException
        || throwable instanceof UndeclaredThrowableException
        || throwable instanceof DataFetcherException;
  }

  static final class RequestErrorLog {

    private static final int MAX_LOGGED_SIGNATURES = 5;

    private final Logger logger;
    private final Set<String> signatures = new HashSet<>();
    private boolean capped;

    RequestErrorLog(Logger logger) {
      this.logger = logger;
    }

    synchronized void record(String operationName, String path, Throwable throwable) {
      if (isExpected(throwable)) {
        logger.debug(
            "GraphQL operation '{}' failed at '{}': {}", operationName, path, throwable.toString());
        return;
      }
      // A failed batch fails every key with the same root cause, possibly re-wrapped per key.
      if (capped || !signatures.add(signature(throwable))) {
        return;
      }
      if (signatures.size() > MAX_LOGGED_SIGNATURES) {
        capped = true;
        logger.warn(
            "Further unexpected errors in GraphQL operation '{}' are not logged", operationName);
        return;
      }
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
        RequestErrorLog requestLog =
            parameters
                .getDataFetchingEnvironment()
                .getGraphQlContext()
                .computeIfAbsent(LOGGED_ERRORS_KEY, key -> new RequestErrorLog(logger));
        String operationName =
            parameters.getDataFetchingEnvironment().getOperationDefinition().getName();
        requestLog.record(
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
