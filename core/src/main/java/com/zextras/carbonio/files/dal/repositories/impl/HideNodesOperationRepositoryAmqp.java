// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.rabbitmq.client.Channel;
import com.zextras.carbonio.files.Files;
import com.zextras.carbonio.files.config.FilesConfig;
import com.zextras.carbonio.files.dal.repositories.interfaces.HideNodesOperationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import java.io.IOException;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.TimeoutException;

public class HideNodesOperationRepositoryAmqp implements HideNodesOperationRepository {

  private static final Logger logger = LoggerFactory.getLogger(HideNodesOperationRepositoryAmqp.class);

  private Connection connection;

  @Inject
  public HideNodesOperationRepositoryAmqp(FilesConfig filesConfig) {
    Properties p = filesConfig.getProperties();
    connection = getRabbitConnection(
        p.getProperty(Files.Config.UserManagement.URL),
        Integer.parseInt(p.getProperty(Files.Config.UserManagement.URL))
    );
  }

  /**
   * Returns rabbit connection or fail; is singleton to use a single connection to increase performances.
   */
  @Singleton
  private Connection getRabbitConnection(String host, int port){
    ConnectionFactory factory = new ConnectionFactory();
    factory.setHost(host);
    factory.setPort(port);
    try (Connection connection = factory.newConnection()) {
      return connection;
    } catch (IOException | TimeoutException e) {
      logger.error("Can't connect to RabbitMQ", e);
      throw new RuntimeException("Can't connect to RabbitMQ", e);
    }
  }

  /**
   * Should not be singleton or shared between threads
   * <a href="https://rabbitmq.github.io/rabbitmq-java-client/api/current/com/rabbitmq/client/Channel.html">...</a>
   */
  private Channel openNewChannel() {
    try {
      Optional<Channel> channelOpt = connection.openChannel();
      if(channelOpt.isPresent()) return channelOpt.get();
          else throw new RuntimeException("RabbitMQ returned empty channel");
    } catch (IOException e) {
      logger.error("Could not create RabbitMQ channel for websocket");
      throw new RuntimeException("Could not create RabbitMQ channel for websocket", e);
    }
  }
}
