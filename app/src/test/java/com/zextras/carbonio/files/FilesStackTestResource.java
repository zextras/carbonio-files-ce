// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.zextras.carbonio.files.it.support.MockStoragesService;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Trimmed integration test stack for the P2a Flyway/datasource bootstrap slice of
 * carbonio-files-ce.
 *
 * <p>Only two dependencies are needed to prove the schema boots:
 *
 * <ul>
 *   <li>Consul (service discovery + KV credential lookup at boot) — stubbed by an in-process
 *       {@link WireMockServer}, following the same idiom as {@code carbonio-tasks-ce}'s {@code
 *       StackTestResource} (root {@code /v1/kv/} recurse stub returning base64-encoded DB
 *       credentials) but using the {@code org.wiremock:wiremock} Java library instead of a
 *       WireMock Docker container, since no other consumer needs the container network alias
 *       trick tasks-ce uses for its second mock role.
 *   <li>PostgreSQL — a real {@code postgres:16} Testcontainer, so Flyway migrates against a real
 *       engine.
 * </ul>
 *
 * <p>No user-management / mailbox stubs are wired here: this resource only proves the
 * datasource + Flyway foundation boots, not auth or any business endpoint (that's P2b/P2c and
 * beyond).
 *
 * <p>Containers are static singletons: they start once per JVM and are reused across all {@code
 * @QuarkusTest} classes that use this resource. {@code stop()} is a no-op; Testcontainers' JVM
 * shutdown hook handles cleanup.
 */
public class FilesStackTestResource implements QuarkusTestResourceLifecycleManager {

  private static final String DB_NAME = "carbonio-files-db";
  private static final String DB_USER = "test";
  private static final String DB_PASSWORD = "test";

  /**
   * Fixed {@code ZM_AUTH_TOKEN} recognised by the in-process user-management REST fake. Any other
   * token → {@code 401} from {@code GET /internal/users/myself} → the auth filter returns HTTP 401.
   */
  public static final String AUTH_TOKEN = "test-auth-token-files-ce";

  /** Fixed account id returned by the REST fake for the {@link #AUTH_TOKEN} test user. */
  public static final String TEST_USER_ID = "00000000-0000-0000-0000-000000000001";

  /**
   * JDBC URL for the shared Postgres Testcontainer, exposed so {@code @QuarkusIntegrationTest}
   * classes (out-of-process: no {@code @Inject}/Arc available) can open a raw JDBC connection for
   * cleanup ({@code @AfterEach} DELETE/TRUNCATE, mirroring {@code
   * QuarkusTestDataAccess#resetDatabase}) and for the rare API-observable-not-creatable read-back
   * (tombstone/version rows). Mirrors {@code carbonio-tasks-ce}'s {@code StackTestResource
   * .POSTGRES_JDBC_URL}. Credentials are the fixed {@link #DB_USER}/{@link #DB_PASSWORD} test values.
   */
  public static volatile String POSTGRES_JDBC_URL;

  private static volatile boolean started = false;
  private static Map<String, String> cachedConfig;

  private static WireMockServer consulMock;
  private static WireMockServer previewMailboxMock;
  private static PostgreSQLContainer<?> postgres;
  private static com.zextras.carbonio.files.utilities.MockUserManagementService userManagementService;
  private static MockStoragesService storagesService;

