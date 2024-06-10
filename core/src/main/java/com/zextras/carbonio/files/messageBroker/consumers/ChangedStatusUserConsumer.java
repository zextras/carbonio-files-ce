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
import java.util.Optional;

public class ChangedStatusUserConsumer extends DefaultConsumer {

  private static final Logger logger = LoggerFactory.getLogger(ChangedStatusUserConsumer.class);

  private NodeRepository nodeRepository;

  public ChangedStatusUserConsumer(Channel channel, NodeRepository nodeRepository) {
    super(channel);
    this.nodeRepository = nodeRepository;
  }

  /**
   * Receives every user's status changed event, checks if its nodes should be shown or hidden and then changes
   * the hidden flag for each node if needed.
   */
  @Override
  public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body) {
    try {
      String message = new String(body, "UTF-8");
      UserStatusChangedEvent userStatusChangedEvent = new ObjectMapper().readValue(message, UserStatusChangedEvent.class);

      if(shouldChangeHiddenFlag(userStatusChangedEvent)){
        List<Node> nodesToProcess = nodeRepository.findNodesByOwner(userStatusChangedEvent.getUserId());
        nodeRepository.invertHiddenFlagNodes(nodesToProcess);
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

  /**
   * An operation is useless if all nodes already have the same flag that the operation would set. Since
   * setting this flag is transactional for all nodes owned by a user, checking a single node is sufficient.
   * Once obtaining the first node's hidden flag value we can check if is already correctly set or otherwise.
   * This is useful because if we catch an userstatuschangedevent, but it is from a non-closed status (like active)
   * to another non-closed status (like maintenance) we do not want to perform a useless update operation for
   * every node owned by user.
   */
  private boolean shouldChangeHiddenFlag(UserStatusChangedEvent userStatusChangedEvent){
    Optional<Node> firstNodeToCheckOpt = nodeRepository.findFirstByOwner(userStatusChangedEvent.getUserId());
    return firstNodeToCheckOpt.isPresent() &&
        !firstNodeToCheckOpt.get().getHidden().equals(shouldHideByStatus(userStatusChangedEvent.getUserStatus()));
  }

  /**
   * Small utility method that returns if nodes should be hidden or not given the status of a user.
   * Here we assume that nodes should be hidden only if user status is closed.
   */
  private boolean shouldHideByStatus(UserStatus userStatus){
    return userStatus.equals(UserStatus.CLOSED);
  }
}
