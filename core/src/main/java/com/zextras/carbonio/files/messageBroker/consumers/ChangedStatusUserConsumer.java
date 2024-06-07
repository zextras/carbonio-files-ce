// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.messageBroker.consumers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import com.zextras.carbonio.files.messageBroker.entities.UserStatusChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UnsupportedEncodingException;

public class ChangedStatusUserConsumer extends DefaultConsumer {

  private static final Logger logger = LoggerFactory.getLogger(ChangedStatusUserConsumer.class);

  public ChangedStatusUserConsumer(Channel channel) {
    super(channel);
  }

  @Override
  public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body) {
    //TODO event received, handle it accordingly
    System.err.println("RECEIVED EVENT!");

    String message = null;
    try {
      message = new String(body, "UTF-8");
    } catch (UnsupportedEncodingException e) {
      throw new RuntimeException(e);
    }
    ObjectMapper objectMapper = new ObjectMapper();
    try {
      UserStatusChangedEvent userStatusChangedEvent = objectMapper.readValue(message, UserStatusChangedEvent.class);
    } catch (JsonProcessingException e) {
      throw new RuntimeException(e);
    }

    // Ack is sent to confirm operation has been completed, if connection fails before ack is returned
    // rabbitMQ will repopulate its queue with object.
    try {
      getChannel().basicAck(envelope.getDeliveryTag(), false);
    } catch (IOException e) {
      throw new RuntimeException("Can't send ack to rabbitmq", e);
    }
  }
}
