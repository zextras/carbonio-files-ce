// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import graphql.ExecutionResult;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FolderSubscriber implements Subscriber<ExecutionResult> {

  private static final Logger logger = LoggerFactory.getLogger(FolderSubscriber.class);

  private final ChannelHandlerContext   webSocketContext;
  private final String operationId;

  public FolderSubscriber(String operationId, ChannelHandlerContext webSocketContext) {
    this.operationId = operationId;
    this.webSocketContext = webSocketContext;
  }

  @Override
  public void onSubscribe(Subscription subscription) {
    subscription.request(Long.MAX_VALUE);
  }

  @Override
  public void onNext(ExecutionResult subscriptionResult) {
    ObjectMapper mapper = new ObjectMapper();
    ObjectNode node = mapper.createObjectNode()
      .put("id", operationId)
      .put("type", "next")
      .set("payload", mapper.convertValue(subscriptionResult.toSpecification(), JsonNode.class));

    try {
      webSocketContext
        .channel()
        .writeAndFlush(new TextWebSocketFrame(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node)));
    } catch (JsonProcessingException e) {
      onError(e);
    }

  }

  @Override
  public void onError(Throwable throwable) {
    logger.error("CLOSE CONN: " + throwable);
    webSocketContext.channel().close();
  }

  @Override
  public void onComplete() {
    logger.info("Complete");
    webSocketContext.channel().close();
  }
}
