// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.zextras.carbonio.files.Files;
import com.zextras.carbonio.files.Files.GraphQL.DataLoaders;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.graphql.GraphQLProvider;
import com.zextras.carbonio.files.graphql.GraphQLRequest;
import com.zextras.carbonio.files.graphql.dataloaders.NodeBatchLoader;
import com.zextras.carbonio.files.graphql.dataloaders.ShareBatchLoader;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.GraphQLException;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.AttributeKey;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.dataloader.DataLoader;
import org.dataloader.DataLoaderFactory;
import org.dataloader.DataLoaderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class is the entry-point of the Files GraphQL API. The main purpose is to:
 * <ul>
 *  <li>Receive a json request</li>
 *  <li>Parse the request creating a {@link GraphQLRequest}</li>
 *  <li>Execute the request via {@link GraphQL}</li>
 *  <li>Return the response with the JSON data requested or an error</li>
 * </ul>
 */

@ChannelHandler.Sharable
@Singleton
public class GraphQLController extends SimpleChannelInboundHandler<FullHttpRequest> {

  private static final Logger logger = LoggerFactory.getLogger(GraphQLController.class);

  /**
   * This ChannelFutureListener aims to close correctly the channel only after the promise has
   * finished successfully! This listener must be used in every netty response.
   */
  private final static ChannelFutureListener sNettyChannelFutureClose = (promise) -> {
    if (!promise.isSuccess()) {
      logger.error("Failed to send the HTTP response, cause by: " + promise.cause().toString());
    }
    promise.channel().close();
  };

  private final GraphQL         graphQL;
  private final NodeBatchLoader  nodeBatchLoader;
  private final ShareBatchLoader shareBatchLoader;

  @Inject
  public GraphQLController(
    GraphQLProvider graphQLProvider,
    NodeBatchLoader nodeBatchLoader,
    ShareBatchLoader shareBatchLoader
  ) {
    super(true);
    this.graphQL = graphQLProvider.getGraphQL();
    this.nodeBatchLoader = nodeBatchLoader;
    this.shareBatchLoader = shareBatchLoader;
  }

  /**
   * {@inheritDoc}
   * <p>
   * This method handles the exception thrown If something goes wrong during the execution of the
   * request. It returns a response containing an INTERNAL_SERVER_ERROR to the client instead of
   * forwarding the exception to the next ChannelHandler in the ChannelPipeline.
   * </p>
   *
   * @param ctx
   * @param cause
   *
   * @throws Exception
   */
  @Override
  public void exceptionCaught(
    ChannelHandlerContext ctx,
    Throwable cause
  ) {
    logger.warn("GraphQLController exception:\n" + cause.toString());
    ctx.writeAndFlush(new DefaultFullHttpResponse(
        HttpVersion.HTTP_1_0,
        HttpResponseStatus.INTERNAL_SERVER_ERROR
      ))
      .addListener(sNettyChannelFutureClose);
  }

  /**
   * {@inheritDoc}
   * <p>This method:
   *  <ul>
   *   <li>
   *     Creates a Runnable implementing the run method that executes
   *     the channelRead_async() (which handles the request).
   *   </li>
   *   <li>Puts the created runnable into the ActivityManager.</li>
   *  </ul>
   * </p>
   *
   * @param context
   * @param httpRequest
   */
  @Override
  protected void channelRead0(
    ChannelHandlerContext context,
    FullHttpRequest httpRequest
  ) {
    try {
      channelRead_async(context, httpRequest);
    } catch (Exception e) {
      logger.error(e.getLocalizedMessage());
      context.pipeline().fireExceptionCaught(e);
    }
  }

