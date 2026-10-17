// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.errors;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonParseException;
import io.smallrye.graphql.spi.config.Config;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

class HiddenErrorMessagesTest {

  private static Config config;

  @BeforeAll
  static void loadTheHideList() throws Exception {
    Properties properties = new Properties();
    try (InputStream in =
        HiddenErrorMessagesTest.class.getResourceAsStream("/application.properties")) {
      properties.load(in);
    }
    List<String> hidden =
        List.of(
            properties
                .getProperty("quarkus.smallrye-graphql.hide-checked-exception-message")
                .split(","));
    config =
        new Config() {
          @Override
          public String getName() {
            return "application.properties";
          }

          @Override
          public Optional<List<String>> getHideErrorMessageList() {
            return Optional.of(hidden);
          }
        };
  }

  @Test
  void theCausesOfAFailedBatchAreReportedAsSystemError() {
    assertThat(
            List.<Throwable>of(
                new SQLException("select"),
                new PSQLException("relation does not exist", PSQLState.UNDEFINED_TABLE),
                new ConnectException("10.0.0.5:5432"),
                new SocketTimeoutException("10.0.0.5:20002"),
                new UnknownHostException("storages.local"),
                new JsonParseException(null, "/opt/zextras"),
                new InterruptedException(),
                new ExecutionException(new SQLException("select")),
                new TimeoutException("10.0.0.5:20002"),
                new StackOverflowError(),
                new NoClassDefFoundError("com/zextras/carbonio/files/Node"),
                new OutOfMemoryError("Java heap space")))
        .allMatch(config::shouldHide);
  }

  @Test
  void theBusinessErrorsKeepTheirMessage() {
    assertThat(config.shouldHide(FilesGraphQLErrors.nodeNotFound("node"))).isFalse();
    assertThat(config.shouldHide(FilesGraphQLErrors.accountNotFound("user"))).isFalse();
    assertThat(config.shouldHide(new InputValidationException("invalid"))).isFalse();
  }
}
