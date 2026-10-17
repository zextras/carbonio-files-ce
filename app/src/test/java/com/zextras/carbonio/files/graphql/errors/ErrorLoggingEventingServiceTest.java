// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.errors;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.security.AuthenticationCompletionException;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import jakarta.enterprise.context.ContextNotActiveException;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;

class ErrorLoggingEventingServiceTest {

  @Test
  void isAlwaysEnabled() {
    assertThat(new ErrorLoggingEventingService().getConfigKey()).isNull();
  }

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
  void unexpectedErrorsAreNotExpected() {
    assertThat(ErrorLoggingEventingService.isExpected(new NullPointerException())).isFalse();
    assertThat(ErrorLoggingEventingService.isExpected(new IllegalArgumentException("limit")))
        .isFalse();
    assertThat(ErrorLoggingEventingService.isExpected(new ContextNotActiveException())).isFalse();
    assertThat(
            ErrorLoggingEventingService.isExpected(new CompletionException(new RuntimeException())))
        .isFalse();
  }

  @Test
  void anExpectedCauseBehindAnUnexpectedExceptionIsStillUnexpected() {
    assertThat(
            ErrorLoggingEventingService.isExpected(
                new IllegalStateException(FilesGraphQLErrors.nodeNotFound("x"))))
        .isFalse();
  }
}
