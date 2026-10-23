// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql;

import com.zextras.carbonio.files.config.TransferPool;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.security.AuthenticationException;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import io.quarkus.security.identity.CurrentIdentityAssociation;
import io.quarkus.smallrye.graphql.runtime.SmallRyeGraphQLExecutionHandler;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.quarkus.vertx.http.runtime.security.QuarkusHttpUser;
import io.smallrye.graphql.execution.context.SmallRyeContextManager;
import io.smallrye.mutiny.CompositeException;
import io.vertx.core.http.HttpHeaders;
import io.vertx.ext.web.MIMEHeader;
import io.vertx.ext.web.ParsedHeaderValues;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Serves POST /graphql like a blocking REST endpoint: the request context lives until the operation
 * ends, also when the client disconnects (SmallRye's handler ends it as soon as the connection
 * closes, under the running resolvers).
 *
 * <p>copyNodes, cloneVersion and transferOwnership are handed from the event loop to the {@link
 * TransferPool} and run there entirely, so a copy that takes minutes costs one transfer thread and
 * no worker. That thread has no Vert.x context: the resolvers run on it directly, and the request
 * context stays active between them because SmallRye's context propagation restores it after each
 * one (see LongBlobOperationsTransferPoolTest).
 *
 * <p>Removable once every resolver, reads included, runs in its own transaction (Quarkus ≥ 3.38
 * adds them) and the long blob operations run as asynchronous jobs: {@code
 * quarkus.smallrye-graphql.nonblocking.enabled=false} then covers the threading, and only the empty
 * 500 for unexpected failures still needs a plain route failure handler.
 */
@ApplicationScoped
public class GraphQLExecutionRoute {

  // TODO: these blob operations stay synchronous and send a space every interval while they run, a
  // hack against the inactivity timeouts on the path (nginx, Envoy). Make them asynchronous jobs.
  static final Set<String> LONG_BLOB_OPERATIONS =
      Set.of("copyNodes", "cloneVersion", "transferOwnership");

  @Inject CurrentIdentityAssociation identityAssociation;
  @Inject CurrentVertxRequest currentVertxRequest;

  @ConfigProperty(name = "carbonio.files.graphql.heartbeat-interval", defaultValue = "10s")
  Duration heartbeatInterval;

  private ScheduledThreadPoolExecutor heartbeats;

  @PostConstruct
  void startHeartbeats() {
    if (heartbeatInterval.toMillis() <= 0) {
      throw new IllegalStateException(
          "carbonio.files.graphql.heartbeat-interval must be at least 1 ms: " + heartbeatInterval);
    }
    heartbeats =
        new ScheduledThreadPoolExecutor(
            1,
            runnable -> Thread.ofPlatform().name("graphql-heartbeat").daemon().unstarted(runnable));
    heartbeats.setRemoveOnCancelPolicy(true);
  }

  public void register(@Observes Router router, TransferPool transferPool) {
    ExecutionHandler handler =
        new ExecutionHandler(
            identityAssociation, currentVertxRequest, heartbeatInterval, heartbeats);
    router
        .post("/graphql")
        .order(-198)
        .handler(ctx -> handOffLongBlobOperation(handler, transferPool, ctx));
    router
        .post("/graphql")
        .order(-198)
        .blockingHandler(handler, false)
        .failureHandler(GraphQLExecutionRoute::endWithoutBody);
  }

  // Long blob operations run entirely on a transfer thread, never on a worker. A full pool answers
  // 503 before any byte, like the REST transfers.
  private static void handOffLongBlobOperation(
      ExecutionHandler handler, TransferPool transferPool, RoutingContext ctx) {
    Optional<LongBlobOperation> operation = handler.longBlobOperation(ctx);
    if (operation.isEmpty()) {
      ctx.next();
      return;
    }
    try {
      transferPool
          .get()
          .execute(
              () -> {
                try {
                  handler.executeLongBlobOperation(ctx, operation.get());
                } catch (RuntimeException failure) {
                  ctx.fail(failure);
                }
              });
    } catch (RejectedExecutionException rejected) {
      ctx.fail(rejected);
    }
  }

  private record LongBlobOperation(JsonObject input, String contentType, Charset charset) {}

  // The UI cannot decode QuarkusErrorHandler's JSON body; it still logs the failure.
  private static void endWithoutBody(RoutingContext ctx) {
    Throwable failure = ctx.failure();
    Throwable cause = unwrapCompletion(failure);
    boolean answeredByQuarkus =
        cause instanceof AuthenticationException
            || cause instanceof UnauthorizedException
            || cause instanceof ForbiddenException
            || cause instanceof RejectedExecutionException;
    if (answeredByQuarkus && cause != failure) {
      // QuarkusErrorHandler maps only the unwrapped exception to its 401/403/503.
      ctx.fail(cause);
      return;
    }
    if (failure != null && !answeredByQuarkus && !ctx.response().headWritten()) {
      ctx.response().setStatusCode(ctx.statusCode()).end();
    }
    ctx.next();
  }

  private static Throwable unwrapCompletion(Throwable failure) {
    Throwable cause = failure;
    while ((cause instanceof CompletionException && cause.getCause() != null)
        || cause instanceof CompositeException) {
      cause =
          cause instanceof CompositeException composite
              ? composite.getCauses().get(0)
              : cause.getCause();
    }
    return cause;
  }

  @PreDestroy
  void stopHeartbeats() {
    heartbeats.shutdownNow();
  }

