// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.config;

import com.zextras.carbonio.files.Constants.ServiceDiscover;
import com.zextras.carbonio.files.clients.ServiceDiscoverHttpClient;
import com.zextras.carbonio.quarkus.extensions.bootstrap.ApplicationConfigService;
import com.zextras.carbonio.quarkus.extensions.bootstrap.NetworkingConfigService;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runtime accessor for the carbonio-files application tunables consumed by the GraphQL DataFetchers
 * (P3c).
 *
 * <p>This is a P3c-scoped subset: it ports only the {@code FilesConfig} accessors the P3c batch of
 * DataFetchers ({@code ConfigDataFetcher}, {@code NotificationDataFetcher}, {@code
 * ShareDataFetcher}) actually calls. The legacy class also exposed service host/port lookups
 * (files/user-management/storages/preview/mailbox/docs-connector/message-broker) and DB/Hikari/
 * page-token-secret-key accessors; those are ported in the phases that introduce their first caller
 * (P2 database extension already covers DB/Hikari, later phases cover the rest) rather than
 * speculatively here.
 *
 * <p>Unlike the legacy Guice {@code FilesConfig}, this bean reads the {@code application-config.*}
 * tunables via {@link ApplicationConfigService} (Consul KV) and the {@code networking-config.*}
 * values via {@link NetworkingConfigService} (config-file/ENV/-D), both maintained by the
 * carbonio-quarkus-extensions-bootstrap extension. As of extension 1.13.0-1 the extension keeps its
 * Consul KV view LIVE (watch), so {@link ApplicationConfigService} returns the CURRENT value on
 * every call and a runtime KV change takes effect without an app restart. ({@code
 * page-token-secret-key} is the exception: it is read live per-call straight from the raw {@code
 * ServiceDiscoverHttpClient} — see {@link #getPageTokenSecretKey()}.)
 */
@ApplicationScoped
public class FilesConfig {

  private static final Logger logger = LoggerFactory.getLogger(FilesConfig.class);

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
   * Boot-time hook (mirrors {@code MessageBrokerManagerImpl#onStart}'s {@code @Observes
   * StartupEvent} pattern): ensures the {@code page-token-secret-key} Consul KV entry exists before
   * the app starts serving traffic. Ports legacy {@code FilesConfig#initializeSecretKey()}.
   */
  void onStart(@Observes StartupEvent event) {
    initializePageTokenSecretKey();
  }

  /**
   * Generates a random HmacSHA256 key and stores it in Consul KV at {@code
   * carbonio-files/page-token-secret-key} via a {@code ?cas=0} write, so the FIRST instance of a
   * cluster to boot wins and every instance converges on the SAME key (subsequent instances' own
   * generated candidate is simply discarded — {@link #getPageTokenSecretKey()} always re-reads the
   * winning value live from Consul, never caching what THIS instance tried to write). On a cold
   * cluster this is exactly what makes page tokens minted by one node verifiable by any other node,
   * and what makes them survive a restart. Wrapped in try/catch: if Consul is unreachable or the
   * ACL token is rejected (403), the app must still boot — {@link #getPageTokenSecretKey()} falls
   * back to the well-known default key in that case, same trade-off the legacy code documented
   * (signing degrades to a shared-but-public key rather than taking the app down).
   */
  private void initializePageTokenSecretKey() {
    try {
      String configKey = ServiceDiscover.Config.PAGE_TOKEN_SECRET_KEY;
      if (serviceDiscoverHttpClient.getConfig(configKey).isEmpty()) {
        String newSecretKey = generateHmacSha256Key();
        boolean created = serviceDiscoverHttpClient.createConfigIfAbsent(configKey, newSecretKey);
        logger.debug(
            created
                ? "Page token secret key created"
                : "Page token secret key already existed (or Consul write failed); another"
                    + " instance's key (or the default) will be used");
      }
    } catch (Exception e) {
      logger.error("Exception while trying to create the page token secret key", e);
    }
  }

  private static String generateHmacSha256Key() {
    try {
      KeyGenerator keyGen = KeyGenerator.getInstance("HmacSHA256");
      SecretKey secretKey = keyGen.generateKey();
      return Base64.getEncoder().encodeToString(secretKey.getEncoded());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("Failed to generate HMAC key", e);
    }
  }

  /**
   * The HMAC-SHA256 key used to sign/verify {@code findNodes} keyset page tokens, read LIVE from
   * Consul on every call (never cached) via {@link ServiceDiscoverHttpClient}. Falls back to a
   * well-known, public default when Consul has no value yet (or is unreachable) — the same
   * intentional trade-off the legacy code made: a page token is not a capability grant on its own
   * (an unsigned/default-keyed one only replays what its own fields already say), so a
   * temporarily-shared-but-public key is preferable to refusing to serve requests.
   */
  public String getPageTokenSecretKey() {
    return serviceDiscoverHttpClient
        .getConfig(ServiceDiscover.Config.PAGE_TOKEN_SECRET_KEY)
        .orElse(ServiceDiscover.Config.DEFAULT_PAGE_TOKEN_SECRET_KEY);
  }

  /**
   * Max number of kept versions for a file, read as the CURRENT (live) value via {@link
   * ApplicationConfigService}. Operators may override it via Consul KV ({@code
   * carbonio-files/max-number-of-versions}) and a runtime change takes effect without an app
   * restart (extension 1.13.0-1 reads Consul KV live); when absent (or malformed) the default
   * declared in {@code application.properties} under the {@code application-config.} prefix applies
   * (legacy default: 30).
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
   * Raw (unparsed, unclamped) {@code max-number-of-versions} value, read as the CURRENT (live)
   * value from the SAME {@link ApplicationConfigService} as {@link #getMaxNumberOfVersions()} — and
   * as every other config read — so the {@code getConfigs} query reports exactly what the
   * version-cap enforcement path enforces, both reflecting a runtime Consul KV change on the very
   * next call without an app restart (extension 1.13.0-1 reads Consul KV live). Falls back to the
   * string form of the default when the key is absent. Unlike {@link #getMaxNumberOfVersions()} it
   * neither parses nor swallows the value: the {@code ConfigDataFetcher} caller parses it inline,
   * so a non-numeric value surfaces as a GraphQL execution error on {@code getConfigs} rather than
   * being silently defaulted.
   */
  public String getMaxNumberOfVersionsRaw() {
    return applicationConfig
        .get(FilesServiceConfig.ApplicationConfig.MAX_NUMBER_OF_VERSIONS)
        .orElse(String.valueOf(ServiceDiscover.Config.DEFAULT_MAX_VERSIONS));
  }

  /**
   * Max uploadable file size in MB, read as the CURRENT (live) value via {@link
   * ApplicationConfigService} (extension 1.13.0-1 reads Consul KV live, so a runtime change applies
   * without an app restart), or {@link Optional#empty()} if not configured or malformed (legacy
   * behaviour: absence of the Consul KV value means "no limit", not a fallback number).
   */
  public Optional<Integer> getMaxUploadableFileSizeInMb() {
    return applicationConfig
        .get(FilesServiceConfig.ApplicationConfig.MAX_UPLOADABLE_SIZE_IN_MB)
        .flatMap(FilesConfig::tryParseInt);
  }

  /**
   * Max downloadable file size in MB, read as the CURRENT (live) value via {@link
   * ApplicationConfigService} (extension 1.13.0-1 reads Consul KV live, so a runtime change applies
   * without an app restart), or {@link Optional#empty()} if not configured or malformed (legacy
   * behaviour: absence of the Consul KV value means "no limit", not a fallback number).
   */
  public Optional<Integer> getMaxDownloadableFileSizeInMb() {
    return applicationConfig
        .get(FilesServiceConfig.ApplicationConfig.MAX_DOWNLOADABLE_SIZE_IN_MB)
        .flatMap(FilesConfig::tryParseInt);
  }

  /**
   * Returns {@code true} as default since notifications are a required feature, but opens the way
   * to disable them if needed (e.g. in tests). Unlike the two accessors above this is NOT a Consul
   * KV value: legacy read it from a config-file/System-properties {@code Properties} object, so it
   * is read here via {@link NetworkingConfigService} (file/ENV/-D chain).
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
