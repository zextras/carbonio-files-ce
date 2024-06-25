// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.messageBroker.interfaces;

import com.rabbitmq.client.Connection;
import com.zextras.carbonio.message_broker.MessageBrokerClient;
import com.zextras.carbonio.message_broker.events.generic.BaseEvent;

public interface MessageBrokerManager {
  boolean healthCheck();
  void startAllConsumers();
  void publishEvent(BaseEvent event);
  MessageBrokerClient getMessageBrokerClient();
}
