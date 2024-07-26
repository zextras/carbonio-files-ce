// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zextras.carbonio.files.Simulator;
import com.zextras.carbonio.files.Simulator.SimulatorBuilder;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.rest.types.health.DependencyType;
import com.zextras.carbonio.files.rest.types.health.HealthResponse;
import com.zextras.carbonio.files.rest.types.health.ServiceHealth;
import io.netty.handler.codec.http.HttpMethod;
import java.util.Collections;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

class HealthApiIT {

  @Test
  void givenAnHealthServiceTheHealthLiveShouldReturn204StatusCode() {
    // Given
    SimulatorBuilder simulatorBUilder =
        SimulatorBuilder.aSimulator().init().withDatabase().withServiceDiscover();

    try (Simulator simulator = simulatorBUilder.build().start()) {
      com.zextras.carbonio.files.utilities.http.HttpRequest httpRequest =
          com.zextras.carbonio.files.utilities.http.HttpRequest.of(
              HttpMethod.GET.toString(), "/health/live/", null, null);

      // When
      com.zextras.carbonio.files.utilities.http.HttpResponse httpResponse =
          TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

      // Then
      Assertions.assertThat(httpResponse.getStatus()).isEqualTo(204);
      Assertions.assertThat(httpResponse.getBodyPayload()).isEmpty();
    }
  }

  @Test
  void givenAllDependenciesHealthyTheHealthShouldReturn200CodeWithTheHealthStatusOfEachDependency()
      throws Exception {
    // Given
    SimulatorBuilder simulatorBuilder =
        SimulatorBuilder.aSimulator()
            .init()
            .withDatabase()
            .withMessageBroker()
            .withServiceDiscover()
            .withUserManagement(Collections.emptyMap())
            .withStorages()
            .withPreview()
            .withDocsConnector();

    try (Simulator simulator = simulatorBuilder.build().start()) {

      // UserManagement
      MockServerClient userManagementServiceMock = simulator.getUserManagementMock();

      userManagementServiceMock
          .when(HttpRequest.request().withMethod(HttpMethod.GET.toString()).withPath("/health/"))
          .respond(HttpResponse.response().withStatusCode(200));

      // Storages
      MockServerClient storagesMock = simulator.getStoragesMock();

      storagesMock
          .when(
              HttpRequest.request().withMethod(HttpMethod.GET.toString()).withPath("/health/live"))
          .respond(HttpResponse.response().withStatusCode(200));

      // Preview
      MockServerClient previewServiceMock = simulator.getPreviewServiceMock();

      previewServiceMock
          .when(
              HttpRequest.request()
                  .withMethod(HttpMethod.GET.toString())
                  .withPath("/health/ready/"))
          .respond(HttpResponse.response().withStatusCode(200));

      // DocsConnector
      MockServerClient docsConnectorServiceMock = simulator.getDocsConnectorServiceMock();

      docsConnectorServiceMock
          .when(
              HttpRequest.request().withMethod(HttpMethod.GET.toString()).withPath("/health/live/"))
          .respond(HttpResponse.response().withStatusCode(204));

      com.zextras.carbonio.files.utilities.http.HttpRequest httpRequest =
          com.zextras.carbonio.files.utilities.http.HttpRequest.of(
              HttpMethod.GET.toString(), "/health/", null, null);

      // When
      com.zextras.carbonio.files.utilities.http.HttpResponse httpResponse =
          TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

      // Then
      Assertions.assertThat(httpResponse.getStatus()).isEqualTo(200);

      HealthResponse healthStatus =
          new ObjectMapper().readValue(httpResponse.getBodyPayload(), HealthResponse.class);

      Assertions.assertThat(healthStatus.isReady()).isTrue();
      List<ServiceHealth> dependenciesHealth = healthStatus.getDependencies();
      Assertions.assertThat(dependenciesHealth).hasSize(6);

      Assertions.assertThat(dependenciesHealth.get(0).getName()).isEqualTo("database");
      Assertions.assertThat(dependenciesHealth.get(0).isLive()).isTrue();
      Assertions.assertThat(dependenciesHealth.get(0).isReady()).isTrue();
      Assertions.assertThat(dependenciesHealth.get(0).getType()).isEqualTo(DependencyType.REQUIRED);

      Assertions.assertThat(dependenciesHealth.get(1).getName())
          .isEqualTo("carbonio-user-management");
      Assertions.assertThat(dependenciesHealth.get(1).isLive()).isTrue();
      Assertions.assertThat(dependenciesHealth.get(1).isReady()).isTrue();
      Assertions.assertThat(dependenciesHealth.get(1).getType()).isEqualTo(DependencyType.REQUIRED);

      Assertions.assertThat(dependenciesHealth.get(2).getName()).isEqualTo("carbonio-storages");
      Assertions.assertThat(dependenciesHealth.get(2).isLive()).isTrue();
      Assertions.assertThat(dependenciesHealth.get(2).isReady()).isTrue();
      Assertions.assertThat(dependenciesHealth.get(2).getType()).isEqualTo(DependencyType.REQUIRED);

      Assertions.assertThat(dependenciesHealth.get(3).getName()).isEqualTo("carbonio-preview");
      Assertions.assertThat(dependenciesHealth.get(3).isLive()).isTrue();
      Assertions.assertThat(dependenciesHealth.get(3).isReady()).isTrue();
      Assertions.assertThat(dependenciesHealth.get(3).getType()).isEqualTo(DependencyType.OPTIONAL);

      Assertions.assertThat(dependenciesHealth.get(4).getName())
          .isEqualTo("carbonio-docs-connector");
      Assertions.assertThat(dependenciesHealth.get(4).isLive()).isTrue();
      Assertions.assertThat(dependenciesHealth.get(4).isReady()).isTrue();
      Assertions.assertThat(dependenciesHealth.get(4).getType()).isEqualTo(DependencyType.OPTIONAL);

      Assertions.assertThat(dependenciesHealth.get(5).getName())
          .isEqualTo("carbonio-message-broker");
      Assertions.assertThat(dependenciesHealth.get(5).isLive()).isTrue();
      Assertions.assertThat(dependenciesHealth.get(5).isReady()).isTrue();
      Assertions.assertThat(dependenciesHealth.get(5).getType()).isEqualTo(DependencyType.REQUIRED);
    }
  }

