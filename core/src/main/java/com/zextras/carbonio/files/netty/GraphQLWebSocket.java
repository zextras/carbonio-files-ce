// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.netty;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Inject;
import com.zextras.carbonio.files.Files;
import com.zextras.carbonio.files.graphql.FolderSubscriber;
import com.zextras.carbonio.files.graphql.GraphQLProvider;
import com.zextras.carbonio.files.graphql.GraphQLRequest;
import com.zextras.carbonio.files.graphql.NodeSubscriber;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.GraphQLException;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.AttributeKey;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Sharable
public class GraphQLWebSocket extends SimpleChannelInboundHandler<TextWebSocketFrame> {

  private static final Logger logger = LoggerFactory.getLogger(GraphQLWebSocket.class);

  private final GraphQL graphQL;

  @Inject
  public GraphQLWebSocket(GraphQLProvider graphQLProvider) {this.graphQL = graphQLProvider.getGraphQL();}


  @Override
  protected void channelRead0(
    ChannelHandlerContext channelHandlerContext,
    TextWebSocketFrame textWebSocketFrame
  ) throws Exception {

    ObjectMapper mapper = new ObjectMapper();
    String payload = textWebSocketFrame.text();
    logger.info(payload);
    Map<String, Object> graphQLContext = new HashMap<>();

    graphQLContext.put(
      Files.GraphQL.Context.REQUESTER,
      channelHandlerContext.channel().attr(AttributeKey.valueOf("requester")).get()
    );
    if(payload.contains("connection_init")) {


      ObjectNode n = mapper.createObjectNode().put("type", "connection_ack");
      String r = mapper.writeValueAsString(n);
      logger.info("MESSAGE " + r);
      channelHandlerContext
        .channel()
        .writeAndFlush(new TextWebSocketFrame(r));
      logger.info("SENT");
      return;
    }
    //channelHandlerContext.channel().write(new TextWebSocketFrame(payload));

    try {

      ObjectMapper map = new ObjectMapper();
      Map<String, Object> payloadMap = new HashMap<>();

      try {
        payloadMap = mapper.readValue(payload, Map.class);
      } catch (IOException e) {
        e.printStackTrace();
      }
      Map<String, Object> p = (Map<String, Object>) payloadMap.get("payload");
      //GraphQLRequest request = GraphQLRequest.buildFromPayload(p);
      ExecutionInput input = ExecutionInput.newExecutionInput()
        .query((String) p.get("query"))
        .variables((Map<String, Object>) p.get("variables"))
        .graphQLContext(graphQLContext)
        .build();

      ExecutionResult result = graphQL.executeAsync(input).join();
      Publisher<ExecutionResult> stream = result.getData();
      stream.subscribe(new FolderSubscriber((String)payloadMap.get("id"), channelHandlerContext));

    } catch (GraphQLException e) {
      channelHandlerContext.channel().writeAndFlush(new TextWebSocketFrame(
        mapper.createObjectNode().put("error", e.toString()).asText()
      ));
    }

  }
}