  private void channelRead_async(
    ChannelHandlerContext context,
    FullHttpRequest httpRequest
  ) {
    HttpVersion protocolVersionRequest = httpRequest.protocolVersion();
    ByteBuf contentRequest = httpRequest.content();

    Map<String, Object> graphQLContext = new HashMap<>();
    graphQLContext.put(
      Files.GraphQL.Context.REQUESTER,
      context.channel().attr(AttributeKey.valueOf("requester")).get()
    );
    graphQLContext.put(
      Files.GraphQL.Context.COOKIES,
      context.channel().attr(AttributeKey.valueOf("cookies")).get()
    );

    try {
      GraphQLRequest request = parseRequest(contentRequest);
      /*
       * The ExecutionInput object represents the input of the request, it has the following fields:
       *   - Query: the actual request to execute
       *   - Variables: the input values of the request (optional)
       *   - OperationName: the name of the request to execute (optional)
       *   - GraphQLContext: containing all the useful information to permit the fetching of the data
       *   - DataLoaderRegistry: a place to register all data loaders in
       */
      ExecutionInput input = ExecutionInput.newExecutionInput()
        .query(request.getRequest())
        .variables(request.getVariables())
        .operationName(request.getOperationName().orElse(""))
        .graphQLContext(graphQLContext)
        .dataLoaderRegistry(buildDataLoaderRegistry())
        .build();

      ExecutionResult executionResult = graphQL.executeAsync(input).join();
      String bodyResponse = new ObjectMapper()
        .writeValueAsString(executionResult.toSpecification());

      FullHttpResponse response = new DefaultFullHttpResponse(
        protocolVersionRequest,
        HttpResponseStatus.OK,
        Unpooled.wrappedBuffer(bodyResponse.getBytes(StandardCharsets.UTF_8))
      );

      response.headers().add(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON);
      response.headers().add(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
      httpRequest.retain();
      context.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);

    } catch (GraphQLRequest.InvalidPayloadRequestError | GraphQLException exception) {

      JsonNode jsonResponse = new ObjectMapper()
        .createObjectNode()
        .put("error_message", "Something went wrong");

      FullHttpResponse response = new DefaultFullHttpResponse(
        protocolVersionRequest,
        HttpResponseStatus.BAD_REQUEST,
        Unpooled.wrappedBuffer(jsonResponse.toPrettyString().getBytes(StandardCharsets.UTF_8))
      );
      response.headers().add(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON);
      response.headers().add(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
      context.writeAndFlush(response).addListener(sNettyChannelFutureClose);
    }
    // Catching the RuntimeException and the JsonProcessingException
    catch (Exception exception) {
      exception.printStackTrace();
      exceptionCaught(context, exception.getCause());
    }
  }

  private GraphQLRequest parseRequest(ByteBuf contentRequest)
    throws GraphQLRequest.InvalidPayloadRequestError {
    if (contentRequest == null || contentRequest.writerIndex() == 0) {
      throw new GraphQLRequest.InvalidPayloadRequestError(
        "The payload of a GraphQL request cannot be empty"
      );
    }
    try {
      String payloadString = contentRequest.toString(StandardCharsets.UTF_8);
      return GraphQLRequest.buildFromPayload(payloadString);
    } catch (Exception exception) {
      throw new GraphQLRequest.InvalidPayloadRequestError(
        "The payload of a GraphQL request cannot be parsed"
      );
    }
  }

  /**
   * Creates a {@link DataLoaderRegistry} and registers every {@link DataLoader} used to load
   * multiple elements in batch.
   * </p>
   * <strong>Note that the data loaders must be created per execution request.</strong>
   *
   * @return a {@link DataLoaderRegistry} containing all the registered {@link DataLoader}s.
   */
  private DataLoaderRegistry buildDataLoaderRegistry() {

    // DataLoaderRegistry is a place to register all data loaders in that needs to be dispatched together
    DataLoaderRegistry registry = new DataLoaderRegistry();
    registry.register(
        DataLoaders.NODE_BATCH_LOADER,
        DataLoaderFactory.newDataLoaderWithTry(nodeBatchLoader)
      );
    registry.register(
      DataLoaders.SHARE_BATCH_LOADER,
      DataLoaderFactory.newDataLoader(shareBatchLoader)
    );

    return registry;
  }
}
