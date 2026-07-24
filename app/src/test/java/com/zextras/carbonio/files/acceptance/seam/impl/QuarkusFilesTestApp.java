// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.acceptance.seam.impl;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.TestUtils;
import com.zextras.carbonio.files.acceptance.seam.FilesTestApp;
import com.zextras.carbonio.files.acceptance.seam.Mocks;
import com.zextras.carbonio.files.acceptance.seam.TestDataAccess;
import com.zextras.carbonio.files.config.FilesConfig;
import com.zextras.carbonio.files.config.TestFilesConfig;
import com.zextras.carbonio.files.utilities.http.HttpRequest;
import com.zextras.carbonio.files.utilities.http.HttpResponse;
import io.quarkus.arc.Arc;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@link FilesTestApp} implementation that drives the shared {@code @QuarkusTest} Files application
 * over a real HTTP socket (the Quarkus test HTTP port exposed via {@code RestAssured.port}), using
 * {@link java.net.http.HttpClient}. It reuses the exact driving shape of the retired
 * {@code RealHttpFilesTestApp} — the acceptance-test bodies never learn which transport they got.
 *
 * <p>Because {@code @QuarkusTest} boots a single application per JVM and reuses it across every
 * acceptance class, {@link #close()} does NOT stop the app — it only rolls back the shared mock/
 * config/user-management state so the next class starts clean. The stack (Postgres, Consul WireMock,
 * preview/mailbox WireMock, in-process UM gRPC) is owned by {@link FilesStackTestResource}.
 */
public class QuarkusFilesTestApp implements FilesTestApp {

  private final HttpClient httpClient;
  private final TestDataAccess testDataAccess;
  private final Mocks mocks;

  QuarkusFilesTestApp() {
    this.httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    this.testDataAccess = new QuarkusTestDataAccess();
    this.mocks = new QuarkusMocks();
  }

  private static int port() {
    int p = io.restassured.RestAssured.port;
    return p > 0 ? p : 8081; // @QuarkusTest default test HTTP port
  }

  @Override
  public HttpResponse send(HttpRequest request) {
    // GraphQL POSTs: wrap the raw query as {"query":"<query>"} exactly like the legacy transport.
    // GET requests (downloads/previews/health) carry no body server-side.
    final String method = request.getMethod();
    if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)) {
      return exchange(request, null, "application/json");
    }
    final String wrappedBody = TestUtils.queryPayload(request.getBodyPayload().orElse(""));
    return exchange(request, wrappedBody, "application/json");
  }

  @Override
  public HttpResponse sendForm(HttpRequest request) {
    // Pre-built form body forwarded verbatim; content-type comes from the request headers.
    return exchange(request, request.getBodyPayload().orElse(""), null);
  }

  @Override
  public HttpResponse upload(HttpRequest request) {
    final byte[] body = request.getBinaryBody().orElse(new byte[0]);
    final java.net.http.HttpRequest.Builder builder =
        java.net.http.HttpRequest.newBuilder(URI.create("http://localhost:" + port() + request.getEndpoint()))
            .timeout(Duration.ofSeconds(20));
    request.getHeaders().ifPresent(h -> h.forEach(e -> builder.header(e.getKey(), e.getValue())));
    request.getCookie().ifPresent(cookie -> builder.header("Cookie", cookie));
    builder.method(request.getMethod(), BodyPublishers.ofByteArray(body));
    try {
      final java.net.http.HttpResponse<String> response =
          httpClient.send(builder.build(), BodyHandlers.ofString(StandardCharsets.UTF_8));
      return HttpResponse.of(response.statusCode(), flattenHeaders(response), response.body());
    } catch (Exception e) {
      throw new IllegalStateException(
          "Upload to " + request.getMethod() + " " + request.getEndpoint() + " failed", e);
    }
  }

  private HttpResponse exchange(HttpRequest request, String body, String defaultContentType) {
    final java.net.http.HttpRequest.Builder builder =
        java.net.http.HttpRequest.newBuilder(URI.create("http://localhost:" + port() + request.getEndpoint()))
            .timeout(Duration.ofSeconds(20));

    final boolean[] hasContentType = {false};
    request
        .getHeaders()
        .ifPresent(
            headers ->
                headers.forEach(
                    h -> {
                      builder.header(h.getKey(), h.getValue());
                      if ("content-type".equalsIgnoreCase(h.getKey())) {
                        hasContentType[0] = true;
                      }
                    }));
    request.getCookie().ifPresent(cookie -> builder.header("Cookie", cookie));
    if (defaultContentType != null && !hasContentType[0]) {
      builder.header("Content-Type", defaultContentType);
    }

    final String method = request.getMethod();
    if (body == null || "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)) {
      builder.method(method, BodyPublishers.noBody());
    } else {
      final BodyPublisher publisher = BodyPublishers.ofString(body, StandardCharsets.UTF_8);
      builder.method(method, publisher);
    }

    try {
      final java.net.http.HttpResponse<String> response =
          httpClient.send(builder.build(), BodyHandlers.ofString(StandardCharsets.UTF_8));
      return HttpResponse.of(response.statusCode(), flattenHeaders(response), response.body());
    } catch (Exception e) {
      throw new IllegalStateException(
          "Request to " + request.getMethod() + " " + request.getEndpoint() + " failed", e);
    }
  }

  private static List<Map.Entry<String, String>> flattenHeaders(
      java.net.http.HttpResponse<String> response) {
    final List<Map.Entry<String, String>> entries = new ArrayList<>();
    response
        .headers()
        .map()
        .forEach((name, values) -> values.forEach(value -> entries.add(Map.entry(name, value))));
    return entries;
  }

  @Override
  public TestDataAccess backdoor() {
    return testDataAccess;
  }

  @Override
  public Mocks mocks() {
    return mocks;
  }

  @Override
  public void close() {
    // Shared @QuarkusTest app: never stop it. Roll back all shared mutable state so the next
    // acceptance class starts from a clean baseline.
    FilesStackTestResource.getStoragesService().reset();
    FilesStackTestResource.getStoragesService().clearAll();

    ((TestFilesConfig) Arc.container().instance(FilesConfig.class).get()).reset();

    var um = FilesStackTestResource.getUserManagementService();
    um.setDown(false);
    um.clearAll();
    um.registerToken(FilesStackTestResource.AUTH_TOKEN, FilesStackTestResource.TEST_USER_ID);

    FilesStackTestResource.resetPreviewMailboxStubs();
    FilesStackTestResource.resetConsulStubs();
  }
}
