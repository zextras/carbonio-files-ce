// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.clients;

import com.zextras.carbonio.files.Constants;
import com.zextras.carbonio.quarkus.extensions.bootstrap.NetworkingConfigService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class UserManagementHttpClient {

  private static final Logger logger = LoggerFactory.getLogger(UserManagementHttpClient.class);
  private static final String LIVE_ENDPOINT = "q/health/live";
  private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(2);

  private final String userManagementUrl;
  private final HttpClient httpClient;

  @Inject
  public UserManagementHttpClient(NetworkingConfigService networkingConfig) {
    String host =
        networkingConfig
            .get(Constants.Config.UserManagement.HOST_PROPERTY)
            .orElse(Constants.Config.UserManagement.DEFAULT_HOST);
    String port =
        networkingConfig
            .get(Constants.Config.UserManagement.PORT_PROPERTY)
            .orElse(String.valueOf(Constants.Config.UserManagement.DEFAULT_PORT));
    this.userManagementUrl =
        String.format("%s://%s:%s/", Constants.Config.UserManagement.DEFAULT_PROTOCOL, host, port);
    this.httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
  }

  public boolean healthLiveCheck() {
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(userManagementUrl + LIVE_ENDPOINT))
              .timeout(PROBE_TIMEOUT)
              .GET()
              .build();
      HttpResponse<Void> response = httpClient.send(request, BodyHandlers.discarding());
      return response.statusCode() == 200;
    } catch (Exception e) {
      logger.debug("user-management liveness probe failed", e);
      return false;
    }
  }
}
