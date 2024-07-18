// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.messageBroker;

import com.google.inject.Injector;
import com.zextras.carbonio.files.Simulator;
import com.zextras.carbonio.files.Simulator.SimulatorBuilder;
import com.zextras.carbonio.files.api.utilities.DatabasePopulator;
import com.zextras.carbonio.files.api.utilities.entities.SimplePopulatorTextFile;
import com.zextras.carbonio.files.dal.dao.ebean.FileVersion;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.messageBroker.consumers.KvChangedConsumer;
import com.zextras.carbonio.files.messageBroker.interfaces.MessageBrokerManager;
import com.zextras.carbonio.message_broker.config.enums.Service;
import com.zextras.carbonio.message_broker.events.services.mailbox.UserStatusChanged;
import com.zextras.carbonio.message_broker.events.services.mailbox.enums.UserStatus;
import com.zextras.carbonio.message_broker.events.services.service_discover.KvChanged;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

class MaxVersionNumberChangedIT {

  static Simulator simulator;
  static NodeRepository nodeRepository;
  static FileVersionRepository fileVersionRepository;
  static MessageBrokerManager messageBrokerManager;

  @BeforeAll
  static void init() {
    Map<String, String> users = new HashMap<String, String>();
    users.put("fake-token", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    users.put("fake-token2", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaab");
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
    messageBrokerManager = injector.getInstance(MessageBrokerManager.class);
    fileVersionRepository = injector.getInstance(FileVersionRepository.class);
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
  void givenANodeWithMultipleVersionWhenMaxNumberVersionChangesLeastRecentVersionsShouldBeDeleted() throws InterruptedException {
    // Given
    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(new SimplePopulatorTextFile("00000000-0000-0000-0000-000000000000", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"))
        .addVersion("00000000-0000-0000-0000-000000000000")
        .addVersion("00000000-0000-0000-0000-000000000000")
        .addVersion("00000000-0000-0000-0000-000000000000")
        .addVersion("00000000-0000-0000-0000-000000000000");

    // When
    KvChanged kvChanged = new KvChanged("carbonio-files/max-number-of-versions", "3");
    KvChangedConsumer kvChangedConsumer = new KvChangedConsumer(nodeRepository, fileVersionRepository);
    kvChangedConsumer.doHandle(kvChanged);

    // Then
    List<FileVersion> fileVersionList = fileVersionRepository.getFileVersions("00000000-0000-0000-0000-000000000000", true);
    Assertions.assertThat(fileVersionList.size() == 3).isTrue();
    Assertions.assertThat(fileVersionList.get(0).getVersion()).isEqualTo(3);
    Assertions.assertThat(fileVersionList.get(1).getVersion()).isEqualTo(4);
    Assertions.assertThat(fileVersionList.get(2).getVersion()).isEqualTo(5);
  }

  @Test
  void givenANodeWithMultipleVersionWhenMaxNumberVersionChangesLeastRecentVersionsShouldBeDeletedExcludingKeepForeverAndCurrent() throws InterruptedException {
    // Given
    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(new SimplePopulatorTextFile("00000000-0000-0000-0000-000000000001", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaab"))
        .addVersion("00000000-0000-0000-0000-000000000001", true)
        .addVersion("00000000-0000-0000-0000-000000000001", true)
        .addVersion("00000000-0000-0000-0000-000000000001")
        .addVersion("00000000-0000-0000-0000-000000000001");

    // When
    KvChanged kvChanged = new KvChanged("carbonio-files/max-number-of-versions", "2");
    KvChangedConsumer kvChangedConsumer = new KvChangedConsumer(nodeRepository, fileVersionRepository);
    kvChangedConsumer.doHandle(kvChanged);

    // Then
    List<FileVersion> fileVersionList = fileVersionRepository.getFileVersions("00000000-0000-0000-0000-000000000001", true);
    Assertions.assertThat(fileVersionList.size() == 3).isTrue();
    Assertions.assertThat(fileVersionList.get(0).getVersion()).isEqualTo(2);
    Assertions.assertThat(fileVersionList.get(1).getVersion()).isEqualTo(3);
    Assertions.assertThat(fileVersionList.get(2).getVersion()).isEqualTo(5);
  }
}
