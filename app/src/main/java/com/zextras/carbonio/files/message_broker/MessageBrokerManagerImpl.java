// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.message_broker;

import com.zextras.carbonio.files.message_broker.consumers.KeyValueChangedConsumer;
import com.zextras.carbonio.files.message_broker.consumers.UserStatusChangedConsumer;
import com.zextras.carbonio.files.message_broker.interfaces.MessageBrokerManager;
import com.zextras.carbonio.message_broker.MessageBrokerClient;
import com.zextras.carbonio.message_broker.consumer.BaseConsumer;
import com.zextras.carbonio.message_broker.events.generic.BaseEvent;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * P5: CDI port of the legacy Guice {@code MessageBrokerManagerImpl}. Starts the consumers at app
 * startup ({@link #onStart}), rather than from an explicit {@code boot} wiring call, since Quarkus
 * has no equivalent boot-sequencing hook.
 *
 * <p>{@link MessageBrokerClient#healthCheck()} never throws (it catches broadly internally and
 * returns {@code false} on any connection failure), so {@link #startAllConsumers()} degrades
 * gracefully to a no-op + warning log when RabbitMQ is unreachable at boot, both here and in
 * {@code %test} (no broker is started for the DAL/GraphQL/REST integration tests yet; that is the
 * P5c acceptance seam).
 */
@ApplicationScoped
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

  /** Starts all consumers as soon as the application is up. */
  void onStart(@Observes StartupEvent event) {
    startAllConsumers();
  }

  /** Closes all consumers (and their RabbitMQ channels) on application shutdown. */
  void onStop(@Observes ShutdownEvent event) {
    close();
  }

  /**
   * Here one can add a consumer to listen to an event published for files
   */
  @Override
  public void startAllConsumers() {
    if(messageBrokerClient.healthCheck()) {
      allConsumers.add(userStatusChangedConsumer);
      allConsumers.add(keyValueChangedConsumer);

      allConsumers.forEach(messageBrokerClient::consume);
    } else {
      logger.warn("Message broker health check failed, not starting consumers");
    }
  }

  @Override
  public void publishEvent(BaseEvent event) {
    if(messageBrokerClient.healthCheck()) {
      messageBrokerClient.publish(event);
    } else {
      logger.warn("Message broker health check failed, not publishing event");
    }
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
