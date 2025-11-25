// SPDX-FileCopyrightText: 2025 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.impl;

import com.google.inject.Inject;
import com.zaxxer.hikari.HikariDataSource;
import com.zextras.carbonio.files.dal.DatabaseManager;
import io.ebean.Database;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DatabaseManagerFlyway implements DatabaseManager {
  private static final Logger logger = LoggerFactory.getLogger(DatabaseManagerFlyway.class);

  private final HikariDataSource dataSource;
  private final Database ebeanDatabase;
  private Flyway flyway;

  @Inject
  public DatabaseManagerFlyway(HikariDataSource dataSource, Database ebeanDatabase) {
    this.dataSource = dataSource;
    this.ebeanDatabase = ebeanDatabase;
  }

  @Override
  public void initialize() {
    Optional<Integer> legacyVersion = getLegacyDatabaseVersion();

    FluentConfiguration config = Flyway.configure()
      .dataSource(dataSource)
      .configuration(Map.of("flyway.postgresql.transactional.lock", "false"));

    // The idea here is to use legacy version (if present) to set baseline.
    // Both empty db and post-Flyway db will have no legacy version so Flyway can handle migration without issues;
    // if the database is in a legacy version but already initialize we set the baseline so it can be migrated by Flyway
    // only from where needed.
    legacyVersion.ifPresent(integer -> config.baselineVersion(String.valueOf(integer)).baselineOnMigrate(true));

    flyway = config.load();
    flyway.migrate();
  }

  private Optional<Integer> getLegacyDatabaseVersion() {
    try {
      return ebeanDatabase
          .sqlQuery("SELECT version FROM db_info")
          .findOneOrEmpty()
          .map(row -> row.getInteger("version"));
    } catch (Exception e) {
        return Optional.empty();
    }
  }

  @Override
  public String getDatabaseVersion() {
    return flyway.info().current() != null
        ? flyway.info().current().getVersion().getVersion()
        : "0";
  }

  @Override
  public boolean isDatabaseLive() {
    try {
      try (Connection connection = flyway.getConfiguration().getDataSource().getConnection()) {
        return connection.isValid(1);
      }
    } catch (SQLException e) {
      logger.error("Database liveness check failed", e);
      return false;
    }
  }

  @Override
  public boolean isDatabaseCorrectVersion() {
    return flyway.info().current() != null
        && !flyway.info().current().getPhysicalLocation().isEmpty();
  }

  @Override
  public void stop() {
    if (ebeanDatabase != null) {
      logger.info("Shutting down database connection...");
      ebeanDatabase.shutdown(true, true);
    }
  }
}