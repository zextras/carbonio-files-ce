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

/**
 * Minimal HTTP client for carbonio-docs-connector's own liveness probe: {@code GET
 * /q/health/live}, the default SmallRye-health liveness path docs-connector (itself a Quarkus
 * service) exposes. Host/port come from {@link NetworkingConfigService} ({@code
 * networking-config.carbonio.docs-connector.*}, defaulting to the mesh IP/port from {@code
 * package/carbonio-files.hcl}: {@code 127.78.0.2:20005}).
 *
 * <p><b>Informational only:</b> the result reports whether docs-connector is reachable; it must
 * never gate carbonio-files' own mesh health (self-only house pattern — a downstream dependency
 * being down cannot flip this service's {@code /q/health/live}).
 */
@ApplicationScoped
public class DocsConnectorHttpClient {

  private static final Logger logger = LoggerFactory.getLogger(DocsConnectorHttpClient.class);
  private static final String LIVE_ENDPOINT = "q/health/live";
  private static final Duration PROBE_TIMEOUT = Duration.ofMillis(500);

  private final String docsConnectorUrl;
  private final HttpClient httpClient;

  @Inject
  public DocsConnectorHttpClient(NetworkingConfigService networkingConfig) {
    String host =
        networkingConfig
            .get(Constants.Config.DocsConnector.HOST_PROPERTY)
            .orElse(Constants.Config.DocsConnector.DEFAULT_HOST);
    String port =
        networkingConfig
            .get(Constants.Config.DocsConnector.PORT_PROPERTY)
            .orElse(String.valueOf(Constants.Config.DocsConnector.DEFAULT_PORT));
    this.docsConnectorUrl =
        String.format("%s://%s:%s/", Constants.Config.DocsConnector.DEFAULT_PROTOCOL, host, port);
    // Force HTTP/1.1 for the same reason as MailboxHttpClient/PreviewClient: the JDK client's
    // default HTTP/2-with-upgrade-attempt trips plaintext HTTP/1.1-only servers (WireMock in ITs).
    this.httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
  }

  /**
   * @return true if docs-connector answers its own liveness check with HTTP 200, false on any
   *     non-200 response or transport-level failure (unreachable, timeout, ...).
   */
  public boolean isLive() {
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(docsConnectorUrl + LIVE_ENDPOINT))
              .timeout(PROBE_TIMEOUT)
              .GET()
              .build();
      HttpResponse<Void> response = httpClient.send(request, BodyHandlers.discarding());
      return response.statusCode() == 200;
    } catch (Exception e) {
      logger.debug("docs-connector liveness probe failed", e);
      return false;
    }
  }
}
