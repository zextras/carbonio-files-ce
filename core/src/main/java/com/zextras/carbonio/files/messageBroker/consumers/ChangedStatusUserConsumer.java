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
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.messageBroker.entities.UserStatusChangedEvent;
import com.zextras.carbonio.usermanagement.enumerations.UserStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.List;

public class ChangedStatusUserConsumer extends DefaultConsumer {

  private static final Logger logger = LoggerFactory.getLogger(ChangedStatusUserConsumer.class);

  private NodeRepository nodeRepository;

  public ChangedStatusUserConsumer(Channel channel, NodeRepository nodeRepository) {
    super(channel);
    this.nodeRepository = nodeRepository;
  }

  @Override
  public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body) {
    try {
      String message = new String(body, "UTF-8");
      UserStatusChangedEvent userStatusChangedEvent = new ObjectMapper().readValue(message, UserStatusChangedEvent.class);

      //TODO use entity
      if(shouldChangeHiddenFlag(userStatusChangedEvent)){
        List<Node> nodesToProcess = nodeRepository.findNodesByOwner(userStatusChangedEvent.getUserId());
        nodeRepository.setHiddenFlagNodes(
            nodesToProcess,
            !userStatusChangedEvent.getUserStatus().equals(UserStatus.ACTIVE));
      }

      // Ack is sent to confirm operation has been completed, if connection fails before ack is returned
      // rabbitMQ will repopulate its queue with object.
      getChannel().basicAck(envelope.getDeliveryTag(), false);
    } catch (JsonProcessingException | UnsupportedEncodingException e) {
      logger.error("Can't process entity in queue", e);
    } catch (IOException e) {
      logger.error("Can't send ack back to rabbitmq", e);
    }
  }
}
