// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.messageBroker;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.zextras.carbonio.files.config.FilesConfig;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.messageBroker.consumers.ChangedStatusUserFilesConsumer;
import com.zextras.carbonio.files.messageBroker.interfaces.FilesConsumer;
import com.zextras.carbonio.files.messageBroker.interfaces.MessageBrokerManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

/**
 * This class is basically a repository for MessageBroker (RabbitMQ).
 * This class has the responsibility of starting consumers of events that need to be listened to.
 * The whole MessageBroker integration has been implemented such that listening to events is responsibility of
 * consumers defined in the project that needs to handle them, while how to push events to queue is defined
 * inside the project's sdk so external projects will be able to push events in the correct queue without worrying
 * about project-centric logic.
 * Note: RabbitMQ doesn't have a built-in check for existing queues and the suggested behaviour is to just create
 * the queue when necessary since if a queue with the same parameters already exists nothing will change;
 * since we can't know for sure if the queue already exists and since we can't know for
 * sure which service will start first/will be up when using said queue, the queue should be created both before
 * reading from it and before writing to it to eliminate every chance of exceptions from a non-existing queue.
 */
@Singleton
public class MessageBrokerManagerImpl implements MessageBrokerManager {

  private static final Logger logger = LoggerFactory.getLogger(MessageBrokerManagerImpl.class);

  private NodeRepository nodeRepository;

  private Connection connection;

  @Inject
  public MessageBrokerManagerImpl(FilesConfig filesConfig, NodeRepository nodeRepository){
    ConnectionFactory factory = new ConnectionFactory();
    factory.setHost(filesConfig.getMessageBrokerIp());
    factory.setPort(filesConfig.getMessageBrokerPort());
    factory.setUsername(filesConfig.getMessageBrokerUsername());
    factory.setPassword(filesConfig.getMessageBrokerPassword());
    try {
      this.connection = factory.newConnection();
    } catch (IOException | TimeoutException e) {
      // Do not throw in creation but throw on health check and every other method that requires a connection
      logger.error("Can't connect to RabbitMQ", e);
    }
    this.nodeRepository = nodeRepository;
  }

  @Override
  public Connection getConnection() {
    return connection;
  }

  private Channel openMessageBrokerChannel() {
    try {
      Optional<Channel> channelOpt = connection.openChannel();
      if(channelOpt.isPresent()) return channelOpt.get();
      else throw new RuntimeException("RabbitMQ returned empty channel");
    } catch (IOException e) {
      throw new RuntimeException("Could not create RabbitMQ channel for websocket", e);
    }
  }

  /**
   * Creates queue relative to given consumer if it doesn't already exist, then starts consumer to listen on that queue.
   */
  private void startConsumer(FilesConsumer filesConsumer) throws RuntimeException{
    try {
      // Create queue if it doesn't exist, if this queue exists already nothing happens
      filesConsumer.getChannel().queueDeclare(filesConsumer.getNameOfQueue(), true, false, false, null);

      // Start consumer with autoAck to false since we want to send manually an ack only once the event has been handled
      filesConsumer.getChannel().basicConsume(filesConsumer.getNameOfQueue(), false, filesConsumer);
    }catch (IOException e) {
      throw new RuntimeException("Can't consume from queue", e);
    }
  }

  /**
   * Starts all consumers with a separate channel for each one of them, since if an operation inside handleDelivery of
   * a consumer is heavy we don't want to block the channel while it executes.
   * If other consumers are needed just create and start them here.
   */
  @Override
  public void startAllConsumers() {
    startConsumer(new ChangedStatusUserFilesConsumer(openMessageBrokerChannel(), nodeRepository));
  }

  @Override
  public boolean healthCheck() {
    return connection != null && connection.isOpen();
  }
}
