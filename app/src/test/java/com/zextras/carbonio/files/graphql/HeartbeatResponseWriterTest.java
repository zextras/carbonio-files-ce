// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import graphql.ExecutionResult;
import io.smallrye.graphql.execution.ExecutionResponse;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class HeartbeatResponseWriterTest {

  private static final String RESULT = "{\"data\":{\"copyNodes\":[]}}";

  private RoutingContext ctx;
  private HttpServerResponse response;
  private ScheduledFuture<?> future;
  private Runnable beat;
  private HeartbeatResponseWriter writer;

  @BeforeEach
  void setUp() {
    ctx = mock(RoutingContext.class);
    response = mock(HttpServerResponse.class, RETURNS_SELF);
    when(ctx.response()).thenReturn(response);
    ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    future = mock(ScheduledFuture.class);
    ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
    when(scheduler.scheduleAtFixedRate(
            task.capture(), anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS)))
        .thenAnswer(invocation -> future);
    writer = new HeartbeatResponseWriter(ctx, StandardCharsets.UTF_8);
    writer.start(Duration.ofSeconds(10), scheduler);
    beat = task.getValue();
  }

  @Test
  void fastResult_isAnsweredLikeSmallRye() {
    writer.write(result(RESULT));

    verify(response).setStatusCode(200);
    verify(response).setStatusMessage("OK");
    verify(response).end(Buffer.buffer(RESULT));
    verify(response, never()).setChunked(true);
    verify(future).cancel(false);
  }

  @Test
  void slowResult_getsTheHeadAtTheFirstBeatThenSpacesThenTheResult() {
    beat.run();
    beat.run();
    writer.write(result(RESULT));

    verify(response).putHeader("X-Accel-Buffering", "no");
    verify(response).setStatusCode(200);
    verify(response).setChunked(true);
    ArgumentCaptor<Buffer> written = ArgumentCaptor.forClass(Buffer.class);
    verify(response, org.mockito.Mockito.times(2)).write(written.capture());
    assertThat(written.getAllValues()).allMatch(chunk -> chunk.toString().equals(" "));
    verify(response).end(Buffer.buffer(RESULT));
    verify(response, never()).setStatusMessage(anyString());
  }

  @Test
  void failureAfterTheHead_isAGraphQLErrorInTheBody() {
    beat.run();
    writer.fail(new IllegalStateException("boom"));

    ArgumentCaptor<Buffer> body = ArgumentCaptor.forClass(Buffer.class);
    verify(response).end(body.capture());
    assertThat(body.getValue().toString()).contains("\"errors\"").contains("System error");
    verify(ctx, never()).fail(any(Throwable.class));
  }

  @Test
  void failureBeforeTheHead_goesToTheRouteFailureHandler() {
    IllegalStateException failure = new IllegalStateException("boom");
    writer.fail(failure);

    verify(ctx).fail(failure);
    verify(response, never()).end(any(Buffer.class));
  }

  @Test
  void unserializableResultAfterTheHead_endsWithAnError() {
    beat.run();
    ExecutionResponse broken = mock(ExecutionResponse.class);
    when(broken.getExecutionResultAsString()).thenThrow(new IllegalStateException("json"));

    writer.write(broken);

    ArgumentCaptor<Buffer> body = ArgumentCaptor.forClass(Buffer.class);
    verify(response).end(body.capture());
    assertThat(body.getValue().toString()).contains("System error");
  }

  @Test
  void beatOnAClosedResponse_stopsTheHeartbeatWithoutWriting() {
    when(response.closed()).thenReturn(true);

    beat.run();

    verify(future).cancel(false);
    verify(response, never()).setStatusCode(anyInt());
    verify(response, never()).write(any(Buffer.class));
  }

  @Test
  void beatAfterTheResult_doesNothing() {
    writer.write(result(RESULT));
    beat.run();

    verify(response, never()).setChunked(true);
    verify(response, never()).write(any(Buffer.class));
  }

  private static ExecutionResponse result(String json) {
    ExecutionResponse executionResponse = mock(ExecutionResponse.class);
    ExecutionResult executionResult = mock(ExecutionResult.class);
    when(executionResult.isDataPresent()).thenReturn(true);
    when(executionResult.getErrors()).thenReturn(List.of());
    when(executionResponse.getExecutionResult()).thenReturn(executionResult);
    when(executionResponse.getExecutionResultAsString()).thenReturn(json);
    return executionResponse;
  }
}
