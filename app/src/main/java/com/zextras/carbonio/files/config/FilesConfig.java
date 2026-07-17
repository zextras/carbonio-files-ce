// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.config;

import com.zextras.carbonio.files.Constants.ServiceDiscover;
import com.zextras.carbonio.files.clients.ServiceDiscoverHttpClient;
import com.zextras.carbonio.quarkus.extensions.bootstrap.ApplicationConfigService;
import com.zextras.carbonio.quarkus.extensions.bootstrap.NetworkingConfigService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;

/**
 * Runtime accessor for the carbonio-files application tunables consumed by the GraphQL
 * DataFetchers (P3c).
 *
 * <p>This is a P3c-scoped subset: it ports only the {@code FilesConfig} accessors the P3c batch
 * of DataFetchers ({@code ConfigDataFetcher}, {@code NotificationDataFetcher}, {@code
 * ShareDataFetcher}) actually calls. The legacy class also exposed service host/port lookups
 * (files/user-management/storages/preview/mailbox/docs-connector/message-broker) and DB/Hikari/
 * page-token-secret-key accessors; those are ported in the phases that introduce their first
 * caller (P2 database extension already covers DB/Hikari, later phases cover the rest) rather
 * than speculatively here.
 *
 * <p>Unlike the legacy Guice {@code FilesConfig}, this bean does NOT talk to the raw {@code
 * ServiceDiscoverHttpClient} directly: every value it exposes is read from the boot-time Consul
 * KV / MicroProfile Config snapshot maintained by the carbonio-quarkus-extensions-bootstrap
 * extension, via {@link ApplicationConfigService} (Consul KV, {@code application-config.*}) or
 * {@link NetworkingConfigService} (config-file/ENV/-D, {@code networking-config.*}).
 */
@ApplicationScoped
public class FilesConfig {

  private final ApplicationConfigService applicationConfig;
  private final NetworkingConfigService networkingConfig;
  private final ServiceDiscoverHttpClient serviceDiscoverHttpClient;

  @Inject
  public FilesConfig(
      ApplicationConfigService applicationConfig,
      NetworkingConfigService networkingConfig,
      ServiceDiscoverHttpClient serviceDiscoverHttpClient) {
    this.applicationConfig = applicationConfig;
    this.networkingConfig = networkingConfig;
    this.serviceDiscoverHttpClient = serviceDiscoverHttpClient;
  }

  /**
   * Max number of kept versions for a file. Operators may override it via Consul KV ({@code
   * carbonio-files/max-number-of-versions}); when absent (or malformed) the default declared in
   * {@code application.properties} under the {@code application-config.} prefix applies (legacy
   * default: 30).
   */
  public int getMaxNumberOfVersions() {
    try {
      return applicationConfig
          .get(FilesServiceConfig.ApplicationConfig.MAX_NUMBER_OF_VERSIONS)
          .map(Integer::parseInt)
          .orElse(ServiceDiscover.Config.DEFAULT_MAX_VERSIONS);
    } catch (NumberFormatException e) {
      return ServiceDiscover.Config.DEFAULT_MAX_VERSIONS;
    }
  }

  /**
   * Raw (unparsed) {@code max-number-of-versions} value, read LIVE from Consul on every call (via
   * {@link ServiceDiscoverHttpClient}) rather than from the boot-time {@link ApplicationConfigService}
   * snapshot, falling back to the string form of the default when the key is absent or ServiceDiscover
   * is unreachable. Unlike {@link #getMaxNumberOfVersions()} this does NOT swallow a malformed value:
   * legacy's {@code ConfigDataFetcher} parsed this value inline with no try/catch, so a non-numeric
   * override surfaced as an uncaught GraphQL execution error on the {@code getConfigs} query
   * specifically, while every other caller (version-cap enforcement) kept the safe, snapshot-based
   * fallback above.
   */
  public String getMaxNumberOfVersionsRaw() {
    return serviceDiscoverHttpClient
        .getConfig(FilesServiceConfig.ApplicationConfig.MAX_NUMBER_OF_VERSIONS)
        .orElse(String.valueOf(ServiceDiscover.Config.DEFAULT_MAX_VERSIONS));
  }

  /**
   * Max uploadable file size in MB, or {@link Optional#empty()} if not configured or malformed
   * (legacy behaviour: absence of the Consul KV value means "no limit", not a fallback number).
   */
  public Optional<Integer> getMaxUploadableFileSizeInMb() {
    return applicationConfig
        .get(FilesServiceConfig.ApplicationConfig.MAX_UPLOADABLE_SIZE_IN_MB)
        .flatMap(FilesConfig::tryParseInt);
  }

  /**
   * Max downloadable file size in MB, or {@link Optional#empty()} if not configured or malformed
   * (legacy behaviour: absence of the Consul KV value means "no limit", not a fallback number).
   */
  public Optional<Integer> getMaxDownloadableFileSizeInMb() {
    return applicationConfig
        .get(FilesServiceConfig.ApplicationConfig.MAX_DOWNLOADABLE_SIZE_IN_MB)
        .flatMap(FilesConfig::tryParseInt);
  }

  /**
   * Returns {@code true} as default since notifications are a required feature, but opens the way
   * to disable them if needed (e.g. in tests). Unlike the two accessors above this is NOT a
   * Consul KV value: legacy read it from a config-file/System-properties {@code Properties}
   * object, so it is read here via {@link NetworkingConfigService} (file/ENV/-D chain).
   */
  public boolean areNotificationsEnabled() {
    return networkingConfig
        .get(FilesServiceConfig.NetworkingConfig.ENABLE_NOTIFICATIONS)
        .map(Boolean::parseBoolean)
        .orElse(true);
  }

  private static Optional<Integer> tryParseInt(String value) {
    try {
      return Optional.of(Integer.parseInt(value));
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }
}
