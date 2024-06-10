// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.messageBroker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.zextras.carbonio.files.config.FilesConfig;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.messageBroker.consumers.ChangedStatusUserConsumer;
import com.zextras.carbonio.files.messageBroker.entities.UserStatusChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

import static com.zextras.carbonio.files.Files.MessageBroker.USER_STATUS_CHANGED_QUEUE;

@Singleton
public class MessageBrokerManagerImpl implements MessageBrokerManager {

  private static final Logger logger = LoggerFactory.getLogger(MessageBrokerManagerImpl.class);

  private NodeRepository nodeRepository;

  private Connection connection;

  @Inject
  public MessageBrokerManagerImpl(FilesConfig filesConfig, NodeRepository nodeRepository){
    this.connection = getRabbitConnection(filesConfig);
    this.nodeRepository = nodeRepository;
  }

  private Connection getRabbitConnection(FilesConfig filesConfig){
    ConnectionFactory factory = new ConnectionFactory();
    factory.setHost(filesConfig.getMessageBrokerIp());
    factory.setPort(filesConfig.getMessageBrokerPort());
    factory.setUsername(filesConfig.getMessageBrokerUsername());
    factory.setPassword(filesConfig.getMessageBrokerPassword());
    try {
      return factory.newConnection();
    } catch (IOException | TimeoutException e) {
      throw new RuntimeException("Can't connect to RabbitMQ", e);
    }
  }

  private Channel openRabbitChannel() {
    try {
      Optional<Channel> channelOpt = connection.openChannel();
      if(channelOpt.isPresent()) return channelOpt.get();
      else throw new RuntimeException("RabbitMQ returned empty channel");
    } catch (IOException e) {
      throw new RuntimeException("Could not create RabbitMQ channel for websocket", e);
    }
  }

  private void createAndStartUserStatusChangedConsumer(Channel channel) throws RuntimeException{
    // Create consumer
    ChangedStatusUserConsumer consumer = new ChangedStatusUserConsumer(channel, nodeRepository);

    try {
      // Create queue if it doesn't exist
      // If this queue exists already nothing happens
      channel.queueDeclare(USER_STATUS_CHANGED_QUEUE, true, false, false, null);

      // Start consumer
      channel.basicConsume(USER_STATUS_CHANGED_QUEUE, true, consumer);
    }catch (IOException e) {
      logger.error("Can't consume from queue", e);
    }
  }

  @Override
  public void startAllConsumers() {
    // Open new rabbitMQ channel
    Channel channel = openRabbitChannel();

    // Try to start consumers
    createAndStartUserStatusChangedConsumer(channel);
  }

  // TODO not really used here, only for testing. Remember to remove.
  @Override
  public void pushUtil(UserStatusChangedEvent userStatusChangedEvent) {
    // Open new rabbitMQ channel
    Channel channel = openRabbitChannel();

    try {
      // Create queue if it doesn't exist
      // If this queue exists already nothing happens
      // Consideration: both reader and writer should create queue if it doesn't exist; this is
      // because we don't know which service starts first
      channel.queueDeclare(USER_STATUS_CHANGED_QUEUE, true, false, false, null);

      String message = new ObjectMapper().writeValueAsString(userStatusChangedEvent);

      channel.basicPublish("", USER_STATUS_CHANGED_QUEUE, null, message.getBytes("UTF-8"));
      channel.close();
    }catch (IOException | TimeoutException e) {
      logger.error("Can't push to queue", e);
    }
  }


}
