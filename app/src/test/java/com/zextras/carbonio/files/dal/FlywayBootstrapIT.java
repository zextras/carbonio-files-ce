// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal;

import static org.assertj.core.api.Assertions.assertThat;

import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Out-of-process {@code @QuarkusIntegrationTest} (via {@link AbstractFilesIT}) proving the schema
 * history (V1..V11, ported verbatim from {@code core/}) boots cleanly under {@code quarkus-flyway}
 * against the real Postgres Testcontainer. Rewritten off the former {@code @Inject
 * Flyway}/{@code @Inject DataSource} (unavailable out-of-process) onto raw JDBC via {@link
 * AbstractFilesIT#jdbcConnection()} — migrations having applied at all is implicitly proven by the
 * launched app booting successfully (an out-of-process app that failed Flyway validation would
 * never come up for {@code @WithTestResource} to hand a port back), so this is now a boot-smoke
 * assertion on the resulting schema shape rather than a live {@code Flyway} bean inspection.
 */
class FlywayBootstrapIT extends AbstractFilesIT {

  @Test
  void flywayShouldMigrateSchemaUpToV12() throws Exception {
    try (Connection connection = jdbcConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT version FROM flyway_schema_history WHERE success = true AND version IS NOT"
                    + " NULL ORDER BY installed_rank DESC LIMIT 1");
        ResultSet resultSet = statement.executeQuery()) {
      assertThat(resultSet.next()).as("at least one successful migration recorded").isTrue();
      assertThat(resultSet.getString(1)).isEqualTo("12");
    }
  }

  @Test
  void hierarchicalConfigRepeatableMigrationShouldBeRecorded() throws Exception {
    try (Connection connection = jdbcConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT type, checksum FROM flyway_schema_history WHERE success = true"
                    + " AND version IS NULL AND description = 'carbonio hierarchical config'");
        ResultSet resultSet = statement.executeQuery()) {
      assertThat(resultSet.next()).as("repeatable migration row recorded").isTrue();
      assertThat(resultSet.getString("type")).isEqualTo("JDBC");
      assertThat(resultSet.getInt("checksum")).isEqualTo(1);
      assertThat(resultSet.next()).as("exactly one such row").isFalse();
    }
  }

  @Test
  void hierarchicalConfigTablesShouldExist() throws Exception {
    for (String table :
        List.of(
            "carbonio_account_config",
            "carbonio_cos_config",
            "carbonio_domain_config",
            "carbonio_global_config",
            "carbonio_config_schema")) {
      assertThat(tableExists(table)).as(table).isTrue();
    }
  }

  @Test
  void dbInfoTableShouldNotExistBecauseV10DroppedIt() throws Exception {
    assertThat(tableExists("db_info")).isFalse();
  }

  @Test
  void nodeTableShouldExistFromV1Init() throws Exception {
    assertThat(tableExists("node")).isTrue();
  }

  private boolean tableExists(String tableName) throws Exception {
    try (Connection connection = jdbcConnection()) {
      DatabaseMetaData metaData = connection.getMetaData();
      try (ResultSet resultSet = metaData.getTables(null, null, tableName, null)) {
        return resultSet.next();
      }
    }
  }
}
