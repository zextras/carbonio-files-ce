// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import com.zextras.carbonio.files.it.support.MockStoragesService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * copyNodes, cloneVersion and transferOwnership answer with a 200 head and one space per interval
 * (100 ms in the ITs) while they run longer than the interval, then the usual JSON result. The fast
 * path is covered without real time by {@code HeartbeatResponseWriterTest}.
 */
class GraphQLHeartbeatApiIT extends AbstractFilesIT {

  private static final String TOKEN = "graphql-heartbeat-token";
  private static final String COOKIE = "ZM_AUTH_TOKEN=" + TOKEN;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService()
        .registerToken(TOKEN, "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa9");
  }

  @AfterEach
  void releaseStorages() {
    MockStoragesService storages = FilesStackTestResource.getStoragesService();
    storages.setCopyFails(false);
    storages.releaseCopies();
  }

  @Test
  void givenACopySlowerThanTheIntervalThenTheHeadArrivesAfterOneIntervalAndTheResultAtTheEnd()
      throws Exception {
    String file = seedFile("slow.txt", LOCAL_ROOT, bytes("slow"), COOKIE);
    String destination = seedFolder("heartbeat-destination", LOCAL_ROOT, COOKIE);
    MockStoragesService storages = FilesStackTestResource.getStoragesService();
    storages.holdCopies();

    try (Socket client = send("/graphql", copyNodes(file, destination), COOKIE)) {
      client.setSoTimeout(20_000);
      storages.awaitHeldCopy();
      InputStream in = client.getInputStream();
      Map<String, String> head = readHead(in);
      Assertions.assertThat(head.get(":status")).startsWith("HTTP/1.1 200");
      Assertions.assertThat(head)
          .containsEntry("transfer-encoding", "chunked")
          .containsEntry("x-accel-buffering", "no");
      String firstChunk = readChunk(in);
      Assertions.assertThat(firstChunk).isNotEmpty().isBlank();

      storages.releaseCopies();
      JsonNode result = MAPPER.readTree(readChunks(in, firstChunk).strip());

      Assertions.assertThat(result.path("errors").isMissingNode()).isTrue();
      Assertions.assertThat(result.path("data").path("copyNodes").path(0).path("id").asText())
          .isNotEmpty();
    }
    Assertions.assertThat(childIds(destination, COOKIE)).hasSize(1);
  }

  @Test
  void givenACopyFailingAfterTheHeadThenTheErrorIsInTheBody() throws Exception {
    String file = seedFile("failing.txt", LOCAL_ROOT, bytes("failing"), COOKIE);
    String destination = seedFolder("heartbeat-failing-destination", LOCAL_ROOT, COOKIE);
    MockStoragesService storages = FilesStackTestResource.getStoragesService();
    storages.setCopyFails(true);
    storages.holdCopies();

    try (Socket client = send("/graphql", copyNodes(file, destination), COOKIE)) {
      client.setSoTimeout(20_000);
      storages.awaitHeldCopy();
      InputStream in = client.getInputStream();
      Assertions.assertThat(readHead(in).get(":status")).startsWith("HTTP/1.1 200");
      String firstChunk = readChunk(in);

      storages.releaseCopies();
      JsonNode result = MAPPER.readTree(readChunks(in, firstChunk).strip());

      Assertions.assertThat(
              result.path("errors").path(0).path("extensions").path("errorCode").asText())
          .isEqualTo("NODE_COPY_ERROR");
    }
    Assertions.assertThat(childIds(destination, COOKIE)).isEmpty();
  }

  private static String copyNodes(String file, String destination) {
    return "{\"query\":\"mutation($i:[String!],$d:ID!){ copyNodes(node_ids:$i, destination_id:$d)"
        + "{ id } }\",\"variables\":{\"i\":[\""
        + file
        + "\"],\"d\":\""
        + destination
        + "\"}}";
  }

  private static byte[] bytes(String content) {
    return content.getBytes(StandardCharsets.UTF_8);
  }

  private static Map<String, String> readHead(InputStream in) throws IOException {
    Map<String, String> head = new LinkedHashMap<>();
    head.put(":status", readLine(in));
    for (String line = readLine(in); !line.isEmpty(); line = readLine(in)) {
      int colon = line.indexOf(':');
      head.put(line.substring(0, colon).trim().toLowerCase(), line.substring(colon + 1).trim());
    }
    return head;
  }

  private static String readChunks(InputStream in, String first) throws IOException {
    StringBuilder body = new StringBuilder(first);
    for (String chunk = readChunk(in); chunk != null; chunk = readChunk(in)) {
      body.append(chunk);
    }
    return body.toString();
  }

  private static String readChunk(InputStream in) throws IOException {
    int size = Integer.parseInt(readLine(in).trim(), 16);
    if (size == 0) {
      readLine(in);
      return null;
    }
    byte[] chunk = in.readNBytes(size);
    readLine(in);
    return new String(chunk, StandardCharsets.UTF_8);
  }

  private static String readLine(InputStream in) throws IOException {
    ByteArrayOutputStream line = new ByteArrayOutputStream();
    for (int b = in.read(); b != -1 && b != '\n'; b = in.read()) {
      if (b != '\r') {
        line.write(b);
      }
    }
    return line.toString(StandardCharsets.UTF_8);
  }
}
