// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.clients;

import com.zextras.carbonio.files.config.FilesConfig;
import javax.inject.Inject;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;

/** Http client to make http requests to the docs-connector using the service discover. */
public class DocsConnectorHttpClient {

  private static final String HEALTH_LIVE_ENDPOINT = "/health/live/";
  private static final int TIMEOUT_IN_MS = 2 * 1000;

  private final String docsConnectorUrl;
  private final CloseableHttpClient httpClient;

  @Inject
  public DocsConnectorHttpClient(CloseableHttpClient httpClient, FilesConfig filesConfig) {
    this.httpClient = httpClient;
    this.docsConnectorUrl = filesConfig.getDocsConnectorUrl();
  }

  /**
   * Allows to check the liveness of carbonio-docs-connector service.
   *
   * @return a <code>true</code> if the carbonio-docs-connector is live, false otherwise.
   */
  public boolean healthLiveCheck() {
    final HttpGet request = new HttpGet(docsConnectorUrl + HEALTH_LIVE_ENDPOINT);

    final RequestConfig requestConfig =
        RequestConfig.custom()
            .setConnectTimeout(TIMEOUT_IN_MS)
            .setSocketTimeout(TIMEOUT_IN_MS)
            .build();
    request.setConfig(requestConfig);

    try (final CloseableHttpResponse docsConnectorResponse = httpClient.execute(request)) {
      return docsConnectorResponse.getStatusLine().getStatusCode() == 204;
    } catch (Exception exception) {
      return false;
    }
  }
}
