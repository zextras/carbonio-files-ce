// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal;

import static org.assertj.core.api.Assertions.assertThat;

import com.zextras.carbonio.files.FilesStackTestResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/**
 * P2a: proves that the existing schema history (V1..V11, ported verbatim from {@code core/}) boots
 * cleanly under {@code quarkus-flyway} against a real Testcontainers PostgreSQL instance.
 *
 * <p>No entities/repositories are ported yet (that's P2b/P2c) — this only exercises the
 * datasource + Flyway wiring provided by {@code carbonio-quarkus-extensions-database}.
 */
@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class FlywayBootstrapIT {

  @Inject Flyway flyway;

  @Inject DataSource dataSource;

  @Test
  void flywayShouldMigrateSchemaUpToV11() {
    assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("11");
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
    try (Connection connection = dataSource.getConnection()) {
      DatabaseMetaData metaData = connection.getMetaData();
      try (ResultSet resultSet = metaData.getTables(null, null, tableName, null)) {
        return resultSet.next();
      }
    }
  }
}
