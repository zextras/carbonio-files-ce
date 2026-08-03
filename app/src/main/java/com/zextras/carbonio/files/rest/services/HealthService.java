// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.services;

import com.zextras.carbonio.files.clients.DocsConnectorHttpClient;
import com.zextras.carbonio.files.clients.UserManagementHttpClient;
import com.zextras.carbonio.files.message_broker.interfaces.MessageBrokerManager;
import com.zextras.carbonio.files.rest.types.health.DependencyType;
import com.zextras.carbonio.files.rest.types.health.ServiceHealth;
import com.zextras.carbonio.preview.sdk.PreviewClient;
import com.zextras.filestore.api.Filestore;
import com.zextras.filestore.api.Filestore.Liveness;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class HealthService {

  private static final Logger logger = LoggerFactory.getLogger(HealthService.class);

  private AgroalDataSource dataSource;
  private DocsConnectorHttpClient docsConnectorHttpClient;
  private MessageBrokerManager messageBrokerManager;
  private PreviewClient previewClient;
  private UserManagementHttpClient userManagementHttpClient;
  private Filestore storagesClient;

  protected HealthService() {}

  @Inject
  public HealthService(
      AgroalDataSource dataSource,
      DocsConnectorHttpClient docsConnectorHttpClient,
      MessageBrokerManager messageBrokerManager,
      PreviewClient previewClient,
      UserManagementHttpClient userManagementHttpClient,
      Filestore storagesClient) {
    this.dataSource = dataSource;
    this.docsConnectorHttpClient = docsConnectorHttpClient;
    this.messageBrokerManager = messageBrokerManager;
    this.previewClient = previewClient;
    this.userManagementHttpClient = userManagementHttpClient;
    this.storagesClient = storagesClient;
  }

  public boolean isDatabaseLive() {
    try (Connection connection = dataSource.getConnection()) {
      return connection.isValid(1);
    } catch (Exception e) {
      logger.debug("Database liveness probe failed", e);
      return false;
    }
  }

  public boolean isUserManagementLive() {
    return userManagementHttpClient.healthLiveCheck();
  }

  public boolean isStoragesLive() {
    return storagesClient.checkLiveness().equals(Liveness.OK);
  }

  public boolean isPreviewLive() {
    return previewClient.healthReady();
  }

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
    boolean docsConnectorIsUp = docsConnectorHttpClient.isLive();
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
