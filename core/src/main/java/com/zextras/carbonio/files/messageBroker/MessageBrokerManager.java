// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.messageBroker;

import com.rabbitmq.client.Connection;
import com.zextras.carbonio.files.messageBroker.entities.UserStatusChangedEvent;

public interface MessageBrokerManager {
  Connection getConnection();

  boolean healthCheck();
  void startAllConsumers();
  void pushUtil(UserStatusChangedEvent UserStatusChangedEvent);
}
