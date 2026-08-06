// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.config.migration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.zextras.carbonio.quarkus.extensions.bootstrap.setup.migration.ConfigMigrationRunner;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises the real {@link V1__RenameDbCredentials} the way {@code
 * carbonio-quarkus-extensions-bootstrap} runs it in production: {@link ConfigMigrationRunner} loads
 * the migration by FQCN (via {@code Class.forName}, exactly like the build-time-generated {@code
 * META-INF/carbonio-migrations.list}) and executes it against the Consul HTTP KV API.
 *
 * <p>Not a {@code @QuarkusTest}: {@code ConfigMigrationRunner} is invoked by the {@code --setup}
 * CLI path BEFORE Quarkus starts (see {@code SetupAwareMain}), so a plain WireMock Consul double is
 * enough to exercise it end-to-end — no app boot / {@code FilesStackTestResource} involved.
 */
class V1RenameDbCredentialsIT {

  private static final String SVC = "carbonio-files";

  private WireMockServer consul;

  @TempDir Path tempDir;

  @BeforeEach
  void startConsulStub() {
    consul = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    consul.start();
  }

  @AfterEach
  void stopConsulStub() {
    consul.stop();
  }

  @Test
  void migratesFlatDbCredentialKeysToNestedPath() {
    // Seed the OLD flat keys, as written by the legacy carbonio-files-db bootstrap.
    stubOldKey("db-name", "carbonio-files-db");
    stubOldKey("db-username", "carbonio-files-db");
    stubOldKey("db-password", "s3cr3t");
    stubWrite("database/credentials/db-name");
    stubWrite("database/credentials/db-username");
    stubWrite("database/credentials/db-password");
    stubDelete("db-name");
    stubDelete("db-username");
    stubDelete("db-password");

    runMigration();

    consul.verify(
        putRequestedFor(urlEqualTo("/v1/kv/" + SVC + "/database/credentials/db-name"))
            .withRequestBody(equalTo("carbonio-files-db")));
    consul.verify(
        putRequestedFor(urlEqualTo("/v1/kv/" + SVC + "/database/credentials/db-username"))
            .withRequestBody(equalTo("carbonio-files-db")));
    consul.verify(
        putRequestedFor(urlEqualTo("/v1/kv/" + SVC + "/database/credentials/db-password"))
            .withRequestBody(equalTo("s3cr3t")));

    // Value-preserving: the exact old value is the one written under the new key (no
    // regeneration), and the old flat key is removed once migrated.
    consul.verify(deleteRequestedFor(urlEqualTo("/v1/kv/" + SVC + "/db-name")));
    consul.verify(deleteRequestedFor(urlEqualTo("/v1/kv/" + SVC + "/db-username")));
    consul.verify(deleteRequestedFor(urlEqualTo("/v1/kv/" + SVC + "/db-password")));
  }

  @Test
  void freshInstall_oldKeysAbsent_isNoOp() {
    // No old-key stubs registered: WireMock's default 404 for the GET ?raw lookups mirrors
    // Consul's real response for a missing key, so the migration must skip every entry.
    runMigration(); // must not throw

    consul.verify(0, putRequestedFor(urlMatching("/v1/kv/.*")));
    consul.verify(0, deleteRequestedFor(urlMatching("/v1/kv/.*")));
  }

  private void runMigration() {
    ConfigMigrationRunner runner =
        new ConfigMigrationRunner(
            "http://localhost:" + consul.port(),
            "test-token",
            tempDir.resolve("nonexistent-config.properties"),
            List.of(V1__RenameDbCredentials.class.getName()));
    runner.execute();
  }

  private void stubOldKey(String shortKey, String value) {
    consul.stubFor(
        get(urlEqualTo("/v1/kv/" + SVC + "/" + shortKey + "?raw"))
            .willReturn(aResponse().withStatus(200).withBody(value)));
  }

  private void stubWrite(String newKeySuffix) {
    consul.stubFor(
        put(urlEqualTo("/v1/kv/" + SVC + "/" + newKeySuffix))
            .willReturn(aResponse().withStatus(200).withBody("true")));
  }

  private void stubDelete(String shortKey) {
    consul.stubFor(
        delete(urlEqualTo("/v1/kv/" + SVC + "/" + shortKey))
            .willReturn(aResponse().withStatus(200).withBody("true")));
  }
}