  @Test
  void
      givenUserManagementUnreachableAndOtherDependenciesHealthyTheHealthShouldReturn500CodeWithTheHealthStatusOfEachDependency()
          throws Exception {
    // Given
    SimulatorBuilder simulatorBuilder =
        SimulatorBuilder.aSimulator()
            .init()
            .withDatabase()
            .withMessageBroker()
            .withServiceDiscover()
            .withUserManagement(Collections.emptyMap())
            .withStorages()
            .withPreview()
            .withDocsConnector();

    try (Simulator simulator = simulatorBuilder.build().start()) {

      // UserManagement
      MockServerClient userManagementServiceMock = simulator.getUserManagementMock();

      userManagementServiceMock
          .when(HttpRequest.request().withMethod(HttpMethod.GET.toString()).withPath("/health/"))
          .respond(HttpResponse.response().withStatusCode(500));

      // Storages
      MockServerClient storagesMock = simulator.getStoragesMock();

      storagesMock
          .when(
              HttpRequest.request().withMethod(HttpMethod.GET.toString()).withPath("/health/live"))
          .respond(HttpResponse.response().withStatusCode(200));

      // Preview
      MockServerClient previewServiceMock = simulator.getPreviewServiceMock();

      previewServiceMock
          .when(
              HttpRequest.request()
                  .withMethod(HttpMethod.GET.toString())
                  .withPath("/health/ready/"))
          .respond(HttpResponse.response().withStatusCode(200));

      // DocsConnector
      MockServerClient docsConnectorServiceMock = simulator.getDocsConnectorServiceMock();

      docsConnectorServiceMock
          .when(
              HttpRequest.request().withMethod(HttpMethod.GET.toString()).withPath("/health/live/"))
          .respond(HttpResponse.response().withStatusCode(204));

      com.zextras.carbonio.files.utilities.http.HttpRequest httpRequest =
          com.zextras.carbonio.files.utilities.http.HttpRequest.of(
              HttpMethod.GET.toString(), "/health/", null, null);

      // When
      com.zextras.carbonio.files.utilities.http.HttpResponse httpResponse =
          TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

      // Then
      Assertions.assertThat(httpResponse.getStatus()).isEqualTo(500);

      HealthResponse healthStatus =
          new ObjectMapper().readValue(httpResponse.getBodyPayload(), HealthResponse.class);

      Assertions.assertThat(healthStatus.isReady()).isFalse();
      List<ServiceHealth> dependenciesHealth = healthStatus.getDependencies();
      Assertions.assertThat(dependenciesHealth).hasSize(6);

      Assertions.assertThat(dependenciesHealth.get(0).getName()).isEqualTo("database");
      Assertions.assertThat(dependenciesHealth.get(0).isLive()).isTrue();
      Assertions.assertThat(dependenciesHealth.get(0).isReady()).isTrue();
      Assertions.assertThat(dependenciesHealth.get(0).getType()).isEqualTo(DependencyType.REQUIRED);

      Assertions.assertThat(dependenciesHealth.get(1).getName())
          .isEqualTo("carbonio-user-management");
      Assertions.assertThat(dependenciesHealth.get(1).isLive()).isFalse();
      Assertions.assertThat(dependenciesHealth.get(1).isReady()).isFalse();
      Assertions.assertThat(dependenciesHealth.get(1).getType()).isEqualTo(DependencyType.REQUIRED);

      Assertions.assertThat(dependenciesHealth.get(2).getName()).isEqualTo("carbonio-storages");
      Assertions.assertThat(dependenciesHealth.get(2).isLive()).isTrue();
      Assertions.assertThat(dependenciesHealth.get(2).isReady()).isTrue();
      Assertions.assertThat(dependenciesHealth.get(2).getType()).isEqualTo(DependencyType.REQUIRED);

      Assertions.assertThat(dependenciesHealth.get(3).getName()).isEqualTo("carbonio-preview");
      Assertions.assertThat(dependenciesHealth.get(3).isLive()).isTrue();
      Assertions.assertThat(dependenciesHealth.get(3).isReady()).isTrue();
      Assertions.assertThat(dependenciesHealth.get(3).getType()).isEqualTo(DependencyType.OPTIONAL);

      Assertions.assertThat(dependenciesHealth.get(4).getName())
          .isEqualTo("carbonio-docs-connector");
      Assertions.assertThat(dependenciesHealth.get(4).isLive()).isTrue();
      Assertions.assertThat(dependenciesHealth.get(4).isReady()).isTrue();
      Assertions.assertThat(dependenciesHealth.get(4).getType()).isEqualTo(DependencyType.OPTIONAL);

      Assertions.assertThat(dependenciesHealth.get(5).getName())
          .isEqualTo("carbonio-message-broker");
      Assertions.assertThat(dependenciesHealth.get(5).isLive()).isTrue();
      Assertions.assertThat(dependenciesHealth.get(5).isReady()).isTrue();
      Assertions.assertThat(dependenciesHealth.get(5).getType()).isEqualTo(DependencyType.REQUIRED);
    }
  }

