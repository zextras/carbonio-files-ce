// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class GraphQLMethodGuardApiIT extends AbstractFilesIT {

  private static final int ONE_MIB = 1024 * 1024;
  private static final String WEBSOCKET_KEY = Base64.getEncoder().encodeToString(new byte[16]);

  private static String baseUrl() {
    return RestAssured.baseURI + ":" + RestAssured.port;
  }

  private static byte[] oneMibBody() {
    byte[] body = new byte[ONE_MIB];
    Arrays.fill(body, (byte) 'a');
    return body;
  }

  private static String readHead(InputStream in) throws Exception {
    ByteArrayOutputStream head = new ByteArrayOutputStream();
    int b;
    while ((b = in.read()) != -1) {
      head.write(b);
      if (head.toString(StandardCharsets.UTF_8).endsWith("\r\n\r\n")) {
        break;
      }
    }
    return head.toString(StandardCharsets.UTF_8);
  }

  private static String rawRequest(String request) throws Exception {
    try (Socket socket = new Socket("localhost", RestAssured.port)) {
      socket.setSoTimeout(5000);
      OutputStream out = socket.getOutputStream();
      out.write(request.getBytes(StandardCharsets.UTF_8));
      out.flush();
      return readHead(socket.getInputStream());
    }
  }

  @ParameterizedTest
  @CsvSource({
    "PUT,/graphql",
    "PUT,/graphql/",
    "PATCH,/graphql",
    "PATCH,/graphql/",
    "DELETE,/graphql",
    "DELETE,/graphql/"
  })
  void givenBodyCarryingNonPostMethodShouldAnswer405FastWithoutReadingTheBody(
      String method, String path) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(baseUrl() + path))
            .version(HttpClient.Version.HTTP_1_1)
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofByteArray(oneMibBody()))
            .build();

    long start = System.nanoTime();
    HttpResponse<String> response;
    long elapsedMs;
    try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
      response = client.send(request, HttpResponse.BodyHandlers.ofString());
      elapsedMs = (System.nanoTime() - start) / 1_000_000;
    }

    Assertions.assertThat(response.statusCode()).isEqualTo(405);
    Assertions.assertThat(response.headers().firstValue("Allow")).hasValue("POST, OPTIONS");
    Assertions.assertThat(response.body()).isEmpty();
    Assertions.assertThat(elapsedMs).isLessThan(2000);

    Response next = publicGraphql("{ __typename }");
    Assertions.assertThat(next.statusCode()).isEqualTo(200);
    Assertions.assertThat(next.jsonPath().getString("data.__typename")).isEqualTo("Query");
  }

  @ParameterizedTest
  @CsvSource({"GET,/graphql", "GET,/graphql/", "HEAD,/graphql", "HEAD,/graphql/"})
  void givenGetOrHeadShouldAnswer405(String method, String path) {
    Response response = RestAssured.given().request(method, path);

    Assertions.assertThat(response.statusCode()).isEqualTo(405);
    Assertions.assertThat(response.header("Allow")).isEqualTo("POST, OPTIONS");
  }

  @Test
  void givenWebSocketUpgradeShouldAnswer405() throws Exception {
    String head =
        rawRequest(
            "GET /graphql HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Connection: Upgrade\r\n"
                + "Upgrade: websocket\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "Sec-WebSocket-Key: "
                + WEBSOCKET_KEY
                + "\r\n"
                + "Sec-WebSocket-Protocol: graphql-transport-ws\r\n"
                + "\r\n");

    Assertions.assertThat(head).startsWith("HTTP/1.1 405");
    Assertions.assertThat(head.toLowerCase()).contains("allow: post, options");
  }

  @Test
  void givenOptionsShouldStayAnswered200WithAllowHeader() {
    Response response = RestAssured.given().options("/graphql");

    Assertions.assertThat(response.statusCode()).isEqualTo(200);
    Assertions.assertThat(response.header("Allow")).isEqualTo("POST, OPTIONS");
  }

  @Test
  void givenPostShouldStillExecute() {
    Response response = publicGraphql("{ __typename }");

    Assertions.assertThat(response.statusCode()).isEqualTo(200);
    Assertions.assertThat(response.jsonPath().getString("data.__typename")).isEqualTo("Query");
  }

  @Test
  void givenSchemaSdlPathShouldNotBeMatchedByTheGuardAndStayHiddenInProd() {
    Response response = RestAssured.given().get("/graphql/schema.graphql");

    Assertions.assertThat(response.statusCode()).isEqualTo(404);
    Assertions.assertThat(response.header("Allow")).isNull();
  }

  @Test
  void givenPostWithUpgradeHeaderShouldAnswer405() throws Exception {
    String head =
        rawRequest(
            "POST /graphql HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\n"
                + "Connection: Upgrade\r\nContent-Length: 0\r\n\r\n");

    Assertions.assertThat(head).startsWith("HTTP/1.1 405");
  }

  @Test
  void givenWebSocketUpgradeOnTrailingSlashPathShouldAnswer405() throws Exception {
    String head =
        rawRequest(
            "GET /graphql/ HTTP/1.1\r\nHost: localhost\r\nConnection: Upgrade\r\n"
                + "Upgrade: websocket\r\nSec-WebSocket-Version: 13\r\n"
                + "Sec-WebSocket-Key: "
                + WEBSOCKET_KEY
                + "\r\n"
                + "Sec-WebSocket-Protocol: graphql-transport-ws\r\n\r\n");

    Assertions.assertThat(head).startsWith("HTTP/1.1 405");
  }

  @Test
  void givenLargePutOnKeepAliveConnectionTheSameConnectionShouldStayUsable() throws Exception {
    int length = 16 * ONE_MIB;
    String payload = "{\"query\":\"{ __typename }\"}";
    try (Socket socket = new Socket("localhost", RestAssured.port)) {
      socket.setSoTimeout(10000);
      Thread watchdog =
          Thread.startVirtualThread(
              () -> {
                try {
                  Thread.sleep(30000);
                  socket.close();
                } catch (Exception ignored) {
                }
              });
      OutputStream out = socket.getOutputStream();
      InputStream in = socket.getInputStream();
      out.write(
          ("PUT /graphql HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n"
                  + "Content-Length: "
                  + length
                  + "\r\n\r\n")
              .getBytes(StandardCharsets.UTF_8));
      byte[] chunk = oneMibBody();
      for (int i = 0; i < 16; i++) {
        out.write(chunk);
      }
      out.flush();
      String first = readHead(in);
      Assertions.assertThat(first).startsWith("HTTP/1.1 405");

      out.write(
          ("POST /graphql HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n"
                  + "Content-Length: "
                  + payload.length()
                  + "\r\n\r\n"
                  + payload)
              .getBytes(StandardCharsets.UTF_8));
      out.flush();
      String second = readHead(in);
      Assertions.assertThat(second).startsWith("HTTP/1.1 200");
      watchdog.interrupt();
    }
  }
}
