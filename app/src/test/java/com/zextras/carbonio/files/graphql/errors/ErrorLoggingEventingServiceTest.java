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

import io.quarkus.security.AuthenticationCompletionException;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import java.lang.reflect.InvocationTargetException;
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
}
