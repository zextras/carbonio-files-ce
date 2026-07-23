// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.sdk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Real end-to-end tests for {@link FilesInternalClient}'s blob-upload methods, using a live {@link
 * HttpServer} on an ephemeral loopback port (no mocked I/O) &mdash; the same style as {@link
 * com.zextras.carbonio.files.sdk.streaming.RestStreamingSupportTest}. These exist to prove the
 * {@code Content-Length} fix holds all the way through the public facade API real callers use, not
 * just the lower-level {@code RestStreamingSupport} helper: {@link
 * FilesInternalClient#uploadFile} / {@link FilesInternalClient#uploadFileVersion} must deliver a
 * real {@code Content-Length} header (derived from their {@code length} parameter) instead of
 * chunked transfer-encoding with an unknown length &mdash; the bug that made the server-side {@code
 * blobLength} arrive as {@code -1} (silently bypassing the quota check).
 */
class FilesInternalClientTest {

  private static final int PAYLOAD_SIZE = 500_000;

  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void uploadFileSendsRealContentLengthAndReturnsNodeId() throws Exception {
    byte[] payload = randomBytes(PAYLOAD_SIZE);
    AtomicReference<String> receivedContentLength = new AtomicReference<>();
    AtomicReference<String> receivedTransferEncoding = new AtomicReference<>();
    AtomicReference<byte[]> receivedBody = new AtomicReference<>();
    CountDownLatch requestHandled = new CountDownLatch(1);

    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext(
        "/internal/accounts/user-1/upload",
        exchange -> {
          try {
            receivedContentLength.set(exchange.getRequestHeaders().getFirst("Content-Length"));
            receivedTransferEncoding.set(
                exchange.getRequestHeaders().getFirst("Transfer-Encoding"));
            receivedBody.set(exchange.getRequestBody().readAllBytes());
            byte[] responseBytes =
                "{\"nodeId\":\"node-123\",\"version\":1}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
              os.write(responseBytes);
            }
          } finally {
            exchange.close();
            requestHandled.countDown();
          }
        });
    server.start();

    FilesInternalClient client =
        FilesInternalClient.atURL("http://localhost:" + server.getAddress().getPort());

    String nodeId =
        assertTimeoutPreemptively(
            Duration.ofSeconds(10),
            () ->
                client.uploadFile(
                    "user-1",
                    "LOCAL_ROOT",
                    "report.pdf",
                    "application/pdf",
                    () -> new ByteArrayInputStream(payload),
                    payload.length),
            "uploadFile hung");

    assertTrue(
        requestHandled.await(5, TimeUnit.SECONDS),
        "server handler never completed processing the request");
    assertEquals("node-123", nodeId);
    assertEquals(
        String.valueOf(payload.length),
        receivedContentLength.get(),
        "the server must receive a real Content-Length header equal to the declared length "
            + "(this is the fix: previously the server saw no Content-Length at all, so "
            + "InternalBlobResource/BlobService treated the upload length as -1)");
    assertTrue(
        receivedTransferEncoding.get() == null
            || !receivedTransferEncoding.get().toLowerCase().contains("chunked"),
        "request must not fall back to chunked transfer-encoding when a length is known");
    assertArrayEquals(payload, receivedBody.get());
  }

  @Test
  void uploadFileVersionSendsRealContentLengthAndReturnsVersion() throws Exception {
    byte[] payload = randomBytes(PAYLOAD_SIZE);
    AtomicReference<String> receivedContentLength = new AtomicReference<>();
    AtomicReference<byte[]> receivedBody = new AtomicReference<>();
    CountDownLatch requestHandled = new CountDownLatch(1);

    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext(
        "/internal/accounts/user-1/upload-version",
        exchange -> {
          try {
            receivedContentLength.set(exchange.getRequestHeaders().getFirst("Content-Length"));
            receivedBody.set(exchange.getRequestBody().readAllBytes());
            byte[] responseBytes =
                "{\"nodeId\":\"node-123\",\"version\":2}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
              os.write(responseBytes);
            }
          } finally {
            exchange.close();
            requestHandled.countDown();
          }
        });
    server.start();

    FilesInternalClient client =
        FilesInternalClient.atURL("http://localhost:" + server.getAddress().getPort());

    int version =
        assertTimeoutPreemptively(
            Duration.ofSeconds(10),
            () ->
                client.uploadFileVersion(
                    "user-1",
                    "node-123",
                    "report.pdf",
                    "application/pdf",
                    () -> new ByteArrayInputStream(payload),
                    payload.length,
                    false),
            "uploadFileVersion hung");

    assertTrue(
        requestHandled.await(5, TimeUnit.SECONDS),
        "server handler never completed processing the request");
    assertEquals(2, version);
    assertEquals(
        String.valueOf(payload.length),
        receivedContentLength.get(),
        "the server must receive a real Content-Length header equal to the declared length");
    assertArrayEquals(payload, receivedBody.get());
  }

  private static byte[] randomBytes(int size) {
    byte[] bytes = new byte[size];
    new Random(7).nextBytes(bytes);
    return bytes;
  }
}
