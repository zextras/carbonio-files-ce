// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.errors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.zextras.carbonio.files.dal.locks.NodeBusyException;
import graphql.GraphQLContext;
import graphql.GraphQLError;
import graphql.Scalars;
import graphql.execution.DataFetcherExceptionHandlerParameters;
import graphql.execution.DataFetcherExceptionHandlerResult;
import graphql.execution.ExecutionStepInfo;
import graphql.execution.MergedField;
import graphql.execution.ResultPath;
import graphql.language.Field;
import graphql.language.OperationDefinition;
import graphql.schema.DataFetchingEnvironment;
import io.quarkus.security.AuthenticationCompletionException;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import io.smallrye.graphql.execution.error.GraphQLExceptionWhileDataFetching;
import java.lang.reflect.InvocationTargetException;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;

class ErrorLoggingEventingServiceTest {

  @Test
  void businessAndSecurityErrorsAreExpected() {
    assertThat(ErrorLoggingEventingService.isExpected(FilesGraphQLErrors.nodeNotFound("x")))
        .isTrue();
    assertThat(ErrorLoggingEventingService.isExpected(new UnauthorizedException("no"))).isTrue();
    assertThat(ErrorLoggingEventingService.isExpected(new ForbiddenException("no"))).isTrue();
    assertThat(ErrorLoggingEventingService.isExpected(new AuthenticationFailedException("no")))
        .isTrue();
    assertThat(
            ErrorLoggingEventingService.isExpected(
                new AuthenticationCompletionException("no", null)))
        .isTrue();
  }

  @Test
  void expectedErrorsStayExpectedThroughWrappers() {
    Throwable wrapped =
        new CompletionException(
            new ExecutionException(new InvocationTargetException(new UnauthorizedException("no"))));

    assertThat(ErrorLoggingEventingService.isExpected(wrapped)).isTrue();
  }

  @Test
  void anExpectedCauseBehindAnUnexpectedExceptionIsStillUnexpected() {
    assertThat(
            ErrorLoggingEventingService.isExpected(
                new IllegalStateException(FilesGraphQLErrors.nodeNotFound("x"))))
        .isFalse();
  }

  private static void record(
      ErrorLoggingEventingService.RequestErrorLog state, Throwable throwable, String path) {
    state.record("Op", path, throwable);
  }

  @Test
  void theSameSignatureIsLoggedOncePerRequest() {
    Logger logger = mock(Logger.class);
    var state = new ErrorLoggingEventingService.RequestErrorLog(logger);

    for (int i = 0; i < 200; i++) {
      record(state, new IllegalArgumentException("limit"), "/a" + i);
    }

    verify(logger, times(1)).error(anyString(), eq("Op"), eq("/a0"), any(Throwable.class));
    verify(logger, never()).warn(anyString(), any(Object.class));
  }

  @Test
  void atMostFiveStackTracesAreLoggedThenOneWarnAndNothingElse() {
    Logger logger = mock(Logger.class);
    var state = new ErrorLoggingEventingService.RequestErrorLog(logger);

    for (int i = 0; i < 7; i++) {
      record(state, new IllegalStateException("distinct-" + i), "/p" + i);
    }
    record(state, new IllegalStateException("distinct-0"), "/again");
    record(state, new IllegalStateException("distinct-9"), "/more");

    verify(logger, times(5)).error(anyString(), any(), any(), any(Throwable.class));
    verify(logger, times(1))
        .warn("Further unexpected errors in GraphQL operation '{}' are not logged", "Op");
    verifyNoMoreInteractions(logger);
  }

  @Test
  void aLockTimeoutIsLoggedAsOneWarningWithoutStackTrace() {
    Logger logger = mock(Logger.class);
    var state = new ErrorLoggingEventingService.RequestErrorLog(logger);

    record(state, new RuntimeException(new SQLException("timeout", "55P03")), "/m");

    verify(logger, times(1))
        .warn(anyString(), eq("Op"), eq("/m"), eq("java.sql.SQLException: timeout"));
    verify(logger, never()).error(anyString(), any(), any(), any(Throwable.class));
  }

