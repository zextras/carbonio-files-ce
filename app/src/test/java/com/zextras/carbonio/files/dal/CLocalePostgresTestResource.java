// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Dedicated test stack for {@link CollationOrderingDalTest}: a Postgres Testcontainer whose
 * database is deliberately initialised with the locale-less {@code C} collation (via {@code
 * POSTGRES_INITDB_ARGS=--locale=C --encoding=UTF8}), NOT the shared {@link
 * com.zextras.carbonio.files.FilesStackTestResource}'s container.
 *
 * <p><b>Why this can't reuse the shared stack:</b> the official {@code postgres:16} image
 * initialises a fresh database with {@code datcollate=en_US.utf8} by default (confirmed
 * empirically), so {@code CollationRepositoryImpl#getValidCollateForQuery()} always resolves to
 * {@code Optional.empty()} there — the {@code C}-detection branch this feature exists for never
 * fires, and a name-ordering assertion would pass identically whether or not the collation fix is
 * wired at all (exactly the "container's own default collation may already mask the difference"
 * trap). Forcing {@code datcollate=C} here reproduces the one environment the feature is actually
 * FOR, so the ordering genuinely differs with vs. without the fix.
 *
 * <p>Only Consul (DB credential KV) + Postgres are stubbed: {@code CollationOrderingDalTest} only
 * exercises {@code NodeRepository}, never storages/user-management/preview, and none of those
 * producers make an eager connection at boot (see {@code FilesConfig}/{@code FilestoreProducer}
 * javadocs), so no other fakes are needed for the app to start.
 */
public class CLocalePostgresTestResource implements QuarkusTestResourceLifecycleManager {

  private static final String DB_NAME = "carbonio-files-db";
  private static final String DB_USER = "test";
  private static final String DB_PASSWORD = "test";

  private static volatile boolean started = false;
  private static Map<String, String> cachedConfig;
  private static WireMockServer consulMock;
  private static PostgreSQLContainer<?> postgres;

  @Override
  public Map<String, String> start() {
    if (started) {
      return cachedConfig;
    }

    postgres =
        new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName(DB_NAME)
            .withUsername(DB_USER)
            .withPassword(DB_PASSWORD)
            .withEnv("POSTGRES_INITDB_ARGS", "--locale=C --encoding=UTF8");
    postgres.start();

    consulMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    consulMock.start();
    setupConsulStubs(consulMock);

    String jdbcUrl =
        String.format(
            "jdbc:postgresql://%s:%d/%s?sslmode=disable",
            postgres.getHost(), postgres.getFirstMappedPort(), DB_NAME);

    cachedConfig =
        Map.ofEntries(
            Map.entry("quarkus.http.host", "localhost"),
            Map.entry("networking-config.carbonio.service-discover.host", "localhost"),
            Map.entry(
                "networking-config.carbonio.service-discover.port",
                String.valueOf(consulMock.port())),
            Map.entry("networking-config.carbonio.postgresql.host", postgres.getHost()),
            Map.entry(
                "networking-config.carbonio.postgresql.port",
                String.valueOf(postgres.getFirstMappedPort())),
            Map.entry("quarkus.datasource.jdbc.url", jdbcUrl),
            Map.entry("quarkus.datasource.username", DB_USER),
            Map.entry("quarkus.datasource.password", DB_PASSWORD),
            Map.entry("quarkus.scheduler.enabled", "false"));

    started = true;
    return cachedConfig;
  }

  @Override
  public void stop() {
    // Testcontainers' JVM shutdown hook stops postgres; no-op here (mirrors
    // FilesStackTestResource).
  }

  /**
   * Trimmed copy of {@code FilesStackTestResource#setupConsulStubs}: DB creds + the generic
   * bootstrap/mesh-registration endpoints the carbonio-quarkus-extensions-bootstrap extension calls
   * at boot regardless of which business feature a test exercises.
   */
  private static void setupConsulStubs(WireMockServer server) {
    String[][] kvEntries = {
      {"carbonio-files/database/credentials/db-name", DB_NAME},
      {"carbonio-files/database/credentials/db-username", DB_USER},
      {"carbonio-files/database/credentials/db-password", DB_PASSWORD},
    };
    server.stubFor(
        get(urlPathEqualTo("/v1/kv/"))
            .atPriority(1)
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(buildKvArrayJson(kvEntries))));

    server.stubFor(
        get(urlPathMatching("/v1/kv/.*")).atPriority(10).willReturn(aResponse().withStatus(404)));

    for (String pattern :
        new String[] {
          "/v1/agent/service/register.*",
          "/v1/agent/service/deregister/.*",
          "/v1/agent/check/register.*",
          "/v1/agent/check/deregister/.*"
        }) {
      server.stubFor(put(urlPathMatching(pattern)).willReturn(aResponse().withStatus(200)));
    }

    for (String pattern : new String[] {"/v1/health/service/.*", "/v1/catalog/service/.*"}) {
      server.stubFor(
          get(urlPathMatching(pattern))
              .willReturn(
                  aResponse()
                      .withStatus(200)
                      .withHeader("Content-Type", "application/json")
                      .withBody("[]")));
    }

    server.stubFor(
        get(urlPathEqualTo("/v1/agent/self"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"Config\":{\"Datacenter\":\"dc1\",\"NodeName\":\"mock-consul\"}}")));
    server.stubFor(
        get(urlPathEqualTo("/v1/status/leader"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("\"127.0.0.1:8300\"")));
  }

  private static String buildKvArrayJson(String[][] entries) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < entries.length; i++) {
      String key = entries[i][0];
      String value =
          Base64.getEncoder().encodeToString(entries[i][1].getBytes(StandardCharsets.UTF_8));
      if (i > 0) {
        sb.append(",");
      }
      sb.append("{\"LockIndex\":0,\"Key\":\"")
          .append(key)
          .append("\",\"Flags\":0,\"Value\":\"")
          .append(value)
          .append("\",\"CreateIndex\":1,\"ModifyIndex\":1}");
    }
    sb.append("]");
    return sb.toString();
  }
}