  @Override
  public Map<String, String> start() {
    if (started) {
      return cachedConfig;
    }

    postgres =
        new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName(DB_NAME)
            .withUsername(DB_USER)
            .withPassword(DB_PASSWORD);
    postgres.start();

    consulMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    consulMock.start();
    setupConsulStubs(consulMock);

    // P5b: a SEPARATE WireMock instance for preview/mailbox, deliberately not sharing consulMock's
    // port. carbonio-quarkus-extensions-bootstrap's Consul HTTP client negotiates an HTTP/1.1 ->
    // h2c upgrade on its connections; once that upgrade lands on a WireMock/Jetty connector, the
    // JDK java.net.http client used by PreviewClient/MailboxHttpClient (pinned to plain HTTP/1.1)
    // can end up reading a response body that never signals EOF on that shared connector, hanging
    // until the test's own HTTP client socket-read-times-out. A dedicated instance never sees an
    // h2c upgrade attempt, so it never trips into that dual-protocol connector state.
    previewMailboxMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    previewMailboxMock.start();
    setupPreviewAndMailboxStubs(previewMailboxMock);

    userManagementService = new com.zextras.carbonio.files.utilities.MockUserManagementService();
    // Default fixture: the fixed AUTH_TOKEN resolves to an ACTIVE, INTERNAL user with the files
    // feature enabled (same contract the previous fixed stub honoured, so existing @QuarkusTest
    // ITs keep working). The acceptance seam adds/overwrites more tokens at runtime.
    userManagementService.registerToken(AUTH_TOKEN, TEST_USER_ID);

    // Phase 0 (storages cutover): a dedicated in-process WireMock fake for carbonio-storages, so the
    // app's REAL FilestoreProducer/StoragesClient talks to it over real HTTP instead of relying on an
    // in-process @io.quarkus.test.Mock Filestore CDI double (see MockStoragesService's javadoc).
    storagesService = new MockStoragesService();

    String jdbcUrl =
        String.format(
            "jdbc:postgresql://%s:%d/%s?sslmode=disable",
            postgres.getHost(), postgres.getFirstMappedPort(), DB_NAME);
    POSTGRES_JDBC_URL = jdbcUrl;

    cachedConfig =
        Map.ofEntries(
            // Phase 1 (@QuarkusIntegrationTest support): the bootstrap extension's
            // CarbonioBootstrapFactory derives the ACTUAL Vert.x HTTP bind host/port from
            // networking-config.carbonio.service.host/port (default 127.78.0.2, the production mesh
            // IP; see application.properties:16). Under @QuarkusTest this is masked by the
            // %test.networking-config.carbonio.service.host=localhost override in
            // application.properties, but @QuarkusIntegrationTest launches the PACKAGED artifact
            // under quarkus.profile=prod (confirmed empirically: the launch command carries
            // "-Dquarkus.profile=prod"), where that %test.-scoped line never applies -> the app binds
            // to 127.78.0.2 and RestAssured's default localhost connection is refused. Force
            // quarkus.http.host directly (same channel/precedence the test framework itself already
            // uses for quarkus.http.port) so the launched app is reachable regardless of profile.
            Map.entry("quarkus.http.host", "localhost"),
            // Consul (files service discovery / KV) → WireMock
            Map.entry("networking-config.carbonio.service-discover.host", "localhost"),
            Map.entry(
                "networking-config.carbonio.service-discover.port",
                String.valueOf(consulMock.port())),
            // PostgreSQL (files database)
            Map.entry("networking-config.carbonio.postgresql.host", postgres.getHost()),
            Map.entry(
                "networking-config.carbonio.postgresql.port",
                String.valueOf(postgres.getFirstMappedPort())),
            Map.entry("quarkus.datasource.jdbc.url", jdbcUrl),
            Map.entry("quarkus.datasource.username", DB_USER),
            Map.entry("quarkus.datasource.password", DB_PASSWORD),
            // user-management REST client (UserResourceApi) → the dedicated in-process WireMock fake.
            Map.entry("networking-config.carbonio.user-management.host", "localhost"),
            Map.entry(
                "networking-config.carbonio.user-management.port",
                String.valueOf(userManagementService.getPort())),
            // P5b: carbonio-preview / carbonio-mailbox upstreams → the dedicated WireMock instance
            // (see the comment on previewMailboxMock's construction above for why it's separate
            // from consulMock).
            Map.entry("networking-config.carbonio.preview.host", "localhost"),
            Map.entry(
                "networking-config.carbonio.preview.port",
                String.valueOf(previewMailboxMock.port())),
            Map.entry("networking-config.carbonio.mailbox.host", "localhost"),
            Map.entry(
                "networking-config.carbonio.mailbox.port",
                String.valueOf(previewMailboxMock.port())),
            // carbonio-docs-connector: reuses the same dedicated WireMock instance as
            // preview/mailbox (DocsConnectorHttpClient forces HTTP/1.1 too, so it's not affected by
            // the h2c-upgrade issue that keeps consulMock separate).
            Map.entry("networking-config.carbonio.docs-connector.host", "localhost"),
            Map.entry(
                "networking-config.carbonio.docs-connector.port",
                String.valueOf(previewMailboxMock.port())),
            // Phase 0: carbonio-storages (Filestore/StoragesClient) -> the dedicated MockStoragesService
            // WireMock fake, replacing the in-process InMemoryFilestore @Mock CDI double.
            Map.entry("networking-config.carbonio.storages.host", "localhost"),
            Map.entry(
                "networking-config.carbonio.storages.port",
                String.valueOf(storagesService.getPort())),
            // Defensive infra fix: under @QuarkusIntegrationTest the launched app runs on the PROD
            // profile, so the seam's %test.quarkus.scheduler.enabled=false override never applies.
            // Without this, the @Scheduled purge job runs LIVE against the shared Postgres
            // Testcontainer during trash/version/delete ITs, causing cross-test flakiness (rows
            // disappearing out from under an assertion mid-test). Disable the scheduler outright via
            // the same config channel.
            Map.entry("quarkus.scheduler.enabled", "false"));

    started = true;
    return cachedConfig;
  }

