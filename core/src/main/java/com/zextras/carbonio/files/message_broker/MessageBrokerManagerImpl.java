// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.message_broker;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.zextras.carbonio.files.message_broker.consumers.KeyValueChangedConsumer;
import com.zextras.carbonio.files.message_broker.consumers.UserStatusChangedConsumer;
import com.zextras.carbonio.files.message_broker.interfaces.MessageBrokerManager;
import com.zextras.carbonio.message_broker.MessageBrokerClient;
import com.zextras.carbonio.message_broker.consumer.BaseConsumer;
import com.zextras.carbonio.message_broker.events.generic.BaseEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Singleton
public class MessageBrokerManagerImpl implements MessageBrokerManager {

  private static final Logger logger = LoggerFactory.getLogger(MessageBrokerManagerImpl.class);

  private final MessageBrokerClient messageBrokerClient;
  private final UserStatusChangedConsumer userStatusChangedConsumer;
  private final KeyValueChangedConsumer keyValueChangedConsumer;
  private List<BaseConsumer> allConsumers;

  @Inject
  public MessageBrokerManagerImpl(
      MessageBrokerClient messageBrokerClient,
      UserStatusChangedConsumer userStatusChangedConsumer,
      KeyValueChangedConsumer keyValueChangedConsumer)
  {
    this.allConsumers = new ArrayList<>();
    this.messageBrokerClient = messageBrokerClient;
    this.userStatusChangedConsumer = userStatusChangedConsumer;
    this.keyValueChangedConsumer = keyValueChangedConsumer;
  }

  /**
   * Here one can add a consumer to listen to an event published for files
   */
  @Override
  public void startAllConsumers() {
    allConsumers.add(userStatusChangedConsumer);
    allConsumers.add(keyValueChangedConsumer);

    allConsumers.forEach(messageBrokerClient::consume);
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

  @Override
  public void close() {
    for(BaseConsumer consumer : allConsumers) {
      try {
        consumer.close();
      } catch (IOException e) {
        logger.warn("Error while closing consumer", e);
      }
    }
  }
}
