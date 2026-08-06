// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.config;

import com.zextras.carbonio.quarkus.extensions.bootstrap.CarbonioServiceConfig;
import com.zextras.carbonio.quarkus.extensions.bootstrap.ConfigKey;

/**
 * Declares the Carbonio Files config keys for build-time documentation generation (configs.md).
 *
 * <p>@ConfigKey constants hold the DOT-separated MicroProfile property suffix (not the Consul KV
 * slash path); the bootstrap extension normalizes slash KV paths to these dotted properties.
 *
 * <p>This is the P1 skeleton subset (service-discover only), grown in P3c with the {@link
 * FilesConfig} tunables consumed by the P3c GraphQL DataFetchers. It grows phase-by-phase as
 * upstream clients (P2 database, P3/P5 user-management, storages, preview, message-broker) are
 * ported.
 */
public final class FilesServiceConfig implements CarbonioServiceConfig {

  private FilesServiceConfig() {}

  public static final class NetworkingConfig {
    private NetworkingConfig() {}

    @ConfigKey(description = "Host of the carbonio-files REST/GraphQL service")
    public static final String SERVICE_HOST = "carbonio.service.host";

    @ConfigKey(description = "Port of the carbonio-files REST/GraphQL service")
    public static final String SERVICE_PORT = "carbonio.service.port";

    @ConfigKey(description = "Host of the Consul service-discover agent")
    public static final String SERVICE_DISCOVER_HOST = "carbonio.service-discover.host";

    @ConfigKey(description = "Port of the Consul service-discover agent")
    public static final String SERVICE_DISCOVER_PORT = "carbonio.service-discover.port";

    @ConfigKey(description = "Host of the carbonio-storages upstream (blob store)")
    public static final String STORAGES_HOST = "carbonio.storages.host";

    @ConfigKey(description = "Port of the carbonio-storages upstream (blob store)")
    public static final String STORAGES_PORT = "carbonio.storages.port";

    // NOTE: legacy FilesConfig.areNotificationsEnabled() read this from a Properties object
    // populated ONLY from /etc/carbonio/files/config.properties + System properties (never from
    // Consul KV), so it belongs to the file/ENV/-D chain (NetworkingConfig), not to the Consul-KV
    // ApplicationConfig chain, even though it is a feature toggle rather than a host/port.
    @ConfigKey(
        description = "Enables new-share/added-node/removed-node notifications",
        ifNotPresent = "true")
    public static final String ENABLE_NOTIFICATIONS = "carbonio.files.enable-notifications";
  }

  public static final class ApplicationConfig {
    private ApplicationConfig() {}

    // NOTE: @ConfigKey constants hold the DOT-separated MicroProfile property suffix, NOT the
    // Consul KV path. These particular legacy KV keys (carbonio-files/max-number-of-versions,
    // .../max-uploadable-size-in-mb, .../max-downloadable-size-in-mb) have no slashes of their
    // own past the service-name prefix, so the bootstrap extension's strip-prefix -> / -> . ->
    // application-config. transform leaves them dash-shaped (no dots introduced).

    /** Consul KV path: carbonio-files/max-number-of-versions */
    @ConfigKey(
        description =
            "Max number of kept versions for a file (see also max-number-of-keep-versions)",
        ifNotPresent = "30")
    public static final String MAX_NUMBER_OF_VERSIONS = "max-number-of-versions";

    /** Consul KV path: carbonio-files/max-uploadable-size-in-mb */
    @ConfigKey(description = "Max uploadable file size in MB", ifNotPresent = "no limit")
    public static final String MAX_UPLOADABLE_SIZE_IN_MB = "max-uploadable-size-in-mb";

    /** Consul KV path: carbonio-files/max-downloadable-size-in-mb */
    @ConfigKey(description = "Max downloadable file size in MB", ifNotPresent = "no limit")
    public static final String MAX_DOWNLOADABLE_SIZE_IN_MB = "max-downloadable-size-in-mb";
  }
}
