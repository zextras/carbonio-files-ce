// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

class LegacyDbInfoBaselineCustomizerTest {

  private static final String LOCATION = "db/migration";
  private static final String DROP_DBINFO_VERSION = "10";

  private static PostgreSQLContainer<?> postgres;

  @BeforeAll
  static void startPostgres() {
    postgres = new PostgreSQLContainer<>("postgres:16");
    postgres.start();
  }

  @AfterAll
  static void stopPostgres() {
    postgres.stop();
  }

  @Test
  void legacy25_12DatabaseIsBaselinedAtDbInfoVersionAndMigratedToLatest() throws Exception {
    String database = newDatabase();
    seedLegacy(database, 9);

    migrate(database);

    assertThat(historyRows(database)).startsWith("BASELINE:9", "SQL:10", "SQL:11");
    assertThat(lastHistoryVersion(database)).isEqualTo(latestMigrationVersion());
    assertThat(tableExists(database, "db_info")).isFalse();
    assertThat(tableExists(database, "node")).isTrue();
  }

  @Test
  void legacy25_6DatabaseIsBaselinedAtDbInfoVersionAndMigratedToLatest() throws Exception {
    String database = newDatabase();
    seedLegacy(database, 8);

    migrate(database);

    assertThat(historyRows(database)).startsWith("BASELINE:8", "SQL:9", "SQL:10");
    assertThat(lastHistoryVersion(database)).isEqualTo(latestMigrationVersion());
    assertThat(tableExists(database, "db_info")).isFalse();
  }

  @Test
  void freshDatabaseAppliesEveryMigrationWithoutBaselineRow() throws Exception {
    String database = newDatabase();

    migrate(database);

    List<String> rows = historyRows(database);
    assertThat(rows)
        .startsWith("SQL:1", "SQL:2")
        .noneMatch(row -> row.startsWith("BASELINE"))
        .hasSize(Integer.parseInt(latestMigrationVersion()));
    assertThat(tableExists(database, "db_info")).isFalse();
  }

  @Test
  void databaseWithFlywayHistoryAndNoDbInfoIsLeftUntouched() throws Exception {
    String database = newDatabase();
    seedLegacy(database, 9);
    Flyway.configure()
        .dataSource(url(database), "test", "test")
        .locations("classpath:" + LOCATION)
        .baselineOnMigrate(true)
        .baselineVersion("9")
        .target(DROP_DBINFO_VERSION)
        .load()
        .migrate();
    List<String> before = historyRows(database);
    assertThat(before).startsWith("BASELINE:9").contains("SQL:10");

    migrate(database);

    List<String> after = historyRows(database);
    assertThat(after)
        .startsWith(before.toArray(String[]::new))
        .hasSize(before.size() + Integer.parseInt(latestMigrationVersion()) - 10);
    assertThat(lastHistoryVersion(database)).isEqualTo(latestMigrationVersion());
  }

  @Test
  void databaseWithLegacyDbInfoAndFlywayHistoryDoesNotEnableBaselineOnMigrate() throws Exception {
    String database = newDatabase();
    migrate(database);
    try (Connection connection = connect(database);
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE TABLE db_info (version integer PRIMARY KEY)");
      statement.execute("INSERT INTO db_info VALUES (3)");
    }
    FluentConfiguration configuration = configuration(database);

    new LegacyDbInfoBaselineCustomizer().customize(configuration);

    assertThat(configuration.isBaselineOnMigrate()).isFalse();
    assertThat(configuration.getBaselineVersion().getVersion()).isEqualTo("1");
  }

