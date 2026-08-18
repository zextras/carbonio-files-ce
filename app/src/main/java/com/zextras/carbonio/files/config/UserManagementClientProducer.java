// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.config;

import com.zextras.carbonio.files.Constants;
import com.zextras.carbonio.quarkus.extensions.bootstrap.NetworkingConfigService;
import com.zextras.carbonio.user_management.sdk.rest.ApiClient;
import com.zextras.carbonio.user_management.sdk.rest.api.UserResourceApi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * CDI producer for the {@link UserResourceApi} REST SDK bean (carbonio-user-management-rest-sdk).
 * Replaces the P3a {@code @GrpcClient("user-management")} stub: host/port come from {@link
 * NetworkingConfigService} ({@code networking-config.carbonio.user-management.*}, defaulting to the
 * mesh IP/port from {@code package/carbonio-files.hcl}: {@code 127.78.0.2:20001}), same as the gRPC
 * client it replaces.
 *
 * <p>The {@link HttpClient} is explicitly pinned to HTTP/1.1: the JDK client's default (HTTP/2 with
 * an HTTP/1.1 upgrade attempt) trips plaintext servers that only speak HTTP/1.1 (e.g.
 * WireMock/Jetty in the ITs) into a protocol error/hang. carbonio-user-management is plain
 * HTTP/1.1, and {@code MailboxHttpClient}/{@code DocsConnectorHttpClient} pin the same version for
 * the same reason.
 */
@ApplicationScoped
public class UserManagementClientProducer {

  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  private final NetworkingConfigService networkingConfig;

  @Inject
  public UserManagementClientProducer(NetworkingConfigService networkingConfig) {
    this.networkingConfig = networkingConfig;
  }

  @Produces
  @ApplicationScoped
  public UserResourceApi produceUserResourceApi() {
    String host =
        networkingConfig
            .get(Constants.Config.UserManagement.HOST_PROPERTY)
            .orElse(Constants.Config.UserManagement.DEFAULT_HOST);
    String port =
        networkingConfig
            .get(Constants.Config.UserManagement.PORT_PROPERTY)
            .orElse(String.valueOf(Constants.Config.UserManagement.DEFAULT_PORT));

    String userManagementUrl =
        String.format("%s://%s:%s", Constants.Config.UserManagement.DEFAULT_PROTOCOL, host, port);

    HttpClient.Builder httpClientBuilder =
        HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1);
    ApiClient apiClient =
        new ApiClient(httpClientBuilder, ApiClient.createDefaultObjectMapper(), userManagementUrl);
    // Must be set before constructing UserResourceApi: its constructor snapshots the timeouts into
    // final fields, so setting them afterwards is a silent no-op.
    apiClient.setConnectTimeout(TIMEOUT);
    apiClient.setReadTimeout(TIMEOUT);
    return new UserResourceApi(apiClient);
  }
}
