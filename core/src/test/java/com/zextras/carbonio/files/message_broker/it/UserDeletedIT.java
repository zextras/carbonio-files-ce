// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.message_broker.it;

import com.google.inject.Injector;
import com.zextras.carbonio.files.Simulator;
import com.zextras.carbonio.files.Simulator.SimulatorBuilder;
import com.zextras.carbonio.files.api.utilities.DatabasePopulator;
import com.zextras.carbonio.files.api.utilities.entities.SimplePopulatorTextFile;
import com.zextras.carbonio.files.config.FilesConfig;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.TombstoneRepository;
import com.zextras.carbonio.files.message_broker.consumers.UserDeletedConsumer;
import com.zextras.carbonio.files.message_broker.consumers.UserStatusChangedConsumer;
import com.zextras.carbonio.message_broker.events.services.mailbox.UserDeleted;
import com.zextras.carbonio.message_broker.events.services.mailbox.UserStatusChanged;
import com.zextras.carbonio.message_broker.events.services.mailbox.enums.UserStatus;
import org.assertj.core.api.Assertions;
import org.assertj.core.api.Condition;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Here I mock an event emitted and received because if I was to emit a real event I could not know
 * when it will be consumed
 */
class UserDeletedIT {

  static Simulator simulator;
  static NodeRepository nodeRepository;
  static FilesConfig filesConfig;
  static FileVersionRepository fileVersionRepository;
  static TombstoneRepository tombstoneRepository;

  @BeforeAll
  static void init() {
    Map<String, String> users = new HashMap<String, String>();
    users.put("fake-token", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
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
  givenAnUserWithSomeNodesAndAUserDeletedEventNodesShouldBeDeletedFromFilesStoragesAndTombstone() {
    // Given
    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(
            new SimplePopulatorTextFile(
                "00000000-0000-0000-0000-000000000000", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));

    // When
    UserDeletedConsumer userDeletedConsumer = new UserDeletedConsumer(filesConfig, nodeRepository, fileVersionRepository, tombstoneRepository);
    userDeletedConsumer.doHandle(new UserDeleted("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));

    // Then
    Optional<Node> nodeOpt = nodeRepository.getNode("00000000-0000-0000-0000-000000000000");

  }
}
