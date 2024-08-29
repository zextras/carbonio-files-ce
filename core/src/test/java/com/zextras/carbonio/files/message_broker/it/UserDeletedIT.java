// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.message_broker.it;

import com.google.gson.Gson;
import com.google.inject.Injector;
import com.zextras.carbonio.files.Simulator;
import com.zextras.carbonio.files.Simulator.SimulatorBuilder;
import com.zextras.carbonio.files.api.utilities.DatabasePopulator;
import com.zextras.carbonio.files.api.utilities.entities.SimplePopulatorTextFile;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.TombstoneRepository;
import com.zextras.carbonio.files.message_broker.consumers.UserDeletedConsumer;
import com.zextras.filestore.api.Filestore;
import com.zextras.carbonio.message_broker.events.services.mailbox.UserDeleted;
import com.zextras.filestore.model.IdentifierType;
import com.zextras.storages.internal.pojo.Query;
import com.zextras.storages.internal.pojo.StoragesBulkDeleteResponse;
import io.netty.handler.codec.http.HttpMethod;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.MediaType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Here I mock an event emitted and received because if I was to emit a real event I could not know
 * when it will be consumed
 */
class UserDeletedIT {

  static Simulator simulator;
  static NodeRepository nodeRepository;
  static FileVersionRepository fileVersionRepository;
  static TombstoneRepository tombstoneRepository;
  static Filestore fileStore;

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
            .withStorages()
            .build()
            .start();
    final Injector injector = simulator.getInjector();
    nodeRepository = injector.getInstance(NodeRepository.class);
    fileVersionRepository = injector.getInstance(FileVersionRepository.class);
    tombstoneRepository = injector.getInstance(TombstoneRepository.class);
    fileStore = injector.getInstance(Filestore.class);
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
  givenAnUserWithSomeNodesAndAUserDeletedEventNodesShouldBeDeletedFromFilesStoragesAndTombstone() throws Exception {
    // Given
    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(
            new SimplePopulatorTextFile(
                "00000000-0000-0000-0000-000000000000", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));

    StoragesBulkDeleteResponse storagesBulkDeleteResponse = new StoragesBulkDeleteResponse();
    Query query = new Query();
    query.setNode("00000000-0000-0000-0000-000000000000");
    query.setType(IdentifierType.files.getValue());
    query.setVersion(1L);
    storagesBulkDeleteResponse.setIds(List.of(query));

    simulator
        .getStoragesMock()
        .when(
            org.mockserver.model.HttpRequest.request()
                .withMethod(HttpMethod.POST.toString())
                .withPath("/bulk-delete"))
        .respond(
            HttpResponse.response()
                .withStatusCode(200)
                .withContentType(MediaType.APPLICATION_JSON)
                .withBody(new Gson().toJson(storagesBulkDeleteResponse))
        );

    // When
    UserDeletedConsumer userDeletedConsumer = new UserDeletedConsumer(fileStore, nodeRepository, fileVersionRepository, tombstoneRepository);
    userDeletedConsumer.doHandle(new UserDeleted("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));

    // Then
    Assertions.assertThat(nodeRepository.getNode("00000000-0000-0000-0000-000000000000").isEmpty()).isTrue();
  }
}
