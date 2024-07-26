// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.message_broker;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.zextras.carbonio.files.config.FilesConfig;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.message_broker.consumers.KeyValueChangedConsumer;
import com.zextras.carbonio.files.message_broker.consumers.UserStatusChangedConsumer;
import com.zextras.carbonio.files.message_broker.interfaces.MessageBrokerManager;
import com.zextras.carbonio.message_broker.MessageBrokerClient;
import com.zextras.carbonio.message_broker.config.enums.Service;
import com.zextras.carbonio.message_broker.consumer.BaseConsumer;
import com.zextras.carbonio.message_broker.events.generic.BaseEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

@Singleton
public class MessageBrokerManagerImpl implements MessageBrokerManager {

  private static final Logger logger = LoggerFactory.getLogger(MessageBrokerManagerImpl.class);

  private final MessageBrokerClient messageBrokerClient;
  private final NodeRepository nodeRepository;
  private final FileVersionRepository fileVersionRepository;
  private List<BaseConsumer> allConsumers;

  @Inject
  public MessageBrokerManagerImpl(
      MessageBrokerClient messageBrokerClient,
      NodeRepository nodeRepository,
      FileVersionRepository fileVersionRepository)
  {
    this.allConsumers = new ArrayList<>();
    this.messageBrokerClient = messageBrokerClient;
    this.nodeRepository = nodeRepository;
    this.fileVersionRepository = fileVersionRepository;
  }

  /**
   * Here one can add a consumer to listen to an event published for files
   */
  @Override
  public void startAllConsumers() {
    allConsumers.add(new UserStatusChangedConsumer(nodeRepository));
    allConsumers.add(new KeyValueChangedConsumer(fileVersionRepository));

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
  public void close() throws Exception {
    for(BaseConsumer consumer : allConsumers) {
      consumer.close();
    }
  }
}