  @Test
  @Timeout(5)
  void aCauseCycleLongerThanOneTerminates() {
    Logger logger = mock(Logger.class);
    var state = new ErrorLoggingEventingService.RequestErrorLog(logger);
    RuntimeException a = new RuntimeException("a");
    RuntimeException b = new RuntimeException("b", a);
    a.initCause(b);

    record(state, a, "/a");
    record(state, a, "/b");

    verify(logger, times(1)).error(anyString(), any(), any(), any(Throwable.class));
  }

  @Test
  void expectedErrorsAreLoggedAtDebugAndNeverCountTowardTheCap() {
    Logger logger = mock(Logger.class);
    var state = new ErrorLoggingEventingService.RequestErrorLog(logger);

    for (int i = 0; i < 10; i++) {
      record(state, FilesGraphQLErrors.nodeNotFound("x" + i), "/e" + i);
    }
    record(state, new IllegalStateException("real"), "/r");

    verify(logger, times(10)).debug(anyString(), any(), any(), any());
    verify(logger, times(1)).error(anyString(), any(), any(), any(Throwable.class));
    verify(logger, never()).warn(anyString(), any(Object.class));
  }

  private static List<GraphQLError> handled(Throwable failure) throws Exception {
    DataFetchingEnvironment environment = mock(DataFetchingEnvironment.class);
    org.mockito.Mockito.when(environment.getGraphQlContext())
        .thenReturn(GraphQLContext.newContext().build());
    org.mockito.Mockito.when(environment.getMergedField())
        .thenReturn(MergedField.newMergedField(Field.newField("f").build()).build());
    org.mockito.Mockito.when(environment.getExecutionStepInfo())
        .thenReturn(
            ExecutionStepInfo.newExecutionStepInfo()
                .type(Scalars.GraphQLString)
                .path(ResultPath.rootPath().segment("f"))
                .build());
    org.mockito.Mockito.when(environment.getOperationDefinition())
        .thenReturn(OperationDefinition.newOperationDefinition().name("Op").build());
    DataFetcherExceptionHandlerParameters parameters =
        DataFetcherExceptionHandlerParameters.newExceptionParameters()
            .dataFetchingEnvironment(environment)
            .exception(failure)
            .build();
    DataFetcherExceptionHandlerResult result =
        new ErrorLoggingEventingService.LoggingExceptionHandler().handleException(parameters).get();
    return result.getErrors();
  }

  private static String errorCodeOf(GraphQLError error) {
    Throwable cause = ((GraphQLExceptionWhileDataFetching) error).getException();
    return ((FilesGraphQLException) cause).getErrorCode().name();
  }

  @Test
  void aNodeBusyExceptionIsAnsweredWithResourceBusyAndTheNodeId() throws Exception {
    List<GraphQLError> errors = handled(new RuntimeException(new NodeBusyException("n1")));

    assertThat(errors).hasSize(1);
    assertThat(errorCodeOf(errors.get(0))).isEqualTo("RESOURCE_BUSY");
    assertThat(errors.get(0).getMessage()).contains("n1");
    assertThat(errors.get(0).getExtensions()).containsKey("nodeId");
  }

  @Test
  void aLockTimeoutOrDeadlockIsAnsweredWithResourceBusyWithoutANodeId() throws Exception {
    for (String state : new String[] {"55P03", "40P01"}) {
      SQLException sql = new SQLException("lock", state);
      List<GraphQLError> errors = handled(new RuntimeException("tx", new RuntimeException(sql)));

      assertThat(errors).hasSize(1);
      assertThat(errorCodeOf(errors.get(0))).isEqualTo("RESOURCE_BUSY");
      assertThat(errors.get(0).getExtensions()).doesNotContainKey("nodeId");
    }
  }

  @Test
  void aLockSqlStateOnTheNextExceptionIsAnsweredWithResourceBusy() throws Exception {
    SQLException first = new SQLException("batch", "23505");
    first.setNextException(new SQLException("lock", "55P03"));

    assertThat(errorCodeOf(handled(new RuntimeException(first)).get(0))).isEqualTo("RESOURCE_BUSY");
  }
}
