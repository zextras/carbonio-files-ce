// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.sdk.streaming;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Real end-to-end tests for {@link RestStreamingSupport}, using a live {@link HttpServer} on an
 * ephemeral loopback port (no mocked I/O). Ported from {@code carbonio-quarkus-extensions}' {@code
 * RestStreamingSupportTest}, repackaged, dropping the {@code uploadStream} (multipart) cases: this
 * module only ports {@link RestStreamingSupport#downloadStream} and {@link
 * RestStreamingSupport#uploadStreamRaw}.
 *
 * <p>Every test drives a real request through a real socket and asserts completion within a bounded
 * time (via {@link org.junit.jupiter.api.Assertions#assertTimeoutPreemptively}), so a regression
 * back to a hanging publisher fails the test instead of hanging the build.
 */
class RestStreamingSupportTest {

  /**
   * ~1MB: large enough that a full-buffering (non-streaming) implementation would still "work" by
   * accident, but small enough to keep the test fast. Streaming is instead verified by asserting
   * the exact byte content/length the server received matches what was sent.
   */
  private static final int PAYLOAD_SIZE = 1_000_000;

  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void http1ClientUsesHttp11() {
    HttpClient client = RestStreamingSupport.http1Client();

    assertEquals(HttpClient.Version.HTTP_1_1, client.version());
  }

  @Test
  void downloadStreamYieldsExactBytesServedByServer() throws Exception {
    byte[] payload = randomBytes(PAYLOAD_SIZE);

    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext(
        "/download",
        exchange -> {
          exchange.sendResponseHeaders(200, payload.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(payload);
          } finally {
            exchange.close();
          }
        });
    server.start();

    HttpClient client = RestStreamingSupport.http1Client();
    URI uri = URI.create("http://localhost:" + server.getAddress().getPort() + "/download");

    byte[] received =
        assertTimeoutPreemptively(
            Duration.ofSeconds(10),
            () -> {
              try (InputStream in =
                  RestStreamingSupport.downloadStream(
                      client, uri, Map.of(), Duration.ofSeconds(10))) {
                return in.readAllBytes();
              }
            },
            "downloadStream hung while reading the response body");

    assertArrayEquals(payload, received);
  }

  @Test
  void uploadStreamRawSendsExactBodyBytesWithoutHanging() throws Exception {
    byte[] payload = randomBytes(PAYLOAD_SIZE);
    AtomicReference<byte[]> receivedBody = new AtomicReference<>();
    CountDownLatch requestHandled = new CountDownLatch(1);

    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext(
        "/upload-raw",
        exchange -> {
          try {
            receivedBody.set(exchange.getRequestBody().readAllBytes());
            exchange.sendResponseHeaders(200, -1);
          } finally {
            exchange.close();
            requestHandled.countDown();
          }
        });
    server.start();

    HttpClient client = RestStreamingSupport.http1Client();
    URI uri = URI.create("http://localhost:" + server.getAddress().getPort() + "/upload-raw");

    assertTimeoutPreemptively(
        Duration.ofSeconds(10),
        () -> {
          var response =
              RestStreamingSupport.uploadStreamRaw(
                  client,
                  uri,
                  Map.of(),
                  "application/octet-stream",
                  () -> new ByteArrayInputStream(payload),
                  payload.length,
                  Duration.ofSeconds(10));
          assertEquals(200, response.statusCode());
        },
        "uploadStreamRaw hung: the body was never fully sent/acknowledged");

    assertTrue(
        requestHandled.await(5, TimeUnit.SECONDS),
        "server handler never completed processing the request");
    assertArrayEquals(
        payload,
        receivedBody.get(),
        "server-received raw body bytes do not match the uploaded payload (length sent="
            + payload.length
            + ", length received="
            + (receivedBody.get() == null ? -1 : receivedBody.get().length)
            + ")");
  }

  @Test
  void uploadStreamRawSendsRealContentLengthHeaderInsteadOfChunkedEncoding() throws Exception {
    byte[] payload = randomBytes(PAYLOAD_SIZE);
    AtomicReference<String> receivedContentLength = new AtomicReference<>();
    AtomicReference<String> receivedTransferEncoding = new AtomicReference<>();
    AtomicReference<byte[]> receivedBody = new AtomicReference<>();
    CountDownLatch requestHandled = new CountDownLatch(1);

    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext(
        "/upload-raw-content-length",
        exchange -> {
          try {
            receivedContentLength.set(exchange.getRequestHeaders().getFirst("Content-Length"));
            receivedTransferEncoding.set(
                exchange.getRequestHeaders().getFirst("Transfer-Encoding"));
            receivedBody.set(exchange.getRequestBody().readAllBytes());
            exchange.sendResponseHeaders(200, -1);
          } finally {
            exchange.close();
            requestHandled.countDown();
          }
        });
    server.start();

    HttpClient client = RestStreamingSupport.http1Client();
    URI uri =
        URI.create(
            "http://localhost:" + server.getAddress().getPort() + "/upload-raw-content-length");

    assertTimeoutPreemptively(
        Duration.ofSeconds(10),
        () -> {
          var response =
              RestStreamingSupport.uploadStreamRaw(
                  client,
                  uri,
                  Map.of(),
                  "application/octet-stream",
                  () -> new ByteArrayInputStream(payload),
                  payload.length,
                  Duration.ofSeconds(10));
          assertEquals(200, response.statusCode());
        },
        "uploadStreamRaw hung: the body was never fully sent/acknowledged");

    assertTrue(
        requestHandled.await(5, TimeUnit.SECONDS),
        "server handler never completed processing the request");
    // This is the crux of the fix: the JDK HTTP client must advertise a real, known
    // Content-Length (derived from the contentLength parameter) rather than falling back to
    // chunked transfer-encoding (which is what a bare BodyPublishers.ofInputStream would do,
    // since it reports an unknown length of -1).
    assertEquals(
        String.valueOf(payload.length),
        receivedContentLength.get(),
        "server should have received a Content-Length header matching the declared contentLength");
    assertTrue(
        receivedTransferEncoding.get() == null
            || !receivedTransferEncoding.get().toLowerCase().contains("chunked"),
        "request must not use chunked transfer-encoding when a Content-Length is known");
    assertArrayEquals(
        payload, receivedBody.get(), "server-received body bytes must match the uploaded payload");
  }

  @Test
  void uploadStreamRawSendsContentTypeAndCallerHeadersVerbatim() throws Exception {
    byte[] payload = "raw body content for header test".getBytes(StandardCharsets.UTF_8);
    AtomicReference<String> receivedContentType = new AtomicReference<>();
    AtomicReference<String> receivedFilename = new AtomicReference<>();
    AtomicReference<String> receivedParentId = new AtomicReference<>();
    CountDownLatch requestHandled = new CountDownLatch(1);

    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext(
        "/upload-raw-headers",
        exchange -> {
          try {
            receivedContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            receivedFilename.set(exchange.getRequestHeaders().getFirst("Filename"));
            receivedParentId.set(exchange.getRequestHeaders().getFirst("ParentId"));
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, -1);
          } finally {
            exchange.close();
            requestHandled.countDown();
          }
        });
    server.start();

    HttpClient client = RestStreamingSupport.http1Client();
    URI uri =
        URI.create("http://localhost:" + server.getAddress().getPort() + "/upload-raw-headers");

    assertTimeoutPreemptively(
        Duration.ofSeconds(10),
        () -> {
          var response =
              RestStreamingSupport.uploadStreamRaw(
                  client,
                  uri,
                  Map.of("Filename", "report.pdf", "ParentId", "LOCAL_ROOT"),
                  "application/pdf",
                  () -> new ByteArrayInputStream(payload),
                  payload.length,
                  Duration.ofSeconds(10));
          assertEquals(200, response.statusCode());
        },
        "uploadStreamRaw hung while sending the request");

    assertTrue(requestHandled.await(5, TimeUnit.SECONDS));
    assertEquals("application/pdf", receivedContentType.get());
    assertEquals("report.pdf", receivedFilename.get());
    assertEquals("LOCAL_ROOT", receivedParentId.get());
  }

  @Test
  void uploadStreamRawNonSuccessStatusThrowsIOExceptionWithStatusAndTruncatedBody()
      throws Exception {
    byte[] payload = "irrelevant body".getBytes(StandardCharsets.UTF_8);
    String errorBody = "node not found: parent does not exist";

    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext(
        "/upload-raw-error",
        exchange -> {
          try {
            exchange.getRequestBody().readAllBytes();
            byte[] errorBytes = errorBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, errorBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
              os.write(errorBytes);
            }
          } finally {
            exchange.close();
          }
        });
    server.start();

    HttpClient client = RestStreamingSupport.http1Client();
    URI uri = URI.create("http://localhost:" + server.getAddress().getPort() + "/upload-raw-error");

    IOException thrown =
        assertTimeoutPreemptively(
            Duration.ofSeconds(10),
            () ->
                assertThrows(
                    IOException.class,
                    () ->
                        RestStreamingSupport.uploadStreamRaw(
                            client,
                            uri,
                            Map.of(),
                            "application/octet-stream",
                            () -> new ByteArrayInputStream(payload),
                            payload.length,
                            Duration.ofSeconds(10))),
            "uploadStreamRaw hung instead of failing fast on a 500 response");

    assertTrue(
        thrown.getMessage().contains("500"),
        "exception message should contain the status code: " + thrown.getMessage());
    assertTrue(
        thrown.getMessage().contains(errorBody),
        "exception message should contain the (truncated) response body: " + thrown.getMessage());
    assertTrue(
        thrown.getMessage().contains(uri.toString()),
        "exception message should contain the request URI: " + thrown.getMessage());
  }

  @Test
  void uploadStreamRawSupplierIsInvokedFreshOnEachSendAndSupportsRepeatedSends() throws Exception {
    byte[] payload = randomBytes(20_000);
    AtomicInteger supplierInvocations = new AtomicInteger(0);
    AtomicInteger requestsReceived = new AtomicInteger(0);
    AtomicReference<byte[]> lastReceivedBody = new AtomicReference<>();

    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext(
        "/upload-raw-repeat",
        exchange -> {
          try {
            lastReceivedBody.set(exchange.getRequestBody().readAllBytes());
            requestsReceived.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
          } finally {
            exchange.close();
          }
        });
    server.start();

    HttpClient client = RestStreamingSupport.http1Client();
    URI uri =
        URI.create("http://localhost:" + server.getAddress().getPort() + "/upload-raw-repeat");

    Supplier<InputStream> countingSupplier =
        () -> {
          supplierInvocations.incrementAndGet();
          return new ByteArrayInputStream(payload);
        };

    assertTimeoutPreemptively(
        Duration.ofSeconds(10),
        () -> {
          for (int i = 0; i < 2; i++) {
            var response =
                RestStreamingSupport.uploadStreamRaw(
                    client,
                    uri,
                    Map.of(),
                    "application/octet-stream",
                    countingSupplier,
                    payload.length,
                    Duration.ofSeconds(10));
            assertEquals(200, response.statusCode());
            assertArrayEquals(
                payload,
                lastReceivedBody.get(),
                "attempt " + i + " did not deliver the full, unread payload");
          }
        },
        "uploadStreamRaw hung on a repeated send using the same supplier");

    assertEquals(2, requestsReceived.get());
    assertEquals(
        2,
        supplierInvocations.get(),
        "bodyStreamSupplier must be invoked once per send attempt so retries get a fresh stream");
  }

  private static byte[] randomBytes(int size) {
    byte[] bytes = new byte[size];
    new Random(42).nextBytes(bytes);
    return bytes;
  }
}
