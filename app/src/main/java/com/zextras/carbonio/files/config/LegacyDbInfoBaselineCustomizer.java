// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.config;

import io.quarkus.flyway.FlywayConfigurationCustomizer;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.jboss.logging.Logger;

@ApplicationScoped
public class LegacyDbInfoBaselineCustomizer implements FlywayConfigurationCustomizer {

  private static final Logger LOG = Logger.getLogger(LegacyDbInfoBaselineCustomizer.class);

  @Override
  public void customize(FluentConfiguration configuration) {
    try (Connection connection = configuration.getDataSource().getConnection()) {
      String schema = configuration.getDefaultSchema();
      if (tableExists(connection, schema, configuration.getTable())
          || !tableExists(connection, schema, "db_info")) {
        return;
      }
      try (PreparedStatement statement =
              connection.prepareStatement("SELECT max(version) FROM db_info");
          ResultSet rows = statement.executeQuery()) {
        rows.next();
        int version = rows.getInt(1);
        if (rows.wasNull()) {
          return;
        }
        LOG.infof("Legacy db_info version %d found, using it as the Flyway baseline", version);
        configuration.baselineOnMigrate(true).baselineVersion(String.valueOf(version));
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Cannot inspect the database for a legacy db_info table", e);
    }
  }

  private static boolean tableExists(Connection connection, String schema, String table)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT 1 FROM information_schema.tables"
                + " WHERE table_schema = COALESCE(?, current_schema()) AND table_name = ?")) {
      statement.setString(1, schema);
      statement.setString(2, table);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next();
      }
    }
  }
}
