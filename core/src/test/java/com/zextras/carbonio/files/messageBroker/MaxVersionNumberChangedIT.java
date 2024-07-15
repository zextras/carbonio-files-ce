// SPDX-FileCopyrightText: 2023 Zextras <https://www.zextras.com>
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
    messageBrokerManager.startAllConsumers();
    KvChanged kvChanged = new KvChanged("carbonio-files/max-number-of-versions", "3");
    messageBrokerManager.getMessageBrokerClient().withCurrentService(Service.SERVICE_DISCOVER).publish(kvChanged);

    // Unfortunately there's no simple way of knowing when an event has been consumed without changing the logic of
    // the consumer itself; this solution while ugly is quite clear and fast enough.
    Thread.sleep(2000);

    // Then
    List<FileVersion> fileVersionList = fileVersionRepository.getFileVersions("00000000-0000-0000-0000-000000000000", true);
    Assertions.assertThat(fileVersionList.size()).isEqualTo(3);
    Assertions.assertThat(fileVersionList.get(0).getVersion()).isEqualTo(3);
    Assertions.assertThat(fileVersionList.get(1).getVersion()).isEqualTo(4);
    Assertions.assertThat(fileVersionList.get(2).getVersion()).isEqualTo(5);
  }

  @Test
  void givenANodeWithMultipleVersionWhenMaxNumberVersionChangesLeastRecentVersionsShouldBeDeletedExcludingKeepForeverAndCurrent() throws InterruptedException {
    // Given
    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(new SimplePopulatorTextFile("00000000-0000-0000-0000-000000000000", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"))
        .addVersion("00000000-0000-0000-0000-000000000000", true)
        .addVersion("00000000-0000-0000-0000-000000000000", true)
        .addVersion("00000000-0000-0000-0000-000000000000")
        .addVersion("00000000-0000-0000-0000-000000000000");

    // When
    messageBrokerManager.startAllConsumers();
    KvChanged kvChanged = new KvChanged("carbonio-files/max-number-of-versions", "2");
    messageBrokerManager.getMessageBrokerClient().withCurrentService(Service.SERVICE_DISCOVER).publish(kvChanged);

    // Unfortunately there's no simple way of knowing when an event has been consumed without changing the logic of
    // the consumer itself; this solution while ugly is quite clear and fast enough.
    Thread.sleep(2000);

    // Then
    List<FileVersion> fileVersionList = fileVersionRepository.getFileVersions("00000000-0000-0000-0000-000000000000", true);
    // Since keepForever versions and current one can't be deleted, here we can expect a number of versions greater than the
    // max number of versions allowed, it's a (probably) rare corner case but ynk.
    Assertions.assertThat(fileVersionList.size()).isEqualTo(3);
    Assertions.assertThat(fileVersionList.get(0).getVersion()).isEqualTo(2);
    Assertions.assertThat(fileVersionList.get(1).getVersion()).isEqualTo(3);
    Assertions.assertThat(fileVersionList.get(2).getVersion()).isEqualTo(5);
  }
}
