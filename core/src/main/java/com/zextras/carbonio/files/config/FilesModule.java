// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.config;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.assistedinject.FactoryModuleBuilder;
import com.zaxxer.hikari.HikariDataSource;
import com.zextras.carbonio.files.Constants;
import com.zextras.carbonio.files.cache.CacheHandlerFactory;
import com.zextras.carbonio.files.dal.DatabaseManager;
import com.zextras.carbonio.files.dal.impl.DatabaseManagerFlyway;
import com.zextras.carbonio.files.dal.dao.ebean.*;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.AddedNodeNotification;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.NewShareNotification;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.Notification;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.RemovedNodeNotification;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.utils.UserNotificationInterest;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.utils.UserNotificationsInfo;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.utils.snapshot.SnapshotNode;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.utils.snapshot.SnapshotUser;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.*;
import com.zextras.carbonio.files.dal.repositories.interfaces.*;
import com.zextras.carbonio.files.graphql.validators.GenericControllerEvaluatorFactory;
import com.zextras.carbonio.files.message_broker.MessageBrokerManagerImpl;
import com.zextras.carbonio.files.message_broker.interfaces.MessageBrokerManager;
import com.zextras.carbonio.message_broker.MessageBrokerClient;
import com.zextras.carbonio.message_broker.config.enums.Service;
import com.zextras.carbonio.preview.PreviewClient;
import com.zextras.carbonio.user_management.sdk.grpc.UserManagementServiceGrpc;
import com.zextras.carbonio.user_management.sdk.grpc.UserManagementServiceGrpc.UserManagementServiceBlockingStub;
import com.zextras.filestore.api.Filestore;
import com.zextras.storages.api.StoragesClient;
import io.ebean.Database;
import io.ebean.DatabaseFactory;
import io.ebean.config.DatabaseConfig;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Properties;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FilesModule extends AbstractModule {

  private static final Logger logger = LoggerFactory.getLogger(FilesModule.class);

  @Override
  public void configure() {
    bind(Clock.class).toInstance(Clock.systemUTC());

    bind(DatabaseManager.class).to(DatabaseManagerFlyway.class).in(Singleton.class);
    bind(NodeRepository.class).to(NodeRepositoryEbean.class);
    bind(ShareRepository.class).to(ShareRepositoryEbean.class);
    bind(TombstoneRepository.class).to(TombstoneRepositoryEbean.class);
    bind(FileVersionRepository.class).to(FileVersionRepositoryEbean.class);
    bind(LinkRepository.class).to(LinkRepositoryEbean.class);
    bind(CollaborationLinkRepository.class).to(CollaborationLinkRepositoryEbean.class);
    bind(UserRepository.class).to(UserRepositoryRest.class);
    bind(CollationRepository.class).to(CollationRepositoryEbean.class);
    bind(NotificationRepository.class).to(NotificationRepositoryEbean.class);

    bind(MessageBrokerManager.class).to(MessageBrokerManagerImpl.class);

    install(new FactoryModuleBuilder().build(CacheHandlerFactory.class));
    install(new FactoryModuleBuilder().build(GenericControllerEvaluatorFactory.class));
  }

  @Provides
  @Singleton
  public FilesConfig provideFilesConfig() throws Exception {
    final FilesConfig config = new FilesConfig();
    config.loadConfig();
    return config;
  }

  @Provides
  @Singleton
  public CloseableHttpClient provideGenericHttpClientPool() {
    return HttpClientBuilder.create()
        .setMaxConnPerRoute(10)
        .setMaxConnTotal(30)
        .build();
  }

  @Provides
  @Singleton
  public HikariDataSource provideDataSource(FilesConfig config) {
    String jdbcPostgresUrl = String.format("jdbc:postgresql://%s:%s/%s",
        config.getDatabaseHost(),
        config.getDatabasePort(),
        config.getDatabaseName());

    int maximumPoolSize = config.getHikariMaxPoolSize();
    int minimumIdleConnections = config.getHikariMinIdleConnections();
    int idleTimeout = config.getHikariIdleTimeout();
    int leakDetectionThreshold = config.getHikariLeakDetectionThreshold();
    int maxLifetime = config.getHikariMaxLifetime();

    logger.info("Hikari: maximum pool size: {}", maximumPoolSize);
    logger.info("Hikari: minimum idle connections: {}", minimumIdleConnections);
    logger.info("Hikari: idle timeout: {}", idleTimeout);
    logger.info("Hikari: leak detection threshold: {}", leakDetectionThreshold);
    logger.info("Hikari: max lifetime: {}", maxLifetime);

    Properties dataSourceProperties = new Properties();
    dataSourceProperties.setProperty("sslmode", "disable");
    dataSourceProperties.setProperty("ApplicationName", "files");

    HikariDataSource dataSource = new HikariDataSource();
    dataSource.setJdbcUrl(jdbcPostgresUrl);
    dataSource.setPoolName("files-db-pool");
    dataSource.setUsername(config.getDatabaseUsername());
    dataSource.setPassword(config.getDatabasePassword());
    dataSource.setMaximumPoolSize(maximumPoolSize);
    dataSource.setMinimumIdle(minimumIdleConnections);
    dataSource.setIdleTimeout(idleTimeout);
    dataSource.setLeakDetectionThreshold(leakDetectionThreshold);
    dataSource.setMaxLifetime(maxLifetime);
    dataSource.setDataSourceProperties(dataSourceProperties);

    return dataSource;
  }

  @Provides
  @Singleton
  public DatabaseConfig provideEbeanDatabaseConfig(HikariDataSource dataSource) {
    ArrayList<Class<?>> entityList = new ArrayList<>();
    entityList.add(DbInfo.class);
    entityList.add(Node.class);
    entityList.add(NodeCustomAttributesPK.class);
    entityList.add(NodeCustomAttributes.class);
    entityList.add(FileVersionPK.class);
    entityList.add(FileVersion.class);
    entityList.add(SharePK.class);
    entityList.add(Share.class);
    entityList.add(Link.class);
    entityList.add(CollaborationLink.class);
    entityList.add(TombstonePK.class);
    entityList.add(Tombstone.class);
    entityList.add(TrashedNode.class);
    entityList.add(Notification.class);
    entityList.add(NewShareNotification.class);
    entityList.add(AddedNodeNotification.class);
    entityList.add(RemovedNodeNotification.class);
    entityList.add(UserNotificationsInfo.class);
    entityList.add(UserNotificationInterest.class);
    entityList.add(SnapshotUser.class);
    entityList.add(SnapshotNode.class);

    DatabaseConfig databaseConfig = new DatabaseConfig();
    databaseConfig.setName("carbonio-files-postgres");
    databaseConfig.setDataSource(dataSource);
    databaseConfig.setDefaultServer(true);
    databaseConfig.addAll(entityList);
    databaseConfig.setCacheMaxSize(100_000);
    databaseConfig.setCacheMaxTimeToLive(300);
    databaseConfig.setCacheMaxIdleTime(300);

    return databaseConfig;
  }

  @Provides
  @Singleton
  public Database provideEbeanDatabase(DatabaseConfig databaseConfig) {
    try {
      Database ebeanDatabase = DatabaseFactory.createWithContextClassLoader(
          databaseConfig,
          FilesModule.class.getClassLoader());

      logger.info("Database connection created successfully");
      return ebeanDatabase;
    } catch (Exception exception) {
      String error = String.format(
          "%s: e.g. %s, %s or %s",
          "Unable to create the database connection! Something went wrong",
          "database is not reachable",
          "the database does not exist",
          "the database credentials are wrong");

      throw new RuntimeException(error, exception);
    }
  }

  @Provides
  @Singleton
  public MessageBrokerClient provideMessageBrokerClient(FilesConfig filesConfig) {
    return MessageBrokerClient.fromConfig(
            filesConfig.getMessageBrokerHost(),
            filesConfig.getMessageBrokerPort(),
            filesConfig.getMessageBrokerUsername(),
            filesConfig.getMessageBrokerPassword())
        .withCurrentService(Service.FILES);
  }

  @Provides
  @Singleton
  public ManagedChannel provideUserManagementChannel(FilesConfig config) {
    String host = config.getUserManagementHost();
    int port = Integer.parseInt(config.getUserManagementPort());
    return ManagedChannelBuilder.forAddress(host, port)
        .usePlaintext()
        .build();
  }

  @Provides
  @Singleton
  public UserManagementServiceBlockingStub provideUserManagementStub(
      ManagedChannel userManagementChannel) {
    return UserManagementServiceGrpc.newBlockingStub(userManagementChannel);
  }

  @Provides
  @Singleton
  public PreviewClient providePreviewClient(FilesConfig config) {
    final String carbonioPreviewUrl = String.format(
        "%s://%s:%s",
        Constants.Config.Preview.DEFAULT_PROTOCOL,
        config.getPreviewHost(),
        config.getPreviewPort());

    return PreviewClient.atURL(carbonioPreviewUrl);
  }

  @Provides
  @Singleton
  public Filestore provideFileStore(FilesConfig config) {
    final String carbonioStoragesUrl = String.format(
        "%s://%s:%s",
        Constants.Config.Storages.DEFAULT_PROTOCOL,
        config.getStoragesHost(),
        config.getStoragesPort());

    return StoragesClient.atUrl(carbonioStoragesUrl);
  }
}
