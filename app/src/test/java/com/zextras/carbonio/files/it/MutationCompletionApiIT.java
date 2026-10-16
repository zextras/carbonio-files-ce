// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.RestAssured;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A mutation runs to completion even when the HTTP connection closes while it is executing (client
 * gone, proxy timeout), as on the legacy stack.
 */
class MutationCompletionApiIT extends AbstractFilesIT {

  private static final String TOKEN = "mutation-completion-token";
  private static final String COOKIE = "ZM_AUTH_TOKEN=" + TOKEN;
  private static final int FILES = 12;

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService()
        .registerToken(TOKEN, "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa7");
  }

  @Test
  void givenTheConnectionClosesDuringCopyNodesThenEveryChildIsStillCopied() throws Exception {
    String source = seedFolder("completion-source", LOCAL_ROOT, COOKIE);
    for (int i = 0; i < FILES; i++) {
      seedFile("f" + i + ".txt", source, ("content " + i).getBytes(StandardCharsets.UTF_8), COOKIE);
    }
    String destination = seedFolder("completion-destination", LOCAL_ROOT, COOKIE);
    FilesStackTestResource.getStoragesService().setCopyDelayMillis(150);

    sendAndDisconnect(
        "{\"query\":\"mutation($i:[String!],$d:ID!){ copyNodes(node_ids:$i, destination_id:$d){"
            + " id } }\",\"variables\":{\"i\":[\""
            + source
            + "\"],\"d\":\""
            + destination
            + "\"}}");

    long deadline = System.currentTimeMillis() + 20_000;
    int copied = copiedChildren(destination);
    while (copied < FILES && System.currentTimeMillis() < deadline) {
      Thread.sleep(250);
      copied = copiedChildren(destination);
    }
    Assertions.assertThat(copied).isEqualTo(FILES);
  }

  private static void sendAndDisconnect(String body) throws Exception {
    byte[] payload = body.getBytes(StandardCharsets.UTF_8);
    String head =
        "POST /graphql/ HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\nCookie: "
            + COOKIE
            + "\r\nContent-Length: "
            + payload.length
            + "\r\n\r\n";
    try (Socket socket = new Socket("localhost", RestAssured.port)) {
      OutputStream out = socket.getOutputStream();
      out.write(head.getBytes(StandardCharsets.UTF_8));
      out.write(payload);
      out.flush();
      Thread.sleep(300);
    }
  }

  private static int copiedChildren(String destination) {
    List<String> copies = childIds(destination);
    return copies.isEmpty() ? 0 : childIds(copies.get(0)).size();
  }

  private static List<String> childIds(String folderId) {
    return graphql(
            "query { getNode(node_id: \\\""
                + folderId
                + "\\\") { ... on Folder { children(limit: 50, sort: NAME_ASC) { nodes { id } } } }"
                + " }",
            COOKIE)
        .jsonPath()
        .getList("data.getNode.children.nodes.id", String.class);
  }
}
