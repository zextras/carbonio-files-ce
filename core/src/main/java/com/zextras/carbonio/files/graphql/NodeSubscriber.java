// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.zextras.carbonio.files.dal.dao.ebean.Node;
import graphql.ExecutionResult;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NodeSubscriber implements Subscriber<ExecutionResult> {

  private static final Logger logger = LoggerFactory.getLogger(NodeSubscriber.class);

  private       AtomicReference<String> folderIdSubscribed;
  private final ChannelHandlerContext   webSocketContext;

  public NodeSubscriber(
    String folderId,
    ChannelHandlerContext webSocketContext
  ) {
    folderIdSubscribed = new AtomicReference<>(folderId);
    this.webSocketContext = webSocketContext;
  }

  @Override
  public void onSubscribe(Subscription subscription) {
    logger.info("SUB to :" + folderIdSubscribed.get());
    subscription.request(Long.MAX_VALUE);
  }

  @Override
  public void onNext(ExecutionResult subscriptionResult) {
    Map<String, String> data = subscriptionResult.getData();
    ObjectMapper mapper = new ObjectMapper();
    ObjectNode node = mapper.createObjectNode()
      .put("id", folderIdSubscribed.get())
      .put("type", "next")
      .set("payload", mapper.convertValue(subscriptionResult.toSpecification(), JsonNode.class));
    //logger.info("[" + folderIdSubscribed.get() + "] - DATA RECEIVED: " + data.get("node_id"));

    try {
      webSocketContext
        .channel()
        .writeAndFlush(new TextWebSocketFrame(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node)));
    } catch (JsonProcessingException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void onError(Throwable throwable) {
    logger.error("CLOSE CONN: " + throwable);
    webSocketContext.channel().close();
  }

  @Override
  public void onComplete() {
    logger.info("Complete for folder: " + folderIdSubscribed.get());
    webSocketContext.channel().close();
  }
}
