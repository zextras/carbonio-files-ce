// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.services;

import com.google.inject.Inject;
import com.zextras.carbonio.files.config.FilesConfig;
import com.zextras.carbonio.files.dal.EbeanDatabaseManager;
import com.zextras.carbonio.files.dal.dao.ebean.DbInfo;
import com.zextras.carbonio.files.rest.types.health.DependencyType;
import com.zextras.carbonio.files.rest.types.health.ServiceHealth;
import com.zextras.filestore.api.Filestore.Liveness;

public class HealthService {

  private final EbeanDatabaseManager ebeanDatabaseManager;
  private final FilesConfig          filesConfig;

  @Inject
  public HealthService(
    EbeanDatabaseManager ebeanDatabaseManager,
    FilesConfig filesConfig
  ) {
    this.ebeanDatabaseManager = ebeanDatabaseManager;
    this.filesConfig = filesConfig;
  }

  /**
   * @return true if the database is reachable, false otherwise.
   */
  public boolean isDatabaseLive() {
    return ebeanDatabaseManager
      .getEbeanDatabase()
      .find(DbInfo.class)
      .findOneOrEmpty()
      .isPresent();
  }

  /**
   * @return true if the carbonio-user-management service is reachable, false otherwise.
   */
  public boolean isUserManagementLive() {
    return filesConfig.getUserManagementClient().healthCheck();
  }

  /**
   * @return true if the carbonio-storages service is reachable, false otherwise.
   */
  public boolean isStoragesLive() {
    return filesConfig.getFileStoreClient().checkLiveness().equals(Liveness.OK);
  }

  /**
   * @return true if the carbonio-preview service is reachable, false otherwise.
   */
  public boolean isPreviewLive() {
    return filesConfig.getPreviewClient().healthReady();
  }

  /**
   * @return a {@link ServiceHealth} representing the status of the database service. This
   * dependency is {@link DependencyType#REQUIRED} for carbonio-files.
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
   * service. This dependency is {@link DependencyType#REQUIRED} for carbonio-files.
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
   * dependency is {@link DependencyType#REQUIRED} for carbonio-files.
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
   * dependency is {@link DependencyType#OPTIONAL} for carbonio-files.
   */
  public ServiceHealth getPreviewHealth() {
    boolean previewIsUp = isPreviewLive();
    return new ServiceHealth()
      .setName("carbonio-preview")
      .setType(DependencyType.OPTIONAL)
      .setLive(previewIsUp)
      .setReady(previewIsUp);
  }
}
