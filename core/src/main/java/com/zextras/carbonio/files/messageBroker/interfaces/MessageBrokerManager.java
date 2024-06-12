// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.messageBroker.interfaces;

import com.rabbitmq.client.Connection;

public interface MessageBrokerManager {
  Connection getConnection();
  boolean healthCheck();
  void startAllConsumers();
}
