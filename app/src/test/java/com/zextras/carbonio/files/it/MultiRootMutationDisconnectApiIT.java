// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import com.zextras.carbonio.files.it.support.MockStoragesService;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MultiRootMutationDisconnectApiIT extends AbstractFilesIT {

  private static final String TOKEN = "multi-root-disconnect-token";
  private static final String COOKIE = "ZM_AUTH_TOKEN=" + TOKEN;
  private static final int FILES = 3;

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService()
        .registerToken(TOKEN, "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa8");
  }

  @Test
  void givenTheConnectionClosesDuringTheFirstRootFieldThenBothRootFieldsStillRun()
      throws Exception {
    String source = seedFolder("multi-root-source", LOCAL_ROOT, COOKIE);
    for (int i = 0; i < FILES; i++) {
      seedFile("f" + i + ".txt", source, ("content " + i).getBytes(StandardCharsets.UTF_8), COOKIE);
    }
    String destination = seedFolder("multi-root-destination", LOCAL_ROOT, COOKIE);
    String created = "created-after-" + UUID.randomUUID();
    MockStoragesService storages = FilesStackTestResource.getStoragesService();
    storages.holdCopies();

    try (Socket client =
        send(
            "/graphql",
            "{\"query\":\"mutation($i:[String!],$d:ID!){ copy: copyNodes(node_ids:$i,"
                + " destination_id:$d){ id } later: createFolder(destination_id:\\\"LOCAL_ROOT\\\","
                + " name:\\\""
                + created
                + "\\\"){ id } }\",\"variables\":{\"i\":[\""
                + source
                + "\"],\"d\":\""
                + destination
                + "\"}}",
            COOKIE)) {
      storages.awaitHeldCopy();
    }
    Assertions.assertThat(foldersNamed(created)).as("second root field not yet run").isZero();
    storages.releaseCopies();

    eventually(
        "both root fields completed",
        () -> copiedChildren(destination, COOKIE) == FILES && foldersNamed(created) == 1);
  }

  private static int foldersNamed(String name) throws SQLException {
    try (Connection connection = jdbcConnection();
        PreparedStatement statement =
            connection.prepareStatement("SELECT COUNT(*) FROM node WHERE name = ?")) {
      statement.setString(1, name);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getInt(1);
      }
    }
  }
}
