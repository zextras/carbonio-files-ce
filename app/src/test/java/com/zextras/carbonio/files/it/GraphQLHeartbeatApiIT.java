// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.api.utilities.GraphqlCommandBuilder;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * copyNodes, cloneVersion and transferOwnership go through the heartbeat writer: one that ends
 * within the interval answers exactly like any other GraphQL request. The heartbeat itself (head,
 * spaces, result or error after the head) is covered without real time by {@code
 * HeartbeatResponseWriterTest}.
 */
class GraphQLHeartbeatApiIT extends AbstractFilesIT {

  private static final String TOKEN = "graphql-heartbeat-token";
  private static final String COOKIE = "ZM_AUTH_TOKEN=" + TOKEN;

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService()
        .registerToken(TOKEN, "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa9");
  }

  @Test
  void givenACopyEndingWithinTheIntervalThenTheResponseIsTheUsualOne() {
    String file = seedFile("fast.txt", LOCAL_ROOT, "fast".getBytes(StandardCharsets.UTF_8), COOKIE);
    String destination = seedFolder("heartbeat-fast-destination", LOCAL_ROOT, COOKIE);

    Response copy =
        graphql(
            GraphqlCommandBuilder.aMutationBuilder("copyNodes")
                .withListOfStrings("node_ids", new String[] {file})
                .withString("destination_id", destination)
                .withWantedResultFormat("{ id }")
                .build(),
            COOKIE);
    Response query =
        graphql(
            GraphqlCommandBuilder.aQueryBuilder("getNode")
                .withString("node_id", destination)
                .withWantedResultFormat("{ id }")
                .build(),
            COOKIE);

    Assertions.assertThat(copy.getStatusCode()).isEqualTo(200);
    Assertions.assertThat(copy.getHeader("Transfer-Encoding")).isNull();
    Assertions.assertThat(copy.getHeader("X-Accel-Buffering")).isNull();
    Assertions.assertThat(copy.getHeader("Content-Length")).isNotNull();
    Assertions.assertThat(copy.getContentType()).isEqualTo(query.getContentType());
    Assertions.assertThat(copy.jsonPath().getString("data.copyNodes[0].id")).isNotEmpty();
  }
}
