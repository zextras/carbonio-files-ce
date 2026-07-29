// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.clients;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zextras.carbonio.files.Constants;
import com.zextras.carbonio.quarkus.extensions.bootstrap.NetworkingConfigService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Live (per-call) Consul KV single-key reader, distinct from the boot-time snapshot exposed by
 * {@code ApplicationConfigService}.
 *
 * <p>Unlike most Consul-KV-backed tunables (read once at boot via the extension's root-recurse
 * snapshot), {@code ConfigDataFetcher#getConfigs} needs legacy parity with the pre-Quarkus
 * behaviour: the old Guice {@code ConfigDataFetcher} hit {@code ServiceDiscoverHttpClient} fresh
 * on every {@code getConfigs} GraphQL call, so a KV value changed after boot (or a malformed one)
 * was observed immediately, not only after a restart. This client restores that live-read for the
 * one caller that needs it ({@link com.zextras.carbonio.files.config.FilesConfig
 * #getMaxNumberOfVersionsRaw()}).
 */
@ApplicationScoped
public class ServiceDiscoverHttpClient {

  private static final Logger logger = LoggerFactory.getLogger(ServiceDiscoverHttpClient.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final String kvBaseUrl;
  private final HttpClient httpClient;

  @Inject
  public ServiceDiscoverHttpClient(NetworkingConfigService networkingConfig) {
    String host =
        networkingConfig
            .get(Constants.ServiceDiscover.HOST_PROPERTY)
            .orElse(Constants.ServiceDiscover.DEFAULT_HOST);
    String port =
        networkingConfig
            .get(Constants.ServiceDiscover.PORT_PROPERTY)
            .orElse(String.valueOf(Constants.ServiceDiscover.DEFAULT_PORT));
    this.kvBaseUrl =
        String.format(
            "http://%s:%s/v1/kv/%s/", host, port, Constants.ServiceDiscover.SERVICE_NAME);
    // Force HTTP/1.1, same rationale as MailboxHttpClient/PreviewClient: WireMock/Jetty in the
    // ITs (and Consul's real agent) only speak plain HTTP/1.1.
    this.httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
  }

  /**
   * Reads a single {@code carbonio-files/<key>} Consul KV entry, live, right now. Returns {@link
   * Optional#empty()} for anything short of a clean 200 with a well-formed body (missing key,
   * ServiceDiscover down, malformed response) — callers are expected to fall back to a default,
   * exactly like the legacy client.
   */
  public Optional<String> getConfig(String key) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(kvBaseUrl + key))
              .header("X-Consul-Token", consulToken())
              .GET()
              .build();
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        return Optional.empty();
      }
      JsonNode root = MAPPER.readTree(response.body());
      if (!root.isArray() || root.isEmpty()) {
        return Optional.empty();
      }
      String base64Value = root.get(0).path("Value").asText(null);
      if (base64Value == null) {
        return Optional.empty();
      }
      // .trim(): a KV value written with a trailing newline (e.g. by an operator via `consul kv
      // put -` or a shell redirect) must not break a caller that parses it as a number/enum.
      return Optional.of(
          new String(Base64.getDecoder().decode(base64Value), StandardCharsets.UTF_8).trim());
    } catch (Exception e) {
      logger.warn("Live Consul KV read failed for carbonio-files/{}: {}", key, e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * Writes a single {@code carbonio-files/<key>} Consul KV entry ONLY IF IT DOES NOT ALREADY
   * EXIST, using Consul's {@code ?cas=0} check-and-set semantics (a CAS write against index 0
   * succeeds only when the key is currently absent). Used at boot to converge every instance of a
   * cluster on ONE shared value (e.g. the page-token HMAC secret): the first instance to reach
   * Consul wins and every other instance's write is rejected, so they all subsequently read back
   * the winner's value via {@link #getConfig(String)}. Returns {@code true} only when THIS call
   * created the key; {@code false} for a losing race, a non-200 response (including an
   * ACL-rejected/403 write), or any transport failure — callers must not treat {@code false} as
   * fatal.
   */
  public boolean createConfigIfAbsent(String key, String value) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(kvBaseUrl + key + "?cas=0"))
              .header("X-Consul-Token", consulToken())
              .PUT(HttpRequest.BodyPublishers.ofString(value, StandardCharsets.UTF_8))
              .build();
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        return false;
      }
      return Boolean.parseBoolean(response.body().trim());
    } catch (Exception e) {
      logger.warn("Consul KV cas=0 write failed for carbonio-files/{}: {}", key, e.getMessage());
      return false;
    }
  }

  private static String consulToken() {
    String token = System.getenv("CONSUL_HTTP_TOKEN");
    return token == null ? "" : token;
  }
}
