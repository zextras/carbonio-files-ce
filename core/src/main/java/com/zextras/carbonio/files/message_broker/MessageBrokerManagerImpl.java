// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.message_broker;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.zextras.carbonio.files.config.FilesConfig;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.message_broker.consumers.UserStatusChangedConsumer;
import com.zextras.carbonio.files.message_broker.interfaces.MessageBrokerManager;
import com.zextras.carbonio.message_broker.MessageBrokerClient;
import com.zextras.carbonio.message_broker.config.enums.Service;
import com.zextras.carbonio.message_broker.events.generic.BaseEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class is basically a repository for MessageBroker (RabbitMQ).
 * This class has the responsibility of starting consumers of events that need to be listened to.
 */
@Singleton
public class MessageBrokerManagerImpl implements MessageBrokerManager {

  private static final Logger logger = LoggerFactory.getLogger(MessageBrokerManagerImpl.class);

  private final MessageBrokerClient messageBrokerClient;
  private final NodeRepository nodeRepository;

  @Inject
  public MessageBrokerManagerImpl(
      FilesConfig filesConfig,
      NodeRepository nodeRepository)
  {
    this.messageBrokerClient =
        MessageBrokerClient.fromConfig(
            filesConfig.getMessageBrokerIp(),
            filesConfig.getMessageBrokerPort(),
            filesConfig.getMessageBrokerUsername(),
            filesConfig.getMessageBrokerPassword())
        .withCurrentService(Service.FILES);
    this.nodeRepository = nodeRepository;
  }

  /**
   * Here one can add a consumer to listen to an event published for files
   */
  @Override
  public void startAllConsumers() {
    messageBrokerClient.consume(new UserStatusChangedConsumer(nodeRepository));
  }

  @Override
  public void publishEvent(BaseEvent event) {
    messageBrokerClient.publish(event);
  }

  @Override
  public MessageBrokerClient getMessageBrokerClient() {
    return messageBrokerClient;
  }

  @Override
  public boolean healthCheck() {
    return messageBrokerClient.healthCheck();
  }
}
