// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import java.nio.charset.StandardCharsets;
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
        "/graphql/",
        "{\"query\":\"mutation($i:[String!],$d:ID!){ copyNodes(node_ids:$i, destination_id:$d){"
            + " id } }\",\"variables\":{\"i\":[\""
            + source
            + "\"],\"d\":\""
            + destination
            + "\"}}",
        COOKIE);

    long deadline = System.currentTimeMillis() + 20_000;
    int copied = copiedChildren(destination, COOKIE);
    while (copied < FILES && System.currentTimeMillis() < deadline) {
      Thread.sleep(250);
      copied = copiedChildren(destination, COOKIE);
    }
    Assertions.assertThat(copied).isEqualTo(FILES);
  }
}
