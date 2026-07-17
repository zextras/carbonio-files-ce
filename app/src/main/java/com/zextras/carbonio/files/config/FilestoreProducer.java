// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.config;

import com.zextras.carbonio.files.Constants;
import com.zextras.carbonio.quarkus.extensions.bootstrap.NetworkingConfigService;
import com.zextras.filestore.api.Filestore;
import com.zextras.storages.api.StoragesClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

/**
 * CDI producer for the {@link Filestore} blob-store client. Mirrors the legacy Guice {@code
 * FilesModule.provideFileStore}: it builds a {@link StoragesClient} pointed at the carbonio-storages
 * mesh upstream ({@code http://<host>:<port>}), reading host/port from the {@link
 * NetworkingConfigService} ({@code networking-config.carbonio.storages.*}, defaults declared in
 * {@code application.properties}: {@code 127.78.0.2:20002}, the mesh IP + storages upstream port
 * from {@code package/carbonio-files.hcl}).
 *
 * <p>The produced bean is injected into {@code NodeDataFetcher} for blob bulk-delete. In the
 * acceptance suite (P3f) this URL is pointed at a stub.
 */
@ApplicationScoped
public class FilestoreProducer {

  private final NetworkingConfigService networkingConfig;

  @Inject
  public FilestoreProducer(NetworkingConfigService networkingConfig) {
    this.networkingConfig = networkingConfig;
  }

  @Produces
  @ApplicationScoped
  public Filestore produceFilestore() {
    String host =
        networkingConfig
            .get(Constants.Config.Storages.HOST_PROPERTY)
            .orElse(Constants.Config.Storages.DEFAULT_HOST);
    String port =
        networkingConfig
            .get(Constants.Config.Storages.PORT_PROPERTY)
            .orElse(String.valueOf(Constants.Config.Storages.DEFAULT_PORT));

    String storagesUrl =
        String.format(
            "%s://%s:%s", Constants.Config.Storages.DEFAULT_PROTOCOL, host, port);

    return StoragesClient.atUrl(storagesUrl);
  }
}
