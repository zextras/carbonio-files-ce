// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.RestAssured;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class GraphQLMethodGuardApiIT extends AbstractFilesIT {

  private static final int ONE_MIB = 1024 * 1024;
  private static final String WEBSOCKET_KEY = Base64.getEncoder().encodeToString(new byte[16]);

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
  void givenLargePutOnKeepAliveConnectionTheSameConnectionShouldStayUsable() {
    int length = 16 * ONE_MIB;
    String payload = "{\"query\":\"{ __typename }\"}";
    assertTimeoutPreemptively(
        Duration.ofSeconds(30),
        () -> {
          try (Socket socket = new Socket("localhost", RestAssured.port)) {
            socket.setSoTimeout(10000);
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
            Assertions.assertThat(first.toLowerCase()).contains("allow: post, options");

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
          }
        });
  }
}
