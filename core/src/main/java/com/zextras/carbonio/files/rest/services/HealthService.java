// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.services;

import com.google.inject.Inject;
import com.zextras.carbonio.files.clients.DocsConnectorHttpClient;
import com.zextras.carbonio.files.config.FilesConfig;
import com.zextras.carbonio.files.dal.DatabaseManager;
import com.zextras.carbonio.files.message_broker.interfaces.MessageBrokerManager;
import com.zextras.carbonio.files.rest.types.health.DependencyType;
import com.zextras.carbonio.files.rest.types.health.ServiceHealth;
import com.zextras.carbonio.preview.sdk.PreviewClient;
import com.zextras.filestore.api.Filestore;
import com.zextras.filestore.api.Filestore.Liveness;

// TODO: health-check design -- files should report only its OWN liveness/readiness. If
// dependency health is genuinely needed, it should be read from Consul (which already
// health-checks every service), NOT by probing each dependency's HTTP endpoint directly. The
// current per-dependency probes (user-management, docs-connector, preview, storages,
// message-broker) are the wrong approach: they only work when a dependency happens to expose a
// health endpoint AND the mesh intention allows it (e.g. user-management is internal-only and
// correctly does not), and gating readiness on remote deps causes cascading flaps.
public class HealthService {

  private final DatabaseManager databaseManagerFlyway;
  private final FilesConfig filesConfig;
  private final DocsConnectorHttpClient docsConnectorHttpClient;
  private final MessageBrokerManager messageBrokerManager;
  private final PreviewClient previewClient;
  private final Filestore storagesClient;

  @Inject
  public HealthService(
      DatabaseManager databaseManagerFlyway,
      FilesConfig filesConfig,
      DocsConnectorHttpClient docsConnectorHttpClient,
      MessageBrokerManager messageBrokerManager,
      PreviewClient previewClient,
      Filestore storagesClient) {
    this.databaseManagerFlyway = databaseManagerFlyway;
    this.filesConfig = filesConfig;
    this.docsConnectorHttpClient = docsConnectorHttpClient;
    this.messageBrokerManager = messageBrokerManager;
    this.previewClient = previewClient;
    this.storagesClient = storagesClient;
  }

  /**
   * @return true if the database is reachable, false otherwise.
   */
  public boolean isDatabaseLive() {
    return databaseManagerFlyway.isDatabaseLive();
  }

  /**
   * carbonio-user-management is internal-only: its Consul mesh intention correctly exposes only
   * {@code /internal} and denies everything else, so carbonio-files has no allowed path to probe
   * its liveness (e.g. {@code /q/health/live} is blocked by the mesh -&gt; 403). Rather than
   * misreport UM as down whenever the mesh intention is (correctly) doing its job, this dependency
   * is unconditionally considered live here; a real UM outage still surfaces per-request as 401 on
   * authenticated calls, which is where it belongs -- not in files' readiness.
   *
   * @return always {@code true}.
   */
  public boolean isUserManagementLive() {
    return true;
  }

  /**
   * @return true if the carbonio-storages service is reachable, false otherwise.
   */
  public boolean isStoragesLive() {
    return storagesClient.checkLiveness().equals(Liveness.OK);
  }

  /**
   * @return true if the carbonio-preview service is reachable, false otherwise.
   */
  public boolean isPreviewLive() {
    return previewClient.healthReady();
  }

  /**
   * @return true if the connection to carbonio-message-broker service is open, false otherwise.
   */
  public boolean isMessageBrokerLive() {
    return messageBrokerManager.healthCheck();
  }

  /**
   * @return a {@link ServiceHealth} representing the status of the database service. This
   *     dependency is {@link DependencyType#REQUIRED} for carbonio-files.
   */
  public ServiceHealth getDatabaseHealth() {
    boolean databaseIsLive = isDatabaseLive();

    return new ServiceHealth()
        .setName("database")
        .setType(DependencyType.REQUIRED)
        .setLive(databaseIsLive)
        .setReady(databaseIsLive);
  }

  /**
   * @return a {@link ServiceHealth} representing the status of the carbonio-user-management
   *     service. This dependency is {@link DependencyType#REQUIRED} for carbonio-files.
   */
  public ServiceHealth getUserManagementHealth() {
    boolean userManagementIsLive = isUserManagementLive();
    return new ServiceHealth()
        .setName("carbonio-user-management")
        .setType(DependencyType.REQUIRED)
        .setLive(userManagementIsLive)
        .setReady(userManagementIsLive);
  }

  /**
   * @return a {@link ServiceHealth} representing the status of the carbonio-storages service. This
   *     dependency is {@link DependencyType#REQUIRED} for carbonio-files.
   */
  public ServiceHealth getStoragesHealth() {
    boolean fileStoreIsLive = isStoragesLive();
    return new ServiceHealth()
        .setName("carbonio-storages")
        .setType(DependencyType.REQUIRED)
        .setLive(fileStoreIsLive)
        .setReady(fileStoreIsLive);
  }

  /**
   * @return a {@link ServiceHealth} representing the status of the carbonio-preview service. This
   *     dependency is {@link DependencyType#OPTIONAL} for carbonio-files.
   */
  public ServiceHealth getPreviewHealth() {
    boolean previewIsUp = isPreviewLive();
    return new ServiceHealth()
        .setName("carbonio-preview")
        .setType(DependencyType.OPTIONAL)
        .setLive(previewIsUp)
        .setReady(previewIsUp);
  }

  /**
   * @return a {@link ServiceHealth} representing the status of the carbonio-docs-connector service.
   *     This dependency is {@link DependencyType#OPTIONAL} for carbonio-files.
   */
  public ServiceHealth getDocsConnectorHealth() {
    boolean docsConnectorIsUp = docsConnectorHttpClient.healthLiveCheck();
    return new ServiceHealth()
        .setName("carbonio-docs-connector")
        .setType(DependencyType.OPTIONAL)
        .setLive(docsConnectorIsUp)
        .setReady(docsConnectorIsUp);
  }
  /**
   * @return a {@link ServiceHealth} representing the status of the carbonio-message-broker service. This
   *     dependency is {@link DependencyType#OPTIONAL} for carbonio-files.
   */
  public ServiceHealth getMessageBrokerHealth() {
    boolean messageBrokerIsUp = isMessageBrokerLive();
    return new ServiceHealth()
        .setName("carbonio-message-broker")
        .setType(DependencyType.OPTIONAL)
        .setLive(messageBrokerIsUp)
        .setReady(messageBrokerIsUp);
  }
}