  private static final class ExecutionHandler extends SmallRyeGraphQLExecutionHandler {

    private static final String DEFAULT_RESPONSE_CONTENT_TYPE =
        "application/graphql-response+json; charset=UTF-8";
    private static final Pattern NEWLINE_OR_TAB = Pattern.compile("[\\n\\t\\r]");

    private final CurrentIdentityAssociation identityAssociation;
    private final CurrentVertxRequest currentVertxRequest;
    private final Duration heartbeatInterval;
    private final ScheduledExecutorService heartbeats;

    ExecutionHandler(
        CurrentIdentityAssociation identityAssociation,
        CurrentVertxRequest currentVertxRequest,
        Duration heartbeatInterval,
        ScheduledExecutorService heartbeats) {
      super(false, false, true, identityAssociation, currentVertxRequest);
      this.identityAssociation = identityAssociation;
      this.currentVertxRequest = currentVertxRequest;
      this.heartbeatInterval = heartbeatInterval;
      this.heartbeats = heartbeats;
    }

    @Override
    public void handle(RoutingContext ctx) {
      run(ctx, () -> doHandle(ctx));
    }

    void executeLongBlobOperation(RoutingContext ctx, LongBlobOperation operation) {
      run(ctx, () -> executeWithHeartbeat(ctx, operation));
    }

    private void run(RoutingContext ctx, Runnable execution) {
      ManagedContext requestContext = Arc.container().requestContext();
      requestContext.activate();
      try {
        if (ctx.user() instanceof QuarkusHttpUser user) {
          identityAssociation.setIdentity(user.getSecurityIdentity());
        } else {
          identityAssociation.setIdentity(QuarkusHttpUser.getSecurityIdentity(ctx, null));
        }
        currentVertxRequest.setCurrent(ctx);
        execution.run();
      } finally {
        SmallRyeContextManager.clearCurrentSmallRyeContext();
        requestContext.terminate();
      }
    }

    // A false positive (the name in a comment or a string) only adds the heartbeat.
    Optional<LongBlobOperation> longBlobOperation(RoutingContext ctx) {
      String contentType = ctx.request().getHeader(HttpHeaders.CONTENT_TYPE);
      String body = ctx.body() == null ? null : ctx.body().asString();
      if (body == null
          || (contentType != null && contentType.startsWith("application/graphql"))
          || LONG_BLOB_OPERATIONS.stream().noneMatch(body::contains)) {
        return Optional.empty();
      }
      JsonObject input;
      try {
        input = inputToJsonObject(NEWLINE_OR_TAB.matcher(body).replaceAll(" "));
      } catch (RuntimeException e) {
        return Optional.empty();
      }
      if (!(input.get("query") instanceof JsonString)) {
        return Optional.empty();
      }
      String responseContentType = responseContentType(ctx);
      if (responseContentType == null) {
        return Optional.empty();
      }
      return supportedCharset(charsetOf(responseContentType))
          .map(charset -> new LongBlobOperation(input, responseContentType, charset));
    }

    private void executeWithHeartbeat(RoutingContext ctx, LongBlobOperation operation) {
      ctx.response().headers().set(HttpHeaders.CONTENT_TYPE, operation.contentType());
      HeartbeatResponseWriter writer = new HeartbeatResponseWriter(ctx, operation.charset());
      writer.start(heartbeatInterval, heartbeats);
      try {
        getExecutionService().executeAsync(operation.input(), getMetaData(ctx), writer);
      } catch (RuntimeException e) {
        writer.fail(e);
      }
    }

    // SmallRyeGraphQLExecutionHandler's Accept negotiation; null where it answers 406.
    private static String responseContentType(RoutingContext ctx) {
      ParsedHeaderValues parsed = ctx.parsedHeaders();
      if (parsed == null || parsed.accept() == null || parsed.accept().isEmpty()) {
        return DEFAULT_RESPONSE_CONTENT_TYPE;
      }
      for (MIMEHeader accept : parsed.accept()) {
        if (isGraphQLJson(accept.rawValue())) {
          return accept.rawValue();
        }
        if (accept.rawValue().startsWith("*/*")) {
          return DEFAULT_RESPONSE_CONTENT_TYPE;
        }
      }
      String accept = ctx.request().getHeader(HttpHeaders.ACCEPT);
      if (accept == null || accept.isEmpty()) {
        return DEFAULT_RESPONSE_CONTENT_TYPE;
      }
      return isGraphQLJson(accept) ? accept : null;
    }

    private static boolean isGraphQLJson(String mimeType) {
      return mimeType.startsWith("application/json")
          || mimeType.startsWith("application/graphql-response+json")
          || mimeType.startsWith("application/graphql+json");
    }

    // An unsupported charset keeps SmallRye's own handling.
    private static Optional<Charset> supportedCharset(String name) {
      try {
        return Optional.of(Charset.forName(name));
      } catch (IllegalArgumentException e) {
        return Optional.empty();
      }
    }

    private static String charsetOf(String mimeType) {
      int start = mimeType.indexOf("charset=");
      if (start == -1) {
        return "UTF-8";
      }
      start += "charset=".length();
      int end = start;
      while (end < mimeType.length()
          && mimeType.charAt(end) != ';'
          && !Character.isWhitespace(mimeType.charAt(end))) {
        end++;
      }
      return end == start ? "UTF-8" : mimeType.substring(start, end);
    }
  }
}
