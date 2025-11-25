// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.services;

import com.google.inject.Inject;
import com.zextras.carbonio.files.clients.DocsConnectorHttpClient;
import com.zextras.carbonio.files.config.FilesConfig;
import com.zextras.carbonio.files.dal.impl.DatabaseManagerFlyway;
import com.zextras.carbonio.files.dal.dao.ebean.DbInfo;
import com.zextras.carbonio.files.message_broker.interfaces.MessageBrokerManager;
import com.zextras.carbonio.files.rest.types.health.DependencyType;
import com.zextras.carbonio.files.rest.types.health.ServiceHealth;
import com.zextras.carbonio.preview.PreviewClient;
import com.zextras.carbonio.usermanagement.UserManagementClient;
import com.zextras.filestore.api.Filestore;
import com.zextras.filestore.api.Filestore.Liveness;

public class HealthService {

  private final DatabaseManagerFlyway databaseManagerFlyway;
  private final FilesConfig filesConfig;
  private final DocsConnectorHttpClient docsConnectorHttpClient;
  private final MessageBrokerManager messageBrokerManager;
  private final PreviewClient previewClient;
  private final UserManagementClient userManagementClient;
  private final Filestore storagesClient;

  @Inject
  public HealthService(
      DatabaseManagerFlyway databaseManagerFlyway,
      FilesConfig filesConfig,
      DocsConnectorHttpClient docsConnectorHttpClient,
      MessageBrokerManager messageBrokerManager,
      PreviewClient previewClient, UserManagementClient userManagementClient, Filestore storagesClient) {
    this.databaseManagerFlyway = databaseManagerFlyway;
    this.filesConfig = filesConfig;
    this.docsConnectorHttpClient = docsConnectorHttpClient;
    this.messageBrokerManager = messageBrokerManager;
    this.previewClient = previewClient;
    this.userManagementClient = userManagementClient;
    this.storagesClient = storagesClient;
  }

  /**
   * @return true if the database is reachable, false otherwise.
   */
  public boolean isDatabaseLive() {
    return databaseManagerFlyway.getEbeanDatabase().find(DbInfo.class).findOneOrEmpty().isPresent();
  }

  /**
   * @return true if the carbonio-user-management service is reachable, false otherwise.
   */
  public boolean isUserManagementLive() {
    return userManagementClient.healthCheck();
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