  @Test
  void givenAllMandatoryDependenciesHealthyTheHealthReadyShouldReturn204StatusCode() {
    // Given
    SimulatorBuilder simulatorBuilder =
        SimulatorBuilder.aSimulator()
            .init()
            .withDatabase()
            .withMessageBroker()
            .withServiceDiscover()
            .withUserManagement(Collections.emptyMap())
            .withStorages();

    try (Simulator simulator = simulatorBuilder.build().start()) {

      // UserManagement
      MockServerClient userManagementServiceMock = simulator.getUserManagementMock();

      userManagementServiceMock
          .when(HttpRequest.request().withMethod(HttpMethod.GET.toString()).withPath("/health/"))
          .respond(HttpResponse.response().withStatusCode(200));

      // Storages
      MockServerClient storagesMock = simulator.getStoragesMock();

      storagesMock
          .when(
              HttpRequest.request().withMethod(HttpMethod.GET.toString()).withPath("/health/live"))
          .respond(HttpResponse.response().withStatusCode(200));

      com.zextras.carbonio.files.utilities.http.HttpRequest httpRequest =
          com.zextras.carbonio.files.utilities.http.HttpRequest.of(
              HttpMethod.GET.toString(), "/health/ready/", null, null);

      // When
      com.zextras.carbonio.files.utilities.http.HttpResponse httpResponse =
          TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

      // Then
      Assertions.assertThat(httpResponse.getStatus()).isEqualTo(204);
      Assertions.assertThat(httpResponse.getBodyPayload()).isEmpty();
    }
  }

