// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.acceptance;

import com.zextras.carbonio.files.FilesStackTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zextras.carbonio.files.acceptance.seam.FilesTestApp;
import com.zextras.carbonio.files.acceptance.seam.impl.QuarkusFilesTestAppBuilder;
import com.zextras.carbonio.files.rest.types.health.DependencyType;
import com.zextras.carbonio.files.rest.types.health.HealthResponse;
import com.zextras.carbonio.files.rest.types.health.ServiceHealth;
import com.zextras.carbonio.files.utilities.http.HttpRequest;
import com.zextras.carbonio.files.utilities.http.HttpResponse;
import java.util.Collections;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class HealthApiIT {

  @Test
  void givenAnHealthServiceTheHealthLiveShouldReturn204StatusCode() {
    // Given
    try (FilesTestApp app =
        QuarkusFilesTestAppBuilder.aFilesTestApp()
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
        QuarkusFilesTestAppBuilder.aFilesTestApp()
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

      // No RabbitMQ broker is stood up in this acceptance stack (see FilesStackTestResource /
      // BrokerEffectsApiIT): MessageBrokerManagerImpl.healthCheck() attempts a real connection and
      // degrades gracefully to false, matching the documented "no broker in the acceptance suite"
      // reality. This is itself informational-only and, per the self-only house pattern, does not
      // affect overall readiness (asserted above as true).
      Assertions.assertThat(dependenciesHealth.get(5).getName())
          .isEqualTo("carbonio-message-broker");
      Assertions.assertThat(dependenciesHealth.get(5).isLive()).isFalse();
      Assertions.assertThat(dependenciesHealth.get(5).isReady()).isFalse();
      Assertions.assertThat(dependenciesHealth.get(5).getType()).isEqualTo(DependencyType.OPTIONAL);
    }
  }

  @Test
  void
      givenUserManagementUnreachableAndOtherDependenciesHealthyTheHealthShouldReturn200CodeWithReadyTrueSelfOnly()
          throws Exception {
    // Given: UM gRPC InProcess server is started then shut down to simulate UM being unreachable.
    // Self-only house pattern: a downstream dependency being down is informational only and must
    // NOT flip carbonio-files' own readiness — /health still returns 200 with ready=true, and UM
    // is reported as an unhealthy (not live) entry in the dependencies list.
    try (FilesTestApp app =
        QuarkusFilesTestAppBuilder.aFilesTestApp()
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

      // No RabbitMQ broker is stood up in this acceptance stack (see FilesStackTestResource /
      // BrokerEffectsApiIT): MessageBrokerManagerImpl.healthCheck() attempts a real connection and
      // degrades gracefully to false. Informational-only; does not affect readiness (true above).
      Assertions.assertThat(dependenciesHealth.get(5).getName())
          .isEqualTo("carbonio-message-broker");
      Assertions.assertThat(dependenciesHealth.get(5).isLive()).isFalse();
      Assertions.assertThat(dependenciesHealth.get(5).isReady()).isFalse();
      Assertions.assertThat(dependenciesHealth.get(5).getType()).isEqualTo(DependencyType.OPTIONAL);
    }
  }

  @Test
  void givenAllMandatoryDependenciesHealthyTheHealthReadyShouldReturn204StatusCode() {
    // Given
    try (FilesTestApp app =
        QuarkusFilesTestAppBuilder.aFilesTestApp()
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
      givenUserManagementUnreachableTheHealthReadyShouldReturn204StatusCodeSelfOnly()
          throws Exception {
    // Given: UM gRPC InProcess server is started then shut down to simulate UM being unreachable.
    // Self-only house pattern: /health/ready is gated on THIS service's own database only — a
    // downstream dependency (user-management) being down is informational and never flips
    // readiness.
    try (FilesTestApp app =
        QuarkusFilesTestAppBuilder.aFilesTestApp()
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

      // Then: readiness stays healthy regardless of user-management being down.
      Assertions.assertThat(httpResponse.getStatus()).isEqualTo(204);
      Assertions.assertThat(httpResponse.getBodyPayload()).isEmpty();

      // And: user-management is reported informational-only (not live) in the /health payload,
      // without affecting the overall ready flag.
      HttpResponse healthResponse = app.send(HttpRequest.of("GET", "/health/", null, null));
      HealthResponse healthStatus =
          new ObjectMapper().readValue(healthResponse.getBodyPayload(), HealthResponse.class);
      Assertions.assertThat(healthStatus.isReady()).isTrue();
      ServiceHealth userManagementHealth =
          healthStatus.getDependencies().stream()
              .filter(dependency -> "carbonio-user-management".equals(dependency.getName()))
              .findFirst()
              .orElseThrow();
      Assertions.assertThat(userManagementHealth.isLive()).isFalse();
    }
  }

  @Test
  void givenStoragesUnreachableTheHealthReadyShouldReturn204StatusCodeSelfOnly() throws Exception {
    // Given: self-only house pattern — storages (a downstream dependency) being unreachable must
    // NOT affect carbonio-files' own readiness, which is gated solely on its own database.
    try (FilesTestApp app =
        QuarkusFilesTestAppBuilder.aFilesTestApp()
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

      // Then: readiness stays healthy regardless of storages being down.
      Assertions.assertThat(httpResponse.getStatus()).isEqualTo(204);
      Assertions.assertThat(httpResponse.getBodyPayload()).isEmpty();

      // And: storages is reported informational-only (not live) in the /health payload, without
      // affecting the overall ready flag.
      HttpResponse healthResponse = app.send(HttpRequest.of("GET", "/health/", null, null));
      HealthResponse healthStatus =
          new ObjectMapper().readValue(healthResponse.getBodyPayload(), HealthResponse.class);
      Assertions.assertThat(healthStatus.isReady()).isTrue();
      ServiceHealth storagesHealth =
          healthStatus.getDependencies().stream()
              .filter(dependency -> "carbonio-storages".equals(dependency.getName()))
              .findFirst()
              .orElseThrow();
      Assertions.assertThat(storagesHealth.isLive()).isFalse();
    }
  }

  @Test
  void
  givenMessageBrokerUnreachableAndOtherMandatoryDependenciesReachableTheHealthReadyShouldReturn204StatusCode() {
    // Given
    try (FilesTestApp app =
        QuarkusFilesTestAppBuilder.aFilesTestApp()
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
