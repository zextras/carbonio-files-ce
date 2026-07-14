// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zextras.carbonio.files.acceptance.seam.FilesTestApp;
import com.zextras.carbonio.files.acceptance.seam.impl.GuiceNettyFilesTestAppBuilder;
import com.zextras.carbonio.files.rest.types.health.DependencyType;
import com.zextras.carbonio.files.rest.types.health.HealthResponse;
import com.zextras.carbonio.files.rest.types.health.ServiceHealth;
import com.zextras.carbonio.files.utilities.http.HttpRequest;
import com.zextras.carbonio.files.utilities.http.HttpResponse;
import java.util.Collections;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class HealthApiIT {

  @Test
  void givenAnHealthServiceTheHealthLiveShouldReturn204StatusCode() {
    // Given
    try (FilesTestApp app =
        GuiceNettyFilesTestAppBuilder.aFilesTestApp()
            .withDatabase()
            .withServiceDiscover()
            .build()) {
      HttpRequest httpRequest = HttpRequest.of("GET", "/health/live/", null, null);

      // When
      HttpResponse httpResponse = app.send(httpRequest);

      // Then
      Assertions.assertThat(httpResponse.getStatus()).isEqualTo(204);
      Assertions.assertThat(httpResponse.getBodyPayload()).isEmpty();
    }
  }

  @Test
  void givenAllDependenciesHealthyTheHealthShouldReturn200CodeWithTheHealthStatusOfEachDependency()
      throws Exception {
    // Given
    try (FilesTestApp app =
        GuiceNettyFilesTestAppBuilder.aFilesTestApp()
            .withDatabase()
            .withMessageBroker()
            .withServiceDiscover()
            .withUserManagement(Collections.emptyMap())
            .withStorages()
            .withPreview()
            .withDocsConnector()
            .build()) {

      // UserManagement health: the InProcess gRPC server is running, so the channel
      // will be in READY/IDLE state and isUserManagementLive() returns true.

      app.mocks().storagesLive();
      app.mocks().previewReady();
      app.mocks().docsConnectorLive();

      HttpRequest httpRequest = HttpRequest.of("GET", "/health/", null, null);

      // When
      HttpResponse httpResponse = app.send(httpRequest);

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
      Assertions.assertThat(dependenciesHealth.get(5).getType()).isEqualTo(DependencyType.OPTIONAL);
    }
  }

  @Test
  void
      givenUserManagementUnreachableAndOtherDependenciesHealthyTheHealthShouldReturn500CodeWithTheHealthStatusOfEachDependency()
          throws Exception {
    // Given: UM gRPC InProcess server is started then shut down to simulate UM being unreachable
    try (FilesTestApp app =
        GuiceNettyFilesTestAppBuilder.aFilesTestApp()
            .withDatabase()
            .withMessageBroker()
            .withServiceDiscover()
            .withUserManagement(Collections.emptyMap())
            .withStorages()
            .withPreview()
            .withDocsConnector()
            .build()) {

      // Shut down the UM gRPC server to simulate UM being unreachable.
      // The channel will transition to TRANSIENT_FAILURE state.
      app.mocks().userManagementDown();

      app.mocks().storagesLive();
      app.mocks().previewReady();
      app.mocks().docsConnectorLive();

      HttpRequest httpRequest = HttpRequest.of("GET", "/health/", null, null);

      // When
      HttpResponse httpResponse = app.send(httpRequest);

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
      Assertions.assertThat(dependenciesHealth.get(5).getType()).isEqualTo(DependencyType.OPTIONAL);
    }
  }

  @Test
  void givenAllMandatoryDependenciesHealthyTheHealthReadyShouldReturn204StatusCode() {
    // Given
    try (FilesTestApp app =
        GuiceNettyFilesTestAppBuilder.aFilesTestApp()
            .withDatabase()
            .withMessageBroker()
            .withServiceDiscover()
            .withUserManagement(Collections.emptyMap())
            .withStorages()
            .build()) {

      // UserManagement health: the InProcess gRPC server is running, so the channel
      // will be in READY/IDLE state and isUserManagementLive() returns true.

      app.mocks().storagesLive();

      HttpRequest httpRequest = HttpRequest.of("GET", "/health/ready/", null, null);

      // When
      HttpResponse httpResponse = app.send(httpRequest);

      // Then
      Assertions.assertThat(httpResponse.getStatus()).isEqualTo(204);
      Assertions.assertThat(httpResponse.getBodyPayload()).isEmpty();
    }
  }

  @Test
  void
      givenUserManagementUnreachableAndOtherMandatoryDependenciesReachableTheHealthReadyShouldReturn500StatusCode() {
    // Given: UM gRPC InProcess server is started then shut down to simulate UM being unreachable
    try (FilesTestApp app =
        GuiceNettyFilesTestAppBuilder.aFilesTestApp()
            .withDatabase()
            .withMessageBroker()
            .withServiceDiscover()
            .withUserManagement(Collections.emptyMap())
            .withStorages()
            .build()) {

      // Shut down the UM gRPC server to simulate UM being unreachable
      app.mocks().userManagementDown();

      app.mocks().storagesLive();

      HttpRequest httpRequest = HttpRequest.of("GET", "/health/ready/", null, null);

      // When
      HttpResponse httpResponse = app.send(httpRequest);

      // Then
      Assertions.assertThat(httpResponse.getStatus()).isEqualTo(500);
      Assertions.assertThat(httpResponse.getBodyPayload()).isEmpty();
    }
  }

  @Test
  void
      givenStoragesUnreachableAndOtherMandatoryDependenciesReachableTheHealthReadyShouldReturn502StatusCode() {
    // Given
    try (FilesTestApp app =
        GuiceNettyFilesTestAppBuilder.aFilesTestApp()
            .withDatabase()
            .withMessageBroker()
            .withServiceDiscover()
            .withUserManagement(Collections.emptyMap())
            .withStorages()
            .build()) {

      // UserManagement health: the InProcess gRPC server is running, so the channel
      // will be in READY/IDLE state and isUserManagementLive() returns true.

      app.mocks().storagesUnreachable();

      HttpRequest httpRequest = HttpRequest.of("GET", "/health/ready/", null, null);

      // When
      HttpResponse httpResponse = app.send(httpRequest);

      // Then
      Assertions.assertThat(httpResponse.getStatus()).isEqualTo(500);
      Assertions.assertThat(httpResponse.getBodyPayload()).isEmpty();
    }
  }

  @Test
  void
  givenMessageBrokerUnreachableAndOtherMandatoryDependenciesReachableTheHealthReadyShouldReturn204StatusCode() {
    // Given
    try (FilesTestApp app =
        GuiceNettyFilesTestAppBuilder.aFilesTestApp()
            .withDatabase()
            .withServiceDiscover()
            .withUserManagement(Collections.emptyMap())
            .withStorages()
            .build()) {

      // UserManagement health: the InProcess gRPC server is running, so the channel
      // will be in READY/IDLE state and isUserManagementLive() returns true.

      app.mocks().storagesLive();

      HttpRequest httpRequest = HttpRequest.of("GET", "/health/ready/", null, null);

      // When
      HttpResponse httpResponse = app.send(httpRequest);

      // Then
      Assertions.assertThat(httpResponse.getStatus()).isEqualTo(204);
      Assertions.assertThat(httpResponse.getBodyPayload()).isEmpty();
    }
  }
}
