// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FilestoreProducerTest {

  private static final MediaType OCTET_STREAM = MediaType.get("application/octet-stream");

  private HttpServer server;
  private ExecutorService serverExecutor;
  private String baseUrl;
  private OkHttpClient client;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          long read = 0;
          try (InputStream in = exchange.getRequestBody()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) {
              read += n;
            }
          } catch (IOException e) {
            exchange.close();
            return;
          }
          byte[] response = ("ok " + read).getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    serverExecutor = Executors.newCachedThreadPool();
    server.setExecutor(serverExecutor);
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    client =
        new OkHttpClient.Builder()
            .addNetworkInterceptor(FilestoreProducer::dropFailedConnection)
            .callTimeout(Duration.ofSeconds(5))
            .build();
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
    serverExecutor.shutdownNow();
    client.connectionPool().evictAll();
  }

  @Test
  void storagesClient_hasNoTimeLimit() {
    OkHttpClient storages = FilestoreProducer.storagesHttpClient().build();

    assertThat(storages.connectTimeoutMillis()).isZero();
    assertThat(storages.readTimeoutMillis()).isZero();
    assertThat(storages.writeTimeoutMillis()).isZero();
    assertThat(storages.callTimeoutMillis()).isZero();
    assertThat(storages.networkInterceptors()).hasSize(1);
  }

  @Test
  void coldConnection_failedUploadThenPost_succeeds() throws IOException {
    abortUpload();
    assertVictimSucceeds(postRequest());
  }

  @Test
  void warmConnection_failedUploadThenGet_succeeds() throws IOException {
    warmUp();
    abortUpload();
    assertVictimSucceeds(getRequest());
  }

  private void warmUp() throws IOException {
    try (Response response = client.newCall(getRequest()).execute()) {
      assertThat(response.code()).isEqualTo(200);
      response.body().string();
    }
  }

  private void abortUpload() {
    assertThatThrownBy(() -> client.newCall(failingUploadRequest()).execute())
        .isInstanceOf(IOException.class)
        .hasMessage("client gone");
    assertThat(client.connectionPool().connectionCount()).isZero();
  }

  private void assertVictimSucceeds(Request request) throws IOException {
    try (Response response = client.newCall(request).execute()) {
      assertThat(response.code()).isEqualTo(200);
      response.body().string();
    }
    assertThat(client.connectionPool().connectionCount()).isEqualTo(1);
  }

  private Request getRequest() {
    return new Request.Builder().url(baseUrl + "/victim").build();
  }

  private Request postRequest() {
    return new Request.Builder()
        .url(baseUrl + "/victim")
        .post(RequestBody.create(OCTET_STREAM, new byte[100 * 1024]))
        .build();
  }

  private Request failingUploadRequest() {
    RequestBody body =
        new RequestBody() {
          @Override
          public MediaType contentType() {
            return OCTET_STREAM;
          }

          @Override
          public long contentLength() {
            return 1 << 20;
          }

          @Override
          public void writeTo(BufferedSink sink) throws IOException {
            sink.write(new byte[32 * 1024]);
            sink.flush();
            throw new IOException("client gone");
          }
        };
    return new Request.Builder().url(baseUrl + "/abort").post(body).build();
  }
}