  /** Exposes the Consul-stub WireMock instance, if a test ever needs to override a Consul stub. */
  public static WireMockServer getWireMock() {
    return consulMock;
  }

  /**
   * Exposes the mutable in-process user-management REST fake (a dedicated {@link WireMockServer}
   * wrapped by {@link com.zextras.carbonio.files.utilities.MockUserManagementService}) so the
   * acceptance seam ({@code QuarkusMocks}/{@code QuarkusFilesTestAppBuilder}) can register
   * per-token fixtures ({@code withUserManagement}/{@code registerUser}) and flip the reversible
   * "down" switch ({@code userManagementDown}). The default {@link #AUTH_TOKEN} → {@link
   * #TEST_USER_ID} fixture is always registered at boot.
   */
  public static com.zextras.carbonio.files.utilities.MockUserManagementService
      getUserManagementService() {
    return userManagementService;
  }

  /**
   * Exposes the mutable in-process carbonio-storages REST fake (a dedicated {@link WireMockServer}
   * wrapped by {@link MockStoragesService}) so the acceptance seam ({@code QuarkusMocks}/{@code
   * DatabasePopulator}/{@code QuarkusFilesTestApp}) and component ITs can seed blobs, flip
   * failure-injection switches, and verify upload/download activity.
   */
  public static MockStoragesService getStoragesService() {
    return storagesService;
  }

  /**
   * Exposes the carbonio-preview/carbonio-mailbox WireMock instance so individual {@code
   * @QuarkusTest} classes can register per-test overriding stubs (default priority 5, higher
   * priority than the generic fallbacks registered by {@link #setupPreviewAndMailboxStubs}, which
   * run at priority 10).
   */
  public static WireMockServer getPreviewMailboxWireMock() {
    return previewMailboxMock;
  }

  /**
   * Resets the preview/mailbox WireMock to its baseline (clears per-test stubs, re-adds the generic
   * fallbacks). Used by the acceptance seam's {@code Mocks#reset()} — the Quarkus counterpart of the
   * legacy {@code Simulator#reinitializeMocks()} preview/mailbox reset.
   */
  public static void resetPreviewMailboxStubs() {
    if (previewMailboxMock != null) {
      previewMailboxMock.resetMappings();
      setupPreviewAndMailboxStubs(previewMailboxMock);
    }
  }

  /** Resets the Consul-stub WireMock to its baseline (clears per-test KV stubs, re-adds defaults). */
  public static void resetConsulStubs() {
    if (consulMock != null) {
      consulMock.resetMappings();
      setupConsulStubs(consulMock);
    }
  }

