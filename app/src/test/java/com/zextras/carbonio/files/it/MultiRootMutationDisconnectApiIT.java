// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
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
  private static final int FILES = 10;

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService()
        .registerToken(TOKEN, "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa8");
  }

  @Test
  void givenTheConnectionClosesDuringTheFirstRootFieldThenTheSecondRootFieldStillRuns()
      throws Exception {
    String source = seedFolder("multi-root-source", LOCAL_ROOT, COOKIE);
    for (int i = 0; i < FILES; i++) {
      seedFile("f" + i + ".txt", source, ("content " + i).getBytes(StandardCharsets.UTF_8), COOKIE);
    }
    String destination = seedFolder("multi-root-destination", LOCAL_ROOT, COOKIE);
    String created = "created-after-" + UUID.randomUUID();
    FilesStackTestResource.getStoragesService().setCopyDelayMillis(200);

    sendAndDisconnect(
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
        COOKIE);

    Assertions.assertThat(copiedChildren(destination, COOKIE))
        .as("copy in flight")
        .isLessThan(FILES);
    Assertions.assertThat(foldersNamed(created)).as("second root field not yet run").isZero();

    long deadline = System.currentTimeMillis() + 30_000;
    int copied = copiedChildren(destination, COOKIE);
    int folders = foldersNamed(created);
    while ((copied < FILES || folders < 1) && System.currentTimeMillis() < deadline) {
      Thread.sleep(250);
      copied = copiedChildren(destination, COOKIE);
      folders = foldersNamed(created);
    }
    Assertions.assertThat(copied).as("copied files").isEqualTo(FILES);
    Assertions.assertThat(folders).as("folders created by the second root field").isEqualTo(1);
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
