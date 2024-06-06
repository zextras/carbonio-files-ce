// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.messageBroker;

import com.google.inject.Inject;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.zextras.carbonio.files.config.FilesConfig;
import com.zextras.carbonio.files.messageBroker.consumers.ChangedStatusUserConsumer;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

public class MessageBrokerManagerImpl implements MessageBrokerManager {

  private Connection connection;

  @Inject
  public MessageBrokerManagerImpl(FilesConfig filesConfig){
    this.connection = getRabbitConnection(filesConfig);
  }

  @Singleton
  private Connection getRabbitConnection(FilesConfig filesConfig){
    ConnectionFactory factory = new ConnectionFactory();
    factory.setHost(filesConfig.getMessageBrokerIp());
    factory.setPort(filesConfig.getMessageBrokerPort());
    try (Connection connection = factory.newConnection()) {
      return connection;
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

  private void createAndStartConsumers(Channel channel) throws IOException{
    // Declare consumers
    ChangedStatusUserConsumer consumer = new ChangedStatusUserConsumer(channel);

    // Start consumers
    channel.basicConsume("CHANGED_USER_STATUS", true, consumer); //TODO should get name from config
  }

  @Override
  public void startAllConsumers() throws RuntimeException {
    // Open new rabbitMQ channel
    Channel channel = openRabbitChannel();

    // Try to start consumers
    try {
      createAndStartConsumers(channel);
    } catch (IOException e) {
      throw new RuntimeException("Exception while starting rabbitmq consumers", e);
    }
  }

}
