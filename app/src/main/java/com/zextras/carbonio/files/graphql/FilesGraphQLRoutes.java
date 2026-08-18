// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zextras.carbonio.files.Constants.GraphQL.Context;
import com.zextras.carbonio.files.Constants.GraphQL.DataLoaders;
import com.zextras.carbonio.files.dal.dao.UserMyself;
import com.zextras.carbonio.files.graphql.dataloaders.NodeBatchLoader;
import com.zextras.carbonio.files.graphql.dataloaders.ShareBatchLoader;
import com.zextras.carbonio.files.graphql.dataloaders.UserBatchLoader;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.GraphQLException;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.vertx.core.http.HttpHeaders;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.Map;
import org.dataloader.DataLoaderFactory;
import org.dataloader.DataLoaderOptions;
import org.dataloader.DataLoaderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registers the two GraphQL HTTP endpoints on the Vert.x router and executes the graphql-java
 * engine for each request. This is the Quarkus replacement for the legacy Netty {@code
 * GraphQLController} and {@code PublicGraphQLController}.
 *
 * <ul>
 *   <li>{@code POST /graphql} — authenticated (guarded by {@link FilesAuthenticationFilter}); the
 *       requester/cookies stashed by the filter are copied into the graphql-java context, and a
 *       FRESH per-request {@link DataLoaderRegistry} is attached (data loaders MUST be
 *       per-request).
 *   <li>{@code POST /public/graphql} — unauthenticated; no requester context and no data loaders.
 * </ul>
 *
 * <p>Execution is SYNCHRONOUS ({@link GraphQL#execute(ExecutionInput)}) and runs on a Vert.x
 * blocking worker thread where the CDI request context is active. Together with the synchronous
 * batch loaders (see {@link NodeBatchLoader}/{@link ShareBatchLoader}) this keeps every data-access
 * hop on a request-scoped thread, so the request-scoped {@code EntityManager} is always available.
 */
@ApplicationScoped
public class FilesGraphQLRoutes {

  private static final Logger logger = LoggerFactory.getLogger(FilesGraphQLRoutes.class);
  private static final String APPLICATION_JSON = "application/json";
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  /**
   * Legacy parity: {@code core/.../HttpRoutingHandler#channelRead0} installed {@code new
   * HttpObjectAggregator(256 * 1024)} ahead of BOTH GraphQL controllers (lines ~99-108 for the
   * authenticated route, ~199-207 for the public one). The Quarkus port kept the Vert.x {@code
   * BodyHandler} but never set a body limit on it (Vert.x's default is unlimited), silently
   * dropping the cap. {@code BodyHandler} tracks the running byte count as data actually arrives
   * (not just a Content-Length pre-check), so this also holds for a chunked request with no
   * Content-Length header at all.
   */
  private static final long GRAPHQL_MAX_BODY_BYTES = 256L * 1024;

  private final GraphQL graphQL;
  private final GraphQL publicGraphQL;
  private final NodeBatchLoader nodeBatchLoader;
  private final ShareBatchLoader shareBatchLoader;
  private final UserBatchLoader userBatchLoader;

  @Inject
  public FilesGraphQLRoutes(
      GraphQLProvider graphQLProvider,
      PublicGraphQLProvider publicGraphQLProvider,
      NodeBatchLoader nodeBatchLoader,
      ShareBatchLoader shareBatchLoader,
      UserBatchLoader userBatchLoader) {
    this.graphQL = graphQLProvider.getGraphQL();
    this.publicGraphQL = publicGraphQLProvider.getGraphQL();
    this.nodeBatchLoader = nodeBatchLoader;
    this.shareBatchLoader = shareBatchLoader;
    this.userBatchLoader = userBatchLoader;
  }

  /** Registers the POST routes when Quarkus publishes the {@link Router} CDI event at startup. */
  public void routes(@Observes Router router) {
    router
        .post("/graphql")
        .handler(BodyHandler.create().setBodyLimit(GRAPHQL_MAX_BODY_BYTES))
        .blockingHandler(this::handleAuthenticatedRequest);
    router
        .post("/public/graphql")
        .handler(BodyHandler.create().setBodyLimit(GRAPHQL_MAX_BODY_BYTES))
        .blockingHandler(this::handlePublicRequest);
  }

  /**
   * Handles an authenticated GraphQL request. The requester and cookies were validated and stashed
   * by {@link FilesAuthenticationFilter}; they are copied into the graphql-java context together
   * with a fresh per-request data-loader registry.
   */
  private void handleAuthenticatedRequest(RoutingContext ctx) {
    // Custom Vert.x routes registered via @Observes Router do NOT get Quarkus' automatic CDI
    // request-context activation, so the request-scoped EntityManager would be unavailable.
    // Activate
    // it explicitly for the whole (fully synchronous) execution on this blocking worker thread.
    ManagedContext requestContext = Arc.container().requestContext();
    boolean activatedHere = false;
    if (!requestContext.isActive()) {
      requestContext.activate();
      activatedHere = true;
    }
    try {
      Map<String, Object> graphQLContext = new HashMap<>();

      UserMyself requester = ctx.get(Context.REQUESTER);
      graphQLContext.put(Context.REQUESTER, requester);
      graphQLContext.put(Context.COOKIES, ctx.<String>get(Context.COOKIES));

      GraphQLRequest request = parseRequest(ctx);
      ExecutionInput input =
          ExecutionInput.newExecutionInput()
              .query(request.getRequest())
              .variables(request.getVariables())
              .operationName(request.getOperationName().orElse(""))
              .graphQLContext(graphQLContext)
              .dataLoaderRegistry(buildDataLoaderRegistry())
              .build();

      // Synchronous execution on the request-scoped blocking worker thread (see class javadoc).
      ExecutionResult executionResult = graphQL.execute(input);
      writeOk(ctx, executionResult);
    } catch (GraphQLRequest.InvalidPayloadRequestError | GraphQLException exception) {
      writeBadRequest(ctx);
    } catch (Exception exception) {
      logger.error(
          "GraphQLRoutes catches an exception handling an authenticated request", exception);
      writeBadRequest(ctx);
    } finally {
      if (activatedHere) {
        requestContext.terminate();
      }
    }
  }

  /** Handles an unauthenticated public GraphQL request: no requester context, no data loaders. */
  private void handlePublicRequest(RoutingContext ctx) {
    ManagedContext requestContext = Arc.container().requestContext();
    boolean activatedHere = false;
    if (!requestContext.isActive()) {
      requestContext.activate();
      activatedHere = true;
    }
    try {
      GraphQLRequest request = parseRequest(ctx);
      ExecutionInput input =
          ExecutionInput.newExecutionInput()
              .query(request.getRequest())
              .variables(request.getVariables())
              .operationName(request.getOperationName().orElse(""))
              .build();

      ExecutionResult executionResult = publicGraphQL.execute(input);
      writeOk(ctx, executionResult);
    } catch (GraphQLRequest.InvalidPayloadRequestError | GraphQLException exception) {
      writeBadRequest(ctx);
    } catch (Exception exception) {
      logger.error("GraphQLRoutes catches an exception handling a public request", exception);
      writeBadRequest(ctx);
    } finally {
      if (activatedHere) {
        requestContext.terminate();
      }
    }
  }

  private GraphQLRequest parseRequest(RoutingContext ctx)
      throws GraphQLRequest.InvalidPayloadRequestError {
    String body = ctx.body() == null ? null : ctx.body().asString();
    if (body == null || body.isEmpty()) {
      throw new GraphQLRequest.InvalidPayloadRequestError(
          "The payload of a GraphQL request cannot be empty");
    }
    try {
      return GraphQLRequest.buildFromPayload(body);
    } catch (GraphQLRequest.InvalidPayloadRequestError error) {
      throw error;
    } catch (Exception exception) {
      throw new GraphQLRequest.InvalidPayloadRequestError(
          "The payload of a GraphQL request cannot be parsed");
    }
  }

  private void writeOk(RoutingContext ctx, ExecutionResult executionResult) throws Exception {
    String bodyResponse = OBJECT_MAPPER.writeValueAsString(executionResult.toSpecification());
    ctx.response()
        .putHeader(HttpHeaders.CONTENT_TYPE, APPLICATION_JSON)
        .setStatusCode(200)
        .end(bodyResponse);
  }

  private void writeBadRequest(RoutingContext ctx) {
    ctx.response()
        .putHeader(HttpHeaders.CONTENT_TYPE, APPLICATION_JSON)
        .setStatusCode(400)
        .end("{\"error_message\":\"Something went wrong\"}");
  }

  /**
   * Creates a fresh {@link DataLoaderRegistry} for a single request and registers the node/share
   * batch loaders under the same keys the data-fetchers look them up by ({@code NodeBatchLoader} /
   * {@code ShareBatchLoader}). Data loaders MUST be created per execution request.
   */
  private DataLoaderRegistry buildDataLoaderRegistry() {
    DataLoaderRegistry registry = new DataLoaderRegistry();
    registry.register(
        DataLoaders.NODE_BATCH_LOADER, DataLoaderFactory.newDataLoaderWithTry(nodeBatchLoader));
    registry.register(
        DataLoaders.SHARE_BATCH_LOADER, DataLoaderFactory.newDataLoader(shareBatchLoader));
    // maxBatchSize(100): the user-management POST /internal/users endpoint rejects lists > 100, so
    // cap each batch at 100 ids (DataLoader splits larger sets into 100-id sub-batches).
    registry.register(
        DataLoaders.USER_BATCH_LOADER,
        DataLoaderFactory.newMappedDataLoader(
            userBatchLoader, DataLoaderOptions.newOptions().setMaxBatchSize(100).build()));
    return registry;
  }
}
