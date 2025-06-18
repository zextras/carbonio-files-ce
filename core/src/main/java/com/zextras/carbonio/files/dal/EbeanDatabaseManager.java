// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.zaxxer.hikari.HikariDataSource;
import com.zextras.carbonio.files.Constants;
import com.zextras.carbonio.files.Constants.Db;
import com.zextras.carbonio.files.config.FilesConfig;
import com.zextras.carbonio.files.dal.dao.ebean.DbInfo;
import io.ebean.Database;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.MessageFormat;
import java.util.Objects;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the creation of a single instance of the EbeanDatabase.
 *
 * <p>The {@link #start()} method creates the related {@link DataSource} necessary to create the
 * eBean {@link Database}.
 *
 * <p>Every single ebean entity <strong>must</strong> be added in the {@link this#entityList}
 * otherwise the custom builder is not be able to find it and register it in the {@link Database}.
 */
@Singleton
public class EbeanDatabaseManager {

  private static final Logger logger = LoggerFactory.getLogger(EbeanDatabaseManager.class);
  private final FilesConfig filesConfig;
  private final Database ebeanDatabase;
  private final HikariDataSource dataSource;
  private final String postgresDatabase;

  @Inject
  public EbeanDatabaseManager(FilesConfig filesConfig, Database ebeanDatabase, HikariDataSource dataSource) {
    this.filesConfig = filesConfig;
    this.ebeanDatabase = ebeanDatabase;
    this.dataSource = dataSource;
    this.postgresDatabase = filesConfig.getDatabaseName();
  }

  private void checkDatabaseExistence() {
    ebeanDatabase
      .sqlQuery(MessageFormat.format(
          "SELECT 1 FROM pg_database WHERE datname = {0}{1}{0}",
          "'",
          postgresDatabase
        )
      )
      .findOneOrEmpty()
      .orElseThrow(() -> {
        logger.error(MessageFormat.format(
          "Database: {0} does not exist! Execute carbonio-files-db-bootstrap command",
          postgresDatabase
        ));
        stop();
        throw new RuntimeException("Database does not exist");
      });
  }

  /**
   * This method checks if the database is initialized and returns the current version
   *
   * @return current database version
   */
  private int getCurrentDatabaseVersion() {
    boolean isDatabaseInitialized = ebeanDatabase
      .sqlQuery(MessageFormat.format(
          "SELECT 1 FROM information_schema.tables where table_name = {0}{1}{0};",
          "'",
          Constants.Db.Tables.DB_INFO.toLowerCase()
        )
      )
      .findOneOrEmpty()
      .isPresent();

    logger.info(MessageFormat.format("Database status: the database is{0} initialized",
        isDatabaseInitialized
          ? ""
          : " not"
      )
    );

    return isDatabaseInitialized
      ? ebeanDatabase.find(DbInfo.class).findOneOrEmpty().map(DbInfo::getVersion).orElse(0)
      : 0;
  }

  private void populatePostgreSQLSchema(
    Connection connection,
    int currentDbVersion
  ) {

    // Base case: the current database version is equals to the last one. It means that in the
    // previous iteration (if there was one) the system has already populated the database with the
    // last version. The database is ready!
    if (currentDbVersion >= Db.DB_VERSION) {
      return;
    }

    // If the execution arrives here then there is at least another version of the schema to populate
    int dbVersionToPopulate = currentDbVersion + 1;

    ClassLoader classLoader = getClass().getClassLoader();
    String sqlFilesToExecute = MessageFormat.format("sql/postgresql_{0}.sql", dbVersionToPopulate);

    String data = new BufferedReader(new InputStreamReader(
      Objects.requireNonNull(classLoader.getResourceAsStream(sqlFilesToExecute)),
      StandardCharsets.UTF_8
    ))
      .lines()
      .collect(Collectors.joining("\n"));

    try (Statement statement = connection.createStatement()) {
      statement.execute(data);
      logger.info(MessageFormat.format(
        "Database version {0} successfully updated!",
        dbVersionToPopulate
      ));
    } catch (SQLException exception) {
      logger.error(MessageFormat.format(
        "Unable to create schema to database {0} version {1}",
        postgresDatabase,
        dbVersionToPopulate
      ));
      logger.error(exception.getMessage());
      stop();
      throw new RuntimeException("Unable to create schema database");
    }

    populatePostgreSQLSchema(connection, ++currentDbVersion);
  }

  public Database getEbeanDatabase() {
    if (ebeanDatabase == null) {
      throw new NullPointerException("EbeanDatabase not correctly initialized");
    }
    return ebeanDatabase;
  }

  /**
   * Starts the service:
   * <ul>
   *   <li>Check if database exists</li>
   *   <li>Check current database version and populate schema if needed</li>
   * </ul>
   *
   * Note: Database and DataSource are now injected via providers, so no need to create them here
   */
  public void start() {
    logger.info("Starting EbeanDatabaseManager...");

    checkDatabaseExistence();

    try {
      populatePostgreSQLSchema(dataSource.getConnection(), getCurrentDatabaseVersion());
      logger.info("EbeanDatabaseManager started successfully");
    } catch (SQLException exception) {
      logger.error("Unable to connect to the database " + postgresDatabase);
      logger.error(exception.getMessage());
      stop();
      throw new RuntimeException("Unable to connect to the database");
    }
  }

  /**
   * Forcibly shutdown all threads of the EbeanDatabase.
   */
  public void stop() {
    if (ebeanDatabase != null) {
      ebeanDatabase.shutdown(true, true);
    }
  }
}