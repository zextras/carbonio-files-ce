// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.config.migration;

import com.zextras.carbonio.quarkus.extensions.bootstrap.setup.migration.ConfigMigration;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Migrates Consul KV DB-credential keys from the pre-Quarkus flat naming ({@code
 * carbonio-files/db-*}) to the carbonio-quarkus-extensions-database slash-separated KV path ({@code
 * carbonio-files/database/credentials/db-*}) that the database extension reads.
 *
 * <p>ConsulKvClient.set(key, value) constructs the URL as {consulBaseUrl}/v1/kv/{key}, so target
 * keys must use Consul KV path separators (slashes), not property name separators (dots).
 *
 * <p>The legacy carbonio-files-db bootstrap only ever wrote three flat DB-credential keys — {@code
 * db-name}, {@code db-username}, {@code db-password} (see {@code
 * package/carbonio-files-db-bootstrap} on the pre-Quarkus {@code dt3-pipeline} branch). The
 * pre-Quarkus stack ALSO read five optional connection-pool tuning keys ({@code hikari-*}) that an
 * operator could set by hand; mirror carbonio-tasks and migrate those to the database extension's
 * {@code db-pool-*} keys too (verbatim value copy, no unit conversion — same as tasks). The fixed
 * carbonio-files-db bootstrap (files-db quarkus-migration branch) writes the nested slash
 * credential path directly on fresh installs, and carbonio-files never shipped the dotted-key bug
 * that affected carbonio-tasks-db, so a single V1 migration is sufficient (no V2).
 *
 * <p>Idempotent: each entry is skipped if the old key no longer exists in Consul. Value-preserving
 * (no password regeneration): the runner reads the existing value and rewrites it verbatim under
 * the new key.
 */
public class V1__RenameDbCredentials extends ConfigMigration {

  private static final String SVC = "carbonio-files";

  @Override
  protected Map<String, BiConsumer<String, String>> networkingMigrations() {
    return Map.of();
  }

  @Override
  protected Map<String, BiConsumer<String, String>> applicationMigrations() {
    return Map.of(
        SVC + "/db-name",
        (k, v) -> applicationConfig.set(SVC + "/database/credentials/db-name", v),
        SVC + "/db-username",
        (k, v) -> applicationConfig.set(SVC + "/database/credentials/db-username", v),
        SVC + "/db-password",
        (k, v) -> applicationConfig.set(SVC + "/database/credentials/db-password", v),
        SVC + "/hikari-max-pool-size",
        (k, v) -> applicationConfig.set(SVC + "/database/db-pool-max-size", v),
        SVC + "/hikari-min-idle-connections",
        (k, v) -> applicationConfig.set(SVC + "/database/db-pool-min-size", v),
        SVC + "/hikari-idle-timeout",
        (k, v) -> applicationConfig.set(SVC + "/database/db-pool-idle-timeout", v),
        SVC + "/hikari-leak-detection-threshold",
        (k, v) -> applicationConfig.set(SVC + "/database/db-pool-leak-detection", v),
        SVC + "/hikari-max-lifetime",
        (k, v) -> applicationConfig.set(SVC + "/database/db-pool-max-lifetime", v));
  }
}
