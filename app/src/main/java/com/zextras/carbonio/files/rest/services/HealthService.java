// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.services;

import com.zextras.carbonio.files.clients.DocsConnectorHttpClient;
import com.zextras.carbonio.files.message_broker.interfaces.MessageBrokerManager;
import com.zextras.carbonio.files.rest.types.health.DependencyType;
import com.zextras.carbonio.files.rest.types.health.ServiceHealth;
import com.zextras.carbonio.preview.sdk.PreviewClient;
import com.zextras.carbonio.user_management.sdk.grpc.GetUserMyselfRequest;
import com.zextras.carbonio.user_management.sdk.grpc.UserManagementServiceGrpc.UserManagementServiceBlockingStub;
import com.zextras.filestore.api.Filestore;
import com.zextras.filestore.api.Filestore.Liveness;
import io.grpc.StatusRuntimeException;
import io.quarkus.grpc.GrpcClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reports carbonio-files' own liveness/readiness plus informational status of its dependencies.
 *
 * <p><b>House pattern (see carbonio-knowledge "Carbonio health-check house pattern"): mesh
 * liveness/readiness is gated SELF-ONLY.</b> {@link #isDatabaseLive()} (this service's own
 * Postgres) is the only signal that feeds {@code /health/live} and {@code /health/ready} (see
 * {@code HealthResource}); a downstream dependency being down (user-management, storages,
 * preview, message-broker) must never flip carbonio-files' own health to failing — that would
 * cascade one service's outage into every mesh health check that depends on it. This is a
 * deliberate behaviour change from the legacy Netty {@code HealthService}, which required
 * database + user-management + storages to all be live for {@code /health/ready}/{@code /health}
 * to report ready.
 *
 * <p>The per-dependency {@code isXxxLive()}/{@code getXxxHealth()} methods below are kept for the
 * {@code /health} JSON payload's informational {@code dependencies} list (shape parity with the
 * legacy response) — they are never used to gate the HTTP status of any endpoint.
 */
@ApplicationScoped
public class HealthService {

  private static final Logger logger = LoggerFactory.getLogger(HealthService.class);

  /** Deadline for the informational (non-gating) user-management reachability probe. */
  private static final long USER_MANAGEMENT_PROBE_TIMEOUT_MS = 500;

  private final DataSource dataSource;
  private final Filestore storagesClient;
  private final PreviewClient previewClient;
  private final DocsConnectorHttpClient docsConnectorClient;
  private final MessageBrokerManager messageBrokerManager;

  @GrpcClient("user-management")
  UserManagementServiceBlockingStub userManagementStub;

  @Inject
  public HealthService(
      DataSource dataSource,
      Filestore storagesClient,
      PreviewClient previewClient,
      DocsConnectorHttpClient docsConnectorClient,
      MessageBrokerManager messageBrokerManager) {
    this.dataSource = dataSource;
    this.storagesClient = storagesClient;
    this.previewClient = previewClient;
    this.docsConnectorClient = docsConnectorClient;
    this.messageBrokerManager = messageBrokerManager;
  }

  /**
   * @return true if carbonio-files' own database is reachable, false otherwise. This is the SOLE
   *     signal behind {@code /health/live} and {@code /health/ready}.
   */
  public boolean isDatabaseLive() {
    try (Connection connection = dataSource.getConnection()) {
      return connection.isValid(2);
    } catch (SQLException e) {
      logger.warn("Database liveness check failed", e);
      return false;
    }
  }

  /**
   * Informational only (see class javadoc): true if carbonio-user-management answers a probe
   * request, false if the mesh upstream is unreachable. A well-formed error response (e.g.
   * UNAUTHENTICATED for the dummy token below) still counts as "live" — only transport-level
   * unavailability does not.
   */
  public boolean isUserManagementLive() {
    try {
      userManagementStub
          .withDeadlineAfter(USER_MANAGEMENT_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
          .getUserMyself(GetUserMyselfRequest.newBuilder().setToken("health-check-probe").build());
      return true;
    } catch (StatusRuntimeException e) {
      return e.getStatus().getCode() != io.grpc.Status.Code.UNAVAILABLE;
    } catch (Exception e) {
      return false;
    }
  }

  /** Informational only (see class javadoc): true if carbonio-storages is reachable. */
  public boolean isStoragesLive() {
    try {
      return storagesClient.checkLiveness().equals(Liveness.OK);
    } catch (Exception e) {
      return false;
    }
  }

  /** Informational only (see class javadoc): true if carbonio-preview is reachable. */
  public boolean isPreviewLive() {
    return previewClient.healthReady();
  }

  /** Informational only (see class javadoc): true if carbonio-docs-connector is reachable. */
  public boolean isDocsConnectorLive() {
    return docsConnectorClient.isLive();
  }

  /** Informational only (see class javadoc): true if carbonio-message-broker is reachable. */
  public boolean isMessageBrokerLive() {
    return messageBrokerManager.healthCheck();
  }

  public ServiceHealth getDatabaseHealth() {
    boolean databaseIsLive = isDatabaseLive();
    return new ServiceHealth()
        .setName("database")
        .setType(DependencyType.REQUIRED)
        .setLive(databaseIsLive)
        .setReady(databaseIsLive);
  }

  public ServiceHealth getUserManagementHealth() {
    boolean userManagementIsLive = isUserManagementLive();
    return new ServiceHealth()
        .setName("carbonio-user-management")
        .setType(DependencyType.REQUIRED)
        .setLive(userManagementIsLive)
        .setReady(userManagementIsLive);
  }

  public ServiceHealth getStoragesHealth() {
    boolean fileStoreIsLive = isStoragesLive();
    return new ServiceHealth()
        .setName("carbonio-storages")
        .setType(DependencyType.REQUIRED)
        .setLive(fileStoreIsLive)
        .setReady(fileStoreIsLive);
  }

  public ServiceHealth getPreviewHealth() {
    boolean previewIsUp = isPreviewLive();
    return new ServiceHealth()
        .setName("carbonio-preview")
        .setType(DependencyType.OPTIONAL)
        .setLive(previewIsUp)
        .setReady(previewIsUp);
  }

  public ServiceHealth getDocsConnectorHealth() {
    boolean docsConnectorIsUp = isDocsConnectorLive();
    return new ServiceHealth()
        .setName("carbonio-docs-connector")
        .setType(DependencyType.OPTIONAL)
        .setLive(docsConnectorIsUp)
        .setReady(docsConnectorIsUp);
  }

  public ServiceHealth getMessageBrokerHealth() {
    boolean messageBrokerIsUp = isMessageBrokerLive();
    return new ServiceHealth()
        .setName("carbonio-message-broker")
        .setType(DependencyType.OPTIONAL)
        .setLive(messageBrokerIsUp)
        .setReady(messageBrokerIsUp);
  }
}