  @Test
  void nonEmptySchemaWithoutHistoryAndWithoutDbInfoFailsFastAndIsLeftUntouched() throws Exception {
    String database = newDatabase();
    try (Connection connection = connect(database);
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE TABLE stray (id integer)");
    }

    assertThatThrownBy(() -> migrate(database))
        .isInstanceOf(FlywayException.class)
        .hasMessageContaining("non-empty schema")
        .hasMessageContaining("no schema history table");

    try (Connection connection = connect(database);
        Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT table_name FROM information_schema.tables"
                    + " WHERE table_schema = 'public'")) {
      List<String> tables = new ArrayList<>();
      while (result.next()) {
        tables.add(result.getString(1));
      }
      assertThat(tables).containsExactly("stray");
    }
  }

  @Test
  void emptyDbInfoTableDoesNotEnableBaselineOnMigrate() throws Exception {
    String database = newDatabase();
    try (Connection connection = connect(database);
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE TABLE db_info (version integer PRIMARY KEY)");
    }
    FluentConfiguration configuration = configuration(database);

    new LegacyDbInfoBaselineCustomizer().customize(configuration);

    assertThat(configuration.isBaselineOnMigrate()).isFalse();
  }

  @Test
  void unreachableDatabaseFailsWithExplicitMessage() {
    FluentConfiguration configuration =
        Flyway.configure().dataSource("jdbc:postgresql://127.0.0.1:1/none", "test", "test");

    LegacyDbInfoBaselineCustomizer customizer = new LegacyDbInfoBaselineCustomizer();

    assertThatThrownBy(() -> customizer.customize(configuration))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("legacy db_info");
  }

  private static void migrate(String database) {
    FluentConfiguration configuration = configuration(database);
    new LegacyDbInfoBaselineCustomizer().customize(configuration);
    configuration.load().migrate();
  }

  private static FluentConfiguration configuration(String database) {
    return Flyway.configure()
        .dataSource(url(database), "test", "test")
        .locations("classpath:" + LOCATION)
        .ignoreMigrationPatterns(new String[0]);
  }

  private static void seedLegacy(String database, int version) throws Exception {
    try (Connection connection = connect(database);
        Statement statement = connection.createStatement()) {
      for (int n = 1; n <= version; n++) {
        statement.execute(resource(LOCATION + "/" + migrationFile(n)));
      }
    }
  }

  private static String migrationFile(int version) throws IOException {
    for (String name : migrationFiles()) {
      if (name.startsWith("V" + version + "__")) {
        return name;
      }
    }
    throw new IllegalStateException("No migration V" + version);
  }

  private static String latestMigrationVersion() throws IOException {
    int latest = 0;
    Pattern pattern = Pattern.compile("V(\\d+)__.*\\.sql");
    for (String name : migrationFiles()) {
      Matcher matcher = pattern.matcher(name);
      if (matcher.matches()) {
        latest = Math.max(latest, Integer.parseInt(matcher.group(1)));
      }
    }
    return String.valueOf(latest);
  }

  private static List<String> migrationFiles() throws IOException {
    List<String> names = new ArrayList<>();
    try (var stream =
        Files.list(
            Path.of(
                LegacyDbInfoBaselineCustomizerTest.class
                    .getClassLoader()
                    .getResource(LOCATION)
                    .getPath()))) {
      stream.forEach(path -> names.add(path.getFileName().toString()));
    }
    return names;
  }

  private static String resource(String path) throws IOException {
    try (InputStream in =
        LegacyDbInfoBaselineCustomizerTest.class.getClassLoader().getResourceAsStream(path)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static String newDatabase() throws SQLException {
    String name = "db_" + UUID.randomUUID().toString().replace("-", "");
    try (Connection connection = connect(postgres.getDatabaseName());
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE DATABASE " + name);
    }
    return name;
  }

  private static List<String> historyRows(String database) throws SQLException {
    List<String> rows = new ArrayList<>();
    try (Connection connection = connect(database);
        Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT type, version FROM flyway_schema_history WHERE version IS NOT NULL"
                    + " ORDER BY installed_rank")) {
      while (result.next()) {
        rows.add(result.getString(1) + ":" + result.getString(2));
      }
    }
    return rows;
  }

  private static String lastHistoryVersion(String database) throws SQLException {
    try (Connection connection = connect(database);
        Statement statement = connection.createStatement();
        ResultSet result =
            statement.executeQuery(
                "SELECT version FROM flyway_schema_history WHERE version IS NOT NULL"
                    + " ORDER BY installed_rank DESC LIMIT 1")) {
      result.next();
      return result.getString(1);
    }
  }

  private static boolean tableExists(String database, String table) throws SQLException {
    try (Connection connection = connect(database);
        Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery("SELECT to_regclass('public." + table + "')")) {
      result.next();
      return result.getString(1) != null;
    }
  }

  private static String url(String database) {
    return String.format(
        "jdbc:postgresql://%s:%d/%s", postgres.getHost(), postgres.getFirstMappedPort(), database);
  }

  private static Connection connect(String database) throws SQLException {
    return DriverManager.getConnection(url(database), "test", "test");
  }
}
