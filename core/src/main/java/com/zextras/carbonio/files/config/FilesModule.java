// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.config;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.assistedinject.FactoryModuleBuilder;
import com.zextras.carbonio.files.cache.CacheHandlerFactory;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.*;
import com.zextras.carbonio.files.dal.repositories.interfaces.*;
import com.zextras.carbonio.files.graphql.validators.GenericControllerEvaluatorFactory;
import com.zextras.carbonio.files.message_broker.MessageBrokerManagerImpl;
import com.zextras.carbonio.files.message_broker.interfaces.MessageBrokerManager;
import com.zextras.carbonio.message_broker.MessageBrokerClient;
import com.zextras.carbonio.message_broker.config.enums.Service;
import com.zextras.carbonio.preview.PreviewClient;
import com.zextras.carbonio.usermanagement.UserManagementClient;
import com.zextras.filestore.api.Filestore;


import java.time.Clock;

import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;

public class FilesModule extends AbstractModule {

  private static final Logger logger = LoggerFactory.getLogger(FilesModule.class);

  @Override
  public void configure() {
    bind(Clock.class).toInstance(Clock.systemUTC());

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
  public Filestore provideFileStore(FilesConfig filesConfig) {
    return filesConfig.getStoragesClient();
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
  public MessageBrokerClient provideMessageBrokerClient(FilesConfig filesConfig) {
    return MessageBrokerClient.fromConfig(
            filesConfig.getMessageBrokerUrl(),
            filesConfig.getMessageBrokerPort(),
            filesConfig.getMessageBrokerUsername(),
            filesConfig.getMessageBrokerPassword())
        .withCurrentService(Service.FILES);
  }

  @Provides
  @Singleton
  public UserManagementClient provideUserManagementClient(FilesConfig filesConfig) {
    return filesConfig.getUserManagementClient();
  }

  @Provides
  @Singleton
  public PreviewClient providePreviewClient(FilesConfig filesConfig) {
    return filesConfig.getPreviewClient();
  }
}