  @Test
  void
      givenUserManagementUnreachableAndOtherMandatoryDependenciesReachableTheHealthReadyShouldReturn500StatusCode() {
    // Given
    SimulatorBuilder simulatorBuilder =
        SimulatorBuilder.aSimulator()
            .init()
            .withDatabase()
            .withMessageBroker()
            .withServiceDiscover()
            .withUserManagement(Collections.emptyMap())
            .withStorages();

    try (Simulator simulator = simulatorBuilder.build().start()) {

      // UserManagement
      MockServerClient userManagementServiceMock = simulator.getUserManagementMock();

      userManagementServiceMock
          .when(HttpRequest.request().withMethod(HttpMethod.GET.toString()).withPath("/health/"))
          .respond(HttpResponse.response().withStatusCode(502));

      // Storages
      MockServerClient storagesMock = simulator.getStoragesMock();

      storagesMock
          .when(
              HttpRequest.request().withMethod(HttpMethod.GET.toString()).withPath("/health/live"))
          .respond(HttpResponse.response().withStatusCode(200));

      com.zextras.carbonio.files.utilities.http.HttpRequest httpRequest =
          com.zextras.carbonio.files.utilities.http.HttpRequest.of(
              HttpMethod.GET.toString(), "/health/ready/", null, null);

      // When
      com.zextras.carbonio.files.utilities.http.HttpResponse httpResponse =
          TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

      // Then
      Assertions.assertThat(httpResponse.getStatus()).isEqualTo(500);
      Assertions.assertThat(httpResponse.getBodyPayload()).isEmpty();
    }
  }

  @Test
  void
      givenStoragesUnreachableAndOtherMandatoryDependenciesReachableTheHealthReadyShouldReturn502StatusCode() {
    // Given
    SimulatorBuilder simulatorBuilder =
        SimulatorBuilder.aSimulator()
            .init()
            .withDatabase()
            .withMessageBroker()
            .withServiceDiscover()
            .withUserManagement(Collections.emptyMap())
            .withStorages();

    try (Simulator simulator = simulatorBuilder.build().start()) {

      // UserManagement
      MockServerClient userManagementServiceMock = simulator.getUserManagementMock();

      userManagementServiceMock
          .when(HttpRequest.request().withMethod(HttpMethod.GET.toString()).withPath("/health/"))
          .respond(HttpResponse.response().withStatusCode(200));

      // Storages
      MockServerClient storagesMock = simulator.getStoragesMock();

      storagesMock
          .when(
              HttpRequest.request().withMethod(HttpMethod.GET.toString()).withPath("/health/live"))
          .respond(HttpResponse.response().withStatusCode(502));

      com.zextras.carbonio.files.utilities.http.HttpRequest httpRequest =
          com.zextras.carbonio.files.utilities.http.HttpRequest.of(
              HttpMethod.GET.toString(), "/health/ready/", null, null);

      // When
      com.zextras.carbonio.files.utilities.http.HttpResponse httpResponse =
          TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

      // Then
      Assertions.assertThat(httpResponse.getStatus()).isEqualTo(500);
      Assertions.assertThat(httpResponse.getBodyPayload()).isEmpty();
    }
  }

  @Test
  void
  givenMessageBrokerUnreachableAndOtherMandatoryDependenciesReachableTheHealthReadyShouldReturn500StatusCode() {
    // Given
    SimulatorBuilder simulatorBuilder =
        SimulatorBuilder.aSimulator()
            .init()
            .withDatabase()
            .withServiceDiscover()
            .withUserManagement(Collections.emptyMap())
            .withStorages();

    try (Simulator simulator = simulatorBuilder.build().start()) {

      // UserManagement
      MockServerClient userManagementServiceMock = simulator.getUserManagementMock();

      userManagementServiceMock
          .when(HttpRequest.request().withMethod(HttpMethod.GET.toString()).withPath("/health/"))
          .respond(HttpResponse.response().withStatusCode(200));

      // Storages
      MockServerClient storagesMock = simulator.getStoragesMock();

      storagesMock
          .when(
              HttpRequest.request().withMethod(HttpMethod.GET.toString()).withPath("/health/live"))
          .respond(HttpResponse.response().withStatusCode(200));

      com.zextras.carbonio.files.utilities.http.HttpRequest httpRequest =
          com.zextras.carbonio.files.utilities.http.HttpRequest.of(
              HttpMethod.GET.toString(), "/health/ready/", null, null);

      // When
      com.zextras.carbonio.files.utilities.http.HttpResponse httpResponse =
          TestUtils.sendRequest(httpRequest, simulator.getNettyChannel());

      // Then
      Assertions.assertThat(httpResponse.getStatus()).isEqualTo(500);
      Assertions.assertThat(httpResponse.getBodyPayload()).isEmpty();
    }
  }
}