  @Override
  public void stop() {
    // Containers/servers are static singletons: they persist for the full test-run JVM lifetime.
    // Testcontainers' JVM shutdown hook stops postgres; the JVM exiting tears down the WireMock
    // server's listener along with it. No-op here, mirroring carbonio-tasks-ce's StackTestResource.
  }

  /**
   * Registers WireMock stubs that impersonate the Consul HTTP API, mirroring {@code
   * carbonio-tasks-ce}'s {@code StackTestResource#setupConsulStubs}.
   *
   * <p>carbonio-quarkus-extensions (>= 1.10.x) issues a SINGLE ROOT recursive GET at boot:
   * {@code GET /v1/kv/?recurse} (prefix == "", urlPath ignores the query string). Consul
   * ACL-filters that root recurse to the keys the token can read; the boot factory then derives
   * the own-service application-config view from the {@code carbonio-files/*} subset. So we stub
   * the ROOT recurse and return all three credential entries in the Consul recursive-response
   * format (values base64-encoded, as the real Consul API does for {@code ?recurse}).
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

    // Catch-all for unknown KV keys → 404 (lowest priority; urlPathMatching ignores the query).
    server.stubFor(
        get(urlPathMatching("/v1/kv/.*"))
            .atPriority(10)
            .willReturn(aResponse().withStatus(404)));

    // Service registration / deregistration → 200
    for (String pattern :
        new String[] {
          "/v1/agent/service/register.*",
          "/v1/agent/service/deregister/.*",
          "/v1/agent/check/register.*",
          "/v1/agent/check/deregister/.*"
        }) {
      server.stubFor(put(urlPathMatching(pattern)).willReturn(aResponse().withStatus(200)));
    }

    // Service discovery → empty array
    for (String pattern : new String[] {"/v1/health/service/.*", "/v1/catalog/service/.*"}) {
      server.stubFor(
          get(urlPathMatching(pattern))
              .willReturn(
                  aResponse()
                      .withStatus(200)
                      .withHeader("Content-Type", "application/json")
                      .withBody("[]")));
    }

    // Agent self / status (urlPath = path-only exact match, ignores the query string)
    server.stubFor(
        get(urlPathEqualTo("/v1/agent/self"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"Config\":{\"Datacenter\":\"dc1\",\"NodeName\":\"mock-consul\"}}")));
    server.stubFor(
        get(urlPathEqualTo("/v1/status/leader"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("\"127.0.0.1:8300\"")));
  }

  /**
   * Registers generic, LOW-priority fallback stubs (priority 10) for the carbonio-preview and
   * carbonio-mailbox upstreams on the dedicated {@code previewMailboxMock} instance. Individual
   * {@code @QuarkusTest} classes register their own higher-priority (default, 5) stubs via {@link
   * #getPreviewMailboxWireMock()} for scenario-specific bytes/status codes; these fallbacks just
   * keep any un-stubbed call from 404-ing.
   */
  private static void setupPreviewAndMailboxStubs(WireMockServer server) {
    // carbonio-preview: PreviewClient#healthReady() → GET {baseUrl}/health/ready/
    server.stubFor(
        get(urlPathEqualTo("/health/ready/")).atPriority(10).willReturn(aResponse().withStatus(200)));

    // carbonio-preview: any preview/thumbnail fetch → a minimal canned PNG.
    byte[] fallbackPreviewBytes = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a};
    server.stubFor(
        get(urlPathMatching("/preview/.*"))
            .atPriority(10)
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "image/png")
                    .withBody(fallbackPreviewBytes)));

    // carbonio-mailbox: POST service/upload?fmt=raw → a canned success CSV response.
    server.stubFor(
        post(urlPathMatching("/service/upload.*"))
            .atPriority(10)
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withBody("200,'null','fallback-attachment-id'\n")));
  }

  /**
   * Builds a Consul-format recursive KV response JSON for the given entries, matching what the
   * real Consul API returns for {@code ?recurse} (values base64-encoded).
   */
  private static String buildKvArrayJson(String[][] entries) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < entries.length; i++) {
      String key = entries[i][0];
      String value = Base64.getEncoder().encodeToString(entries[i][1].getBytes(StandardCharsets.UTF_8));
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
