// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql;

import graphql.ErrorType;
import graphql.ExecutionResult;
import io.smallrye.graphql.execution.ExecutionResponse;
import io.smallrye.graphql.execution.ExecutionResponseWriter;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes a GraphQL response like SmallRye's own writer, except that an operation still running
 * after {@code interval} gets its 200 head at once and then one space per interval until the JSON
 * result (leading whitespace is valid JSON), so no inactivity timeout on the path cuts it.
 */
final class HeartbeatResponseWriter implements ExecutionResponseWriter {

  private static final Logger logger = LoggerFactory.getLogger(HeartbeatResponseWriter.class);
  private static final String SYSTEM_ERROR =
      "{\"errors\":[{\"message\":\"System error\"}],\"data\":null}";

  private final RoutingContext ctx;
  private final Charset charset;
  private ScheduledFuture<?> beats;
  private boolean streaming;
  private boolean done;

  HeartbeatResponseWriter(RoutingContext ctx, Charset charset) {
    this.ctx = ctx;
    this.charset = charset;
  }

  synchronized void start(Duration interval, ScheduledExecutorService scheduler) {
    long millis = interval.toMillis();
    beats = scheduler.scheduleAtFixedRate(this::beat, millis, millis, TimeUnit.MILLISECONDS);
  }

  @Override
  public synchronized void write(ExecutionResponse executionResponse) {
    stop();
    HttpServerResponse response = ctx.response();
    Buffer body;
    try {
      body = encode(executionResponse.getExecutionResultAsString());
    } catch (RuntimeException e) {
      fail(e);
      return;
    }
    if (streaming) {
      response.end(body);
    } else if (aborted(executionResponse.getExecutionResult())) {
      response.setStatusCode(500).end();
    } else {
      try {
        response.setStatusCode(200).setStatusMessage("OK").end(body);
      } catch (IllegalStateException e) {
        logger.debug("Cannot write the GraphQL response", e);
      }
    }
  }

  @Override
  public synchronized void fail(Throwable failure) {
    stop();
    if (streaming) {
      logger.error("GraphQL execution failed after the response started", failure);
      ctx.response().end(encode(SYSTEM_ERROR));
    } else {
      ctx.fail(failure);
    }
  }

  private synchronized void beat() {
    try {
      HttpServerResponse response = ctx.response();
      if (done || response.ended() || response.closed()) {
        stop();
        return;
      }
      if (!streaming) {
        // Without it nginx holds the compressed spaces until the end and the browser side is
        // silent.
        response.putHeader("X-Accel-Buffering", "no").setStatusCode(200).setChunked(true);
        streaming = true;
      }
      response.write(encode(" "));
    } catch (RuntimeException e) {
      logger.warn("GraphQL heartbeat stopped", e);
      stop();
    }
  }

  private void stop() {
    done = true;
    if (beats != null) {
      beats.cancel(false);
    }
  }

  private Buffer encode(String text) {
    return Buffer.buffer(text.getBytes(charset));
  }

  // SmallRye answers an empty 500 when an aborted execution still returned data.
  private static boolean aborted(ExecutionResult result) {
    return result.isDataPresent()
        && result.getErrors().stream()
            .anyMatch(error -> ErrorType.ExecutionAborted.equals(error.getErrorType()));
  }
}
