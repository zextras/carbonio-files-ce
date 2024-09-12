// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.message_broker.it;

import com.google.inject.Injector;
import com.zextras.carbonio.files.Simulator;
import com.zextras.carbonio.files.Simulator.SimulatorBuilder;
import com.zextras.carbonio.files.api.utilities.DatabasePopulator;
import com.zextras.carbonio.files.api.utilities.entities.SimplePopulatorTextFile;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.message_broker.consumers.UserStatusChangedConsumer;
import com.zextras.carbonio.message_broker.events.services.mailbox.UserStatusChanged;
import com.zextras.carbonio.message_broker.events.services.mailbox.enums.UserStatus;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.assertj.core.api.Assertions;
import org.assertj.core.api.Condition;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Here I mock an event emitted and received because if I was to emit a real event I could not know
 * when it will be consumed
 */
class UserStatusChangedIT {

  static Simulator simulator;
  static NodeRepository nodeRepository;
  static Condition<Node> isNodeHidden;


  @BeforeAll
  static void init() {
    Map<String, String> users = new HashMap<String, String>();
    users.put("fake-token", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    users.put("fake-token2", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaab");
    users.put("fake-token3", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaac");
    simulator =
        SimulatorBuilder.aSimulator()
            .init()
            .withDatabase()
            .withMessageBroker()
            .withServiceDiscover()
            .withUserManagement(users)
            .build()
            .start();
    final Injector injector = simulator.getInjector();
    nodeRepository = injector.getInstance(NodeRepository.class);
    isNodeHidden = new Condition<Node>(Node::isHidden, "node is hidden");
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
  void
  givenANotHiddenNodeAndAnUserStatusChangedEventClosedWrittenOnMessageBrokerQueueUsersNodesHiddenFlagsShouldBeTrue() {
    // Given
    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(
            new SimplePopulatorTextFile(
                "00000000-0000-0000-0000-000000000000", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));

    // When
    UserStatusChangedConsumer userStatusChangedConsumer = new UserStatusChangedConsumer(nodeRepository);
    userStatusChangedConsumer.doHandle(new UserStatusChanged("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", UserStatus.CLOSED));

    // Then
    Optional<Node> nodeOpt = nodeRepository.getNode("00000000-0000-0000-0000-000000000000");
    Assertions.assertThat(nodeOpt).isPresent();
    Assertions.assertThat(nodeOpt.get()).is(isNodeHidden);
  }

  @Test
  void
  givenAHiddenNodeAnUserStatusChangedEventActiveWrittenOnMessageBrokerQueueUsersNodesHiddenFlagsShouldBeFalse() {
    // Given
    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(
            new SimplePopulatorTextFile(
                "00000000-0000-0000-0000-000000000001", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaab"));
    nodeRepository.updateNode(
        nodeRepository.getNode("00000000-0000-0000-0000-000000000001").get().setHidden(true));

    // When
    UserStatusChangedConsumer userStatusChangedConsumer = new UserStatusChangedConsumer(nodeRepository);
    userStatusChangedConsumer.doHandle(new UserStatusChanged("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaab", UserStatus.ACTIVE));

    // Then
    Optional<Node> nodeOpt = nodeRepository.getNode("00000000-0000-0000-0000-000000000001");
    Assertions.assertThat(nodeOpt).isPresent();
    Assertions.assertThat(nodeOpt.get()).isNot(isNodeHidden);
  }

  @Test
  void
  givenANotHiddenNodeAnUserStatusChangedEventMaintenanceWrittenOnMessageBrokerQueueUsersNodesHiddenFlagsShouldBeUnchanged() {
    // Given
    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(
            new SimplePopulatorTextFile(
                "00000000-0000-0000-0000-000000000002", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaac"));

    // When
    UserStatusChangedConsumer userStatusChangedConsumer = new UserStatusChangedConsumer(nodeRepository);
    userStatusChangedConsumer.doHandle(new UserStatusChanged("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaac", UserStatus.MAINTENANCE));

    // Then
    Optional<Node> nodeOpt = nodeRepository.getNode("00000000-0000-0000-0000-000000000002");
    Assertions.assertThat(nodeOpt).isPresent();
    Assertions.assertThat(nodeOpt.get()).isNot(isNodeHidden);
  }
}
