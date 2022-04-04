// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.zextras.carbonio.files.Files;
import com.zextras.carbonio.files.clients.ServiceDiscoverHttpClient;
import com.zextras.carbonio.files.config.FilesConfig;
import com.zextras.carbonio.files.dal.dao.ebean.DbInfo;
import com.zextras.carbonio.files.dal.dao.ebean.FileVersion;
import com.zextras.carbonio.files.dal.dao.ebean.FileVersionPK;
import com.zextras.carbonio.files.dal.dao.ebean.Link;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeCustomAttributes;
import com.zextras.carbonio.files.dal.dao.ebean.NodeCustomAttributesPK;
import com.zextras.carbonio.files.dal.dao.ebean.Share;
import com.zextras.carbonio.files.dal.dao.ebean.SharePK;
import com.zextras.carbonio.files.dal.dao.ebean.Tombstone;
import com.zextras.carbonio.files.dal.dao.ebean.TombstonePK;
import com.zextras.carbonio.files.dal.dao.ebean.TrashedNode;
import io.ebean.Database;
import io.ebean.DatabaseFactory;
import io.ebean.config.DatabaseConfig;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;
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

  private static final Logger         logger = LoggerFactory.getLogger(EbeanDatabaseManager.class);
  private final        List<Class<?>> entityList;
  private final        String         jdbcPostgresUrl;
  private final        String         postgresDatabase;
  private final        String         postgresUser;
  private final        String         postgresPassword;
  private              Database       ebeanDatabase;

  @Inject
  public EbeanDatabaseManager(FilesConfig filesConfig) {
    Properties config = filesConfig.getProperties();

    postgresDatabase = ServiceDiscoverHttpClient
      .defaultURL("carbonio-files")
      .getConfig("db-name")
      .getOrElse("carbonio-files-db");

    postgresUser = ServiceDiscoverHttpClient
      .defaultURL("carbonio-files")
      .getConfig("db-username")
      .getOrElse("carbonio-files-db");

    postgresPassword = ServiceDiscoverHttpClient
      .defaultURL("carbonio-files")
      .getConfig("db-password")
      .getOrElse("");

    jdbcPostgresUrl = "jdbc:postgresql://"
      + config.getProperty(Files.Config.Database.URL, "127.78.0.2")
      + ":"
      + config.getProperty(Files.Config.Database.PORT, "20000")
      + "/"
      + postgresDatabase;

    entityList = new ArrayList<>();
    entityList.add(DbInfo.class);
    entityList.add(Node.class);
    entityList.add(NodeCustomAttributesPK.class);
    entityList.add(NodeCustomAttributes.class);
    entityList.add(FileVersionPK.class);
    entityList.add(FileVersion.class);
    entityList.add(SharePK.class);
    entityList.add(Share.class);
    entityList.add(Link.class);
    entityList.add(TombstonePK.class);
    entityList.add(Tombstone.class);
    entityList.add(TrashedNode.class);
  }

  private void createSchemaForPostgreSQL(Connection connection) {
    ClassLoader classLoader = getClass().getClassLoader();

    String data = new BufferedReader(new InputStreamReader(
      Objects.requireNonNull(classLoader.getResourceAsStream("sql/postgresql_0.sql")),
      StandardCharsets.UTF_8
    )).lines().collect(Collectors.joining("\n"));

    try {
      connection.createStatement().execute(data);
    } catch (SQLException e) {
    }
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
   *   <li>Create the {@link DatabaseConfig} with all the necessary configuration settings and
   *       especially set as <strong>default</strong> the database</li>
   *   <li>Create the {@link Database} instance</li>
   *   <li>If the database does not have the schema then the system creates it (this is temporary)</li>
   * </ul>
   */
  public void start() {

    if (ebeanDatabase != null) {
      logger.info("Files database already up and running");
      return;
    }

    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setURL(jdbcPostgresUrl);
    dataSource.setDatabaseName(postgresDatabase);
    dataSource.setUser(postgresUser);
    dataSource.setPassword(postgresPassword);

    DatabaseConfig serverConfig = new DatabaseConfig();
    serverConfig.setName("carbonio-files-postgres");
    serverConfig.setDataSource(dataSource);
    serverConfig.setDefaultServer(true);
    serverConfig.addAll(entityList);

    ebeanDatabase = DatabaseFactory.createWithContextClassLoader(
      serverConfig,
      this.getClass().getClassLoader()
    );

    if (ebeanDatabase != null) {
      boolean isSchemaCreated = ebeanDatabase
        .sqlQuery("SELECT schema_name FROM information_schema.schemata WHERE schema_name = 'files'")
        .findOneOrEmpty()
        .isPresent();

      if (!isSchemaCreated) {
        try {
          createSchemaForPostgreSQL(dataSource.getConnection());
        } catch (SQLException e) {
          logger.info("Could not initialize Files database" + e);
        }
      }
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
