// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class LockTimeoutApiIT extends AbstractFilesIT {

  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String REQUESTER_COOKIE = "ZM_AUTH_TOKEN=fake-token";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", REQUESTER_ID);
  }

  @Test
  void givenAWriterQueuedBehindALongRowLockTheFolderShouldBeFreedAfterTheLockTimeout()
      throws Exception {
    String parentId = seedFolder("parent", LOCAL_ROOT, REQUESTER_COOKIE);
    String fileId =
        seedFile("x.txt", parentId, "content".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);

    Response rename;
    Response create;
    try (Connection uploading = jdbcConnection()) {
      uploading.setAutoCommit(false);
      try (PreparedStatement lock =
          uploading.prepareStatement("SELECT node_id FROM node WHERE node_id = ? FOR UPDATE")) {
        lock.setString(1, fileId);
        lock.execute();
      }

      CompletableFuture<Response> renameFuture =
          CompletableFuture.supplyAsync(
              () ->
                  graphql(
                      GraphqlCommandBuilder.aMutationBuilder("updateNode")
                          .withString("node_id", fileId)
                          .withString("name", "renamed")
                          .withWantedResultFormat("{ id }")
                          .build(),
                      REQUESTER_COOKIE));
      awaitBlockedBy(uploading);

      try {
        rename = renameFuture.get(30, TimeUnit.SECONDS);
      } catch (TimeoutException e) {
        throw new AssertionError(
            "the rename kept waiting on the held row lock: lock_timeout is not bounding it", e);
      }

      create =
          graphql(
              GraphqlCommandBuilder.aMutationBuilder("createFolder")
                  .withString("destination_id", parentId)
                  .withString("name", "new")
                  .withWantedResultFormat("{ id }")
                  .build(),
              REQUESTER_COOKIE);
    }

    Assertions.assertThat(nodeExists(fileId, REQUESTER_COOKIE)).isTrue();
    Assertions.assertThat(childIds(parentId, REQUESTER_COOKIE)).hasSize(2);
  }
}
