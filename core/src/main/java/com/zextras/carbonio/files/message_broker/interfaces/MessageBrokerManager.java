// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.message_broker.interfaces;

import com.zextras.carbonio.message_broker.MessageBrokerClient;
import com.zextras.carbonio.message_broker.events.generic.BaseEvent;

import javax.sql.DataSource;

/**
 * Classes extending this interface are basically a repository for MessageBroker (RabbitMQ).
 * They have the responsibility of starting consumers of events that need to be listened to.
 *
 * <p>The {@link #healthCheck()} method should return the connection status with RabbitMQ.
 *
 * <p>The {@link #startAllConsumers()} method should start all consumers required for this project.
 *
 * <p>The {@link #publishEvent(BaseEvent)} method should use Message Broker Client from relative SDK to publish an event.
 *
 * <p>The {@link #getMessageBrokerClient()} method should return Message Broker Client from Message Broker SDK.
 */
public interface MessageBrokerManager extends AutoCloseable {
  boolean healthCheck();
  void startAllConsumers();
  void publishEvent(BaseEvent event);
  MessageBrokerClient getMessageBrokerClient();
}
