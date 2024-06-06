// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.messageBroker.consumers;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class ChangedStatusUserConsumer extends DefaultConsumer {

  private static final Logger logger = LoggerFactory.getLogger(ChangedStatusUserConsumer.class);

  public ChangedStatusUserConsumer(Channel channel) {
    super(channel);
  }

  @Override
  public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body) {
    //TODO event received, handle it accordingly
    System.out.println("RECEIVED EVENT!");

    // Ack is sent to confirm operation has been completed, if connection fails before ack is returned
    // rabbitMQ will repopulate its queue with object.
    try {
      getChannel().basicAck(envelope.getDeliveryTag(), false);
    } catch (IOException e) {
      throw new RuntimeException("Can't send ack to rabbitmq", e);
    }
  }
}
