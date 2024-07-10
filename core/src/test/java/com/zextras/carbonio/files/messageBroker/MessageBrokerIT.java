// SPDX-FileCopyrightText: 2023 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.messageBroker;

import com.google.inject.Injector;
import com.zextras.carbonio.files.Simulator;
import com.zextras.carbonio.files.Simulator.SimulatorBuilder;
import com.zextras.carbonio.files.api.utilities.DatabasePopulator;
import com.zextras.carbonio.files.api.utilities.entities.SimplePopulatorTextFile;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.messageBroker.interfaces.MessageBrokerManager;
import com.zextras.carbonio.message_broker.config.enums.Service;
import com.zextras.carbonio.message_broker.events.services.mailbox.UserStatusChanged;
import com.zextras.carbonio.message_broker.events.services.mailbox.enums.UserStatus;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

class MessageBrokerIT {

  static Simulator simulator;
  static NodeRepository nodeRepository;
  static MessageBrokerManager messageBrokerManager;

  @BeforeAll
  static void init() {
    simulator =
        SimulatorBuilder.aSimulator()
            .init()
            .withDatabase()
            .withMessageBroker()
            .withServiceDiscover()
            .withUserManagement(
                Map.of(
                    "fake-token",
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"))
            .build()
            .start();
    final Injector injector = simulator.getInjector();
    nodeRepository = injector.getInstance(NodeRepository.class);
    messageBrokerManager = injector.getInstance(MessageBrokerManager.class);
  }

  @AfterEach
  void cleanUp() {
    simulator.resetDatabase();
  }

  @AfterAll
  static void cleanUpAll() {
    simulator.stopAll();
  }

  @Test
  void givenANotHiddenNodeAndAnUserStatusChangedEventClosedWrittenOnMessageBrokerQueueUsersNodesHiddenFlagsShouldBeTrue() throws InterruptedException {
    // Given
    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(new SimplePopulatorTextFile("00000000-0000-0000-0000-000000000000", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));

    // When
    messageBrokerManager.startAllConsumers();
    UserStatusChanged userStatusChangedEvent = new UserStatusChanged("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", UserStatus.CLOSED);
    messageBrokerManager.getMessageBrokerClient().withCurrentService(Service.MAILBOX).publish(userStatusChangedEvent);

    // Unfortunately there's no simple way of knowing when an event has been consumed without changing the logic of
    // the consumer itself; this solution while ugly is quite clear and fast enough.
    Thread.sleep(2000);

    // Then
    Optional<Node> nodeOpt = nodeRepository.getNode("00000000-0000-0000-0000-000000000000");
    nodeOpt.ifPresentOrElse(node -> {
      Assertions.assertThat(node.isHidden()).isTrue();
    }, () -> Assertions.fail("Node not found"));
  }

  @Test
  void givenAHiddenNodeAnUserStatusChangedEventActiveWrittenOnMessageBrokerQueueUsersNodesHiddenFlagsShouldBeFalse() throws InterruptedException {
    // Given
    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(new SimplePopulatorTextFile("00000000-0000-0000-0000-000000000000", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
    nodeRepository.updateNode(nodeRepository.getNode("00000000-0000-0000-0000-000000000000").get().setHidden(true));

    // When
    messageBrokerManager.startAllConsumers();
    UserStatusChanged userStatusChangedEvent = new UserStatusChanged("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", UserStatus.ACTIVE);
    messageBrokerManager.getMessageBrokerClient().withCurrentService(Service.MAILBOX).publish(userStatusChangedEvent);

    // Unfortunately there's no simple way of knowing when an event has been consumed without changing the logic of
    // the consumer itself; this solution while ugly is quite clear and fast enough.
    Thread.sleep(2000);

    // Then
    Optional<Node> nodeOpt = nodeRepository.getNode("00000000-0000-0000-0000-000000000000");
    nodeOpt.ifPresentOrElse(node -> {
      Assertions.assertThat(node.isHidden()).isFalse();
    }, () -> Assertions.fail("Node not found"));
  }

  @Test
  void givenANotHiddenNodeAnUserStatusChangedEventMaintenanceWrittenOnMessageBrokerQueueUsersNodesHiddenFlagsShouldBeUnchanged() throws InterruptedException {
    // Given
    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(new SimplePopulatorTextFile("00000000-0000-0000-0000-000000000000", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));

    // When
    messageBrokerManager.startAllConsumers();
    UserStatusChanged userStatusChangedEvent = new UserStatusChanged("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", UserStatus.MAINTENANCE);
    messageBrokerManager.getMessageBrokerClient().withCurrentService(Service.MAILBOX).publish(userStatusChangedEvent);

    // Unfortunately there's no simple way of knowing when an event has been consumed without changing the logic of
    // the consumer itself; this solution while ugly is quite clear and fast enough.
    Thread.sleep(2000);

    // Then
    Optional<Node> nodeOpt = nodeRepository.getNode("00000000-0000-0000-0000-000000000000");
    nodeOpt.ifPresentOrElse(node -> {
      Assertions.assertThat(node.isHidden()).isFalse();
    }, () -> Assertions.fail("Node not found"));
  }

}
