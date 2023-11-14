// SPDX-FileCopyrightText: 2023 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.controllers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.zextras.carbonio.files.exceptions.BadRequestException;
import com.zextras.carbonio.files.graphql.GraphQLRequest;
import com.zextras.carbonio.files.graphql.PublicGraphQLProvider;
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
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class is the entry-point of the Files Public GraphQL API that is accessible without
 * authentication token. The main purpose is to:
 *
 * <ul>
 *   <li>Receive a json request
 *   <li>Parse the request creating a {@link GraphQLRequest}
 *   <li>Execute the request via {@link GraphQL}
 *   <li>Return the response with the JSON data requested or an error
 * </ul>
 */
@ChannelHandler.Sharable
@Singleton
public class PublicGraphQLController extends SimpleChannelInboundHandler<FullHttpRequest> {

  private static final Logger logger = LoggerFactory.getLogger(PublicGraphQLController.class);

  private final GraphQL publicGraphQL;

  @Inject
  public PublicGraphQLController(PublicGraphQLProvider publicGraphQLProvider) {
    super(true);
    this.publicGraphQL = publicGraphQLProvider.getGraphQL();
  }

  @Override
  protected void channelRead0(ChannelHandlerContext context, FullHttpRequest httpRequest) {
    try {
      GraphQLRequest request = parseRequest(httpRequest.content());
      /*
       * The ExecutionInput object represents the input of the request, it has the following fields:
       *   - Query: the actual request to execute
       *   - Variables: the input values of the request (optional)
       *   - OperationName: the name of the request to execute (optional)
       */
      ExecutionInput input =
          ExecutionInput.newExecutionInput()
              .query(request.getRequest())
              .variables(request.getVariables())
              .operationName(request.getOperationName().orElse(""))
              .build();

      ExecutionResult executionResult = publicGraphQL.executeAsync(input).join();
      String bodyResponse =
          new ObjectMapper().writeValueAsString(executionResult.toSpecification());

      FullHttpResponse response =
          new DefaultFullHttpResponse(
              httpRequest.protocolVersion(),
              HttpResponseStatus.OK,
              Unpooled.wrappedBuffer(bodyResponse.getBytes(StandardCharsets.UTF_8)));

      response.headers().add(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON);
      response.headers().add(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
      httpRequest.retain();
      context.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);

    } catch (GraphQLRequest.InvalidPayloadRequestError | GraphQLException exception) {
      logger.error("PublicGraphQLController catches an exception handling the request", exception);
      context.fireExceptionCaught(new BadRequestException());
    }
    // Catching the RuntimeException and the JsonProcessingException
    catch (Exception exception) {
      logger.error("PublicGraphQLController catches an exception", exception);
      context.fireExceptionCaught(exception);
    }
  }

  private GraphQLRequest parseRequest(ByteBuf contentRequest)
      throws GraphQLRequest.InvalidPayloadRequestError {
    if (contentRequest == null || contentRequest.writerIndex() == 0) {
      throw new GraphQLRequest.InvalidPayloadRequestError(
          "The payload of a GraphQL request cannot be empty");
    }
    try {
      String payloadString = contentRequest.toString(StandardCharsets.UTF_8);
      return GraphQLRequest.buildFromPayload(payloadString);
    } catch (Exception exception) {
      throw new GraphQLRequest.InvalidPayloadRequestError(
          "The payload of a GraphQL request cannot be parsed");
    }
  }
}
