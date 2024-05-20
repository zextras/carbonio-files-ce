// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.config;

import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.assistedinject.FactoryModuleBuilder;
import com.zextras.carbonio.files.cache.CacheHandlerFactory;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.CollaborationLinkRepositoryEbean;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.FileVersionRepositoryEbean;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.LinkRepositoryEbean;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.NodeRepositoryEbean;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.ShareRepositoryEbean;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.TombstoneRepositoryEbean;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.UserRepositoryRest;
import com.zextras.carbonio.files.dal.repositories.interfaces.CollaborationLinkRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.LinkRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.ShareRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.TombstoneRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.UserRepository;
import com.zextras.carbonio.files.graphql.validators.GenericControllerEvaluatorFactory;
import com.zextras.filestore.api.Filestore;
import com.zextras.storages.api.StoragesClient;
import java.time.Clock;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;

public class FilesModule extends AbstractModule {

  private final FilesConfig filesConfig;

  @Inject
  public FilesModule(FilesConfig filesConfig) {
    this.filesConfig = filesConfig;
  }

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

    install(new FactoryModuleBuilder().build(CacheHandlerFactory.class));

    install(new FactoryModuleBuilder().build(GenericControllerEvaluatorFactory.class));
  }

  @Provides
  public Filestore getFileStore() {
    return StoragesClient.atUrl(filesConfig.getFileStoreUrl());
  }

  @Singleton
  @Provides
  public CloseableHttpClient getGenericHttpClientPool() {
    return HttpClientBuilder.create().setMaxConnPerRoute(10).setMaxConnTotal(30).build();
  }
}
