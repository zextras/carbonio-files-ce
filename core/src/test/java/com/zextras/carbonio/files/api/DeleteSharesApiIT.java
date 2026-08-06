// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.api;

import com.google.inject.Injector;
import com.zextras.carbonio.files.Simulator;
import com.zextras.carbonio.files.Simulator.SimulatorBuilder;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.api.utilities.DatabasePopulator;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.api.utilities.entities.SimplePopulatorTextFile;
import com.zextras.carbonio.files.dal.dao.ebean.ACL.SharePermission;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.ShareRepository;
import com.zextras.carbonio.files.utilities.http.HttpRequest;
import com.zextras.carbonio.files.utilities.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class DeleteSharesApiIT {

  static Simulator simulator;
  static NodeRepository nodeRepository;
  static ShareRepository shareRepository;

  @BeforeAll
  static void init() {
    simulator =
        SimulatorBuilder.aSimulator()
            .init()
            .withDatabase()
            .withServiceDiscover()
            .withUserManagement(
                Map.of(
                    "fake-token",
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                    "fake-token-account-for-sharing",
                    "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                    "fake-token-account-for-sharing-2",
                    "cccccccc-cccc-cccc-cccc-cccccccccccc"))
            .build()
            .start();
    final Injector injector = simulator.getInjector();
    nodeRepository = injector.getInstance(NodeRepository.class);
    shareRepository = injector.getInstance(ShareRepository.class);
  }

  @AfterEach
  void cleanUp() {
    simulator.resetDatabase();
  }

  @AfterAll
  static void cleanUpAll() {
    simulator.stopAll();
  }

  void createFile(String nodeId, String ownerId) {
    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addNode(new SimplePopulatorTextFile(nodeId, ownerId));
  }

  void createShare(String nodeId, String targetUserId, SharePermission permission) {
    DatabasePopulator.aNodePopulator(simulator.getInjector())
        .addShare(nodeId, targetUserId, permission);
  }

  @Test
  void givenExistingSharesTheDeleteSharesShouldDeleteAllAndReturnTargetIds() {
    // Given
    createFile("00000000-0000-0000-0000-000000000000", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    createShare(
        "00000000-0000-0000-0000-000000000000",
        "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
        SharePermission.READ_ONLY);
    createShare(
        "00000000-0000-0000-0000-000000000000",
        "cccccccc-cccc-cccc-cccc-cccccccccccc",
        SharePermission.READ_ONLY);

    final String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("deleteShares")
            .withString("node_id", "00000000-0000-0000-0000-000000000000")
            .withListOfStrings(
                "share_target_ids",
                new String[] {
                  "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", "cccccccc-cccc-cccc-cccc-cccccccccccc"
                })
            .withWantedResultFormat("")
            .build();

    final HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);

    // When
    final HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    final List<String> deletedIds =
        (List<String>)
            TestUtils.jsonResponseToValue(httpResponse.getBodyPayload(), "deleteShares")
                .orElse(List.of());

    Assertions.assertThat(deletedIds)
        .hasSize(2)
        .containsExactly(
            "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", "cccccccc-cccc-cccc-cccc-cccccccccccc");

    Assertions.assertThat(
            shareRepository.getShare(
                "00000000-0000-0000-0000-000000000000", "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"))
        .isEmpty();

    Assertions.assertThat(
            shareRepository.getShare(
                "00000000-0000-0000-0000-000000000000", "cccccccc-cccc-cccc-cccc-cccccccccccc"))
        .isEmpty();
  }

  @Test
  void givenOneNonExistingShareTheDeleteSharesShouldReturnPartialSuccessWithErrors() {
    // Given
    createFile("00000000-0000-0000-0000-000000000000", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    createShare(
        "00000000-0000-0000-0000-000000000000",
        "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
        SharePermission.READ_ONLY);

    final String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("deleteShares")
            .withString("node_id", "00000000-0000-0000-0000-000000000000")
            .withListOfStrings(
                "share_target_ids",
                new String[] {
                  "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", "cccccccc-cccc-cccc-cccc-cccccccccccc"
                })
            .withWantedResultFormat("")
            .build();

    final HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);

    // When
    final HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    final List<String> deletedIds =
        (List<String>)
            TestUtils.jsonResponseToValue(httpResponse.getBodyPayload(), "deleteShares")
                .orElse(List.of());

    Assertions.assertThat(deletedIds)
        .hasSize(1)
        .containsExactly("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    final List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly(
            "Could not find share for node: 00000000-0000-0000-0000-000000000000"
                + " and user cccccccc-cccc-cccc-cccc-cccccccccccc");
  }

  @Test
  void givenATargetUserTheDeleteSharesShouldAllowSelfDeletion() {
    // Given
    createFile("00000000-0000-0000-0000-000000000000", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    createShare(
        "00000000-0000-0000-0000-000000000000",
        "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
        SharePermission.READ_ONLY);

    final String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("deleteShares")
            .withString("node_id", "00000000-0000-0000-0000-000000000000")
            .withListOfStrings(
                "share_target_ids", new String[] {"bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"})
            .withWantedResultFormat("")
            .build();

    final HttpRequest httpRequest =
        HttpRequest.of(
            "POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token-account-for-sharing", bodyPayload);

    // When
    final HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    final List<String> deletedIds =
        (List<String>)
            TestUtils.jsonResponseToValue(httpResponse.getBodyPayload(), "deleteShares")
                .orElse(List.of());

    Assertions.assertThat(deletedIds)
        .hasSize(1)
        .containsExactly("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    Assertions.assertThat(
            shareRepository.getShare(
                "00000000-0000-0000-0000-000000000000", "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"))
        .isEmpty();
  }

  @Test
  void givenAUserWithoutPermissionsTheDeleteSharesShouldReturnErrors() {
    // Given
    createFile("00000000-0000-0000-0000-000000000000", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    createShare(
        "00000000-0000-0000-0000-000000000000",
        "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
        SharePermission.READ_ONLY);

    final String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("deleteShares")
            .withString("node_id", "00000000-0000-0000-0000-000000000000")
            .withListOfStrings(
                "share_target_ids", new String[] {"bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"})
            .withWantedResultFormat("")
            .build();

    // cccccccc is not the target and has no share permission
    final HttpRequest httpRequest =
        HttpRequest.of(
            "POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token-account-for-sharing-2", bodyPayload);

    // When
    final HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    final List<String> deletedIds =
        (List<String>)
            TestUtils.jsonResponseToValue(httpResponse.getBodyPayload(), "deleteShares")
                .orElse(List.of());

    Assertions.assertThat(deletedIds).isEmpty();

    final List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors).hasSize(1);
  }

  @Test
  void givenOwnerAsTargetTheDeleteSharesShouldReturnErrorForOwner() {
    // Given
    createFile("00000000-0000-0000-0000-000000000000", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    createShare(
        "00000000-0000-0000-0000-000000000000",
        "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
        SharePermission.READ_ONLY);

    final String bodyPayload =
        GraphqlCommandBuilder.aMutationBuilder("deleteShares")
            .withString("node_id", "00000000-0000-0000-0000-000000000000")
            .withListOfStrings(
                "share_target_ids",
                new String[] {
                  "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
                })
            .withWantedResultFormat("")
            .build();

    final HttpRequest httpRequest =
        HttpRequest.of("POST", "/graphql/", "ZM_AUTH_TOKEN=fake-token", bodyPayload);

    // When
    final HttpResponse httpResponse =
        TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

    // Then
    Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

    final List<String> deletedIds =
        (List<String>)
            TestUtils.jsonResponseToValue(httpResponse.getBodyPayload(), "deleteShares")
                .orElse(List.of());

    Assertions.assertThat(deletedIds)
        .hasSize(1)
        .containsExactly("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    final List<String> errors = TestUtils.jsonResponseToErrors(httpResponse.getBodyPayload());
    Assertions.assertThat(errors)
        .hasSize(1)
        .containsExactly(
            "Could not find share for node: 00000000-0000-0000-0000-000000000000"
                + " and user aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  }
}
