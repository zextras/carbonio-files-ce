// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.config;

import com.zextras.carbonio.files.Constants;
import com.zextras.carbonio.preview.sdk.PreviewClient;
import com.zextras.carbonio.quarkus.extensions.bootstrap.NetworkingConfigService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * CDI producer for the {@link PreviewClient} REST SDK bean. Mirrors the legacy Guice {@code
 * FilesModule.providePreviewClient}: host/port come from {@link NetworkingConfigService} ({@code
 * networking-config.carbonio.preview.*}, defaulting to the mesh IP/port from {@code
 * package/carbonio-files.hcl}: {@code 127.78.0.2:20003}).
 *
 * <p>{@link PreviewClient} is a {@code public final} class (not an interface), so it cannot be
 * given a CDI client proxy under a normal scope (e.g. {@code @ApplicationScoped}) — {@link
 * Singleton} is a pseudo-scope that hands out the same producer-built instance directly, without
 * requiring a proxy, which is exactly what an un-proxyable final type needs.
 */
@ApplicationScoped
public class PreviewClientProducer {

  private final NetworkingConfigService networkingConfig;

  @Inject
  public PreviewClientProducer(NetworkingConfigService networkingConfig) {
    this.networkingConfig = networkingConfig;
  }

  @Produces
  @Singleton
  public PreviewClient producePreviewClient() {
    String host =
        networkingConfig
            .get(Constants.Config.Preview.HOST_PROPERTY)
            .orElse(Constants.Config.Preview.DEFAULT_HOST);
    String port =
        networkingConfig
            .get(Constants.Config.Preview.PORT_PROPERTY)
            .orElse(String.valueOf(Constants.Config.Preview.DEFAULT_PORT));

    String previewUrl =
        String.format("%s://%s:%s", Constants.Config.Preview.DEFAULT_PROTOCOL, host, port);

    return PreviewClient.atURL(previewUrl);
  }
}
