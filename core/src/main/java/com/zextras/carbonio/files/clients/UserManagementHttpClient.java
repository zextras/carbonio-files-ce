// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.clients;

import com.zextras.carbonio.files.config.FilesConfig;
import javax.inject.Inject;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;

/**
 * Http client to check the liveness of carbonio-user-management.
 *
 * <p>Before the REST SDK migration, {@link com.zextras.carbonio.files.rest.services.HealthService}
 * checked liveness via the gRPC {@code ManagedChannel}'s {@code getState()}/{@code
 * ConnectivityState}. There is no REST/HTTP equivalent of that channel-state introspection, so this
 * replaces it with a lightweight HTTP liveness probe, mirroring {@link DocsConnectorHttpClient}
 * (carbonio-user-management is, like carbonio-docs-connector, a Quarkus service exposing its
 * liveness probe via SmallRye Health at {@code /q/health/live}, HTTP 200 when UP).
 */
public class UserManagementHttpClient {

  private static final String HEALTH_LIVE_ENDPOINT = "/q/health/live";
  private static final int TIMEOUT_IN_MS = 2 * 1000;

  private final String userManagementUrl;
  private final CloseableHttpClient httpClient;

  @Inject
  public UserManagementHttpClient(CloseableHttpClient httpClient, FilesConfig filesConfig) {
    this.httpClient = httpClient;
    this.userManagementUrl =
        "http://" + filesConfig.getUserManagementHost() + ":" + filesConfig.getUserManagementPort();
  }

  /**
   * Allows to check the liveness of carbonio-user-management service.
   *
   * @return a <code>true</code> if the carbonio-user-management is live, false otherwise.
   */
  public boolean healthLiveCheck() {
    final HttpGet request = new HttpGet(userManagementUrl + HEALTH_LIVE_ENDPOINT);

    final RequestConfig requestConfig =
        RequestConfig.custom()
            .setConnectTimeout(TIMEOUT_IN_MS)
            .setSocketTimeout(TIMEOUT_IN_MS)
            .build();
    request.setConfig(requestConfig);

    try (final CloseableHttpResponse response = httpClient.execute(request)) {
      return response.getStatusLine().getStatusCode() == 200;
    } catch (Exception exception) {
      return false;
    }
  }
}
