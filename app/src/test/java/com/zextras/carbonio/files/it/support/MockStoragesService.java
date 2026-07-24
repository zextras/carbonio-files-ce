// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.extension.ResponseTransformerV2;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.http.HttpHeader;
import com.github.tomakehurst.wiremock.http.HttpHeaders;
import com.github.tomakehurst.wiremock.http.QueryParameter;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.http.Response;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stateful HTTP fake for carbonio-storages, backed by a dedicated {@link WireMockServer}. Replaces
 * the P4a-era {@code @io.quarkus.test.Mock InMemoryFilestore} CDI double: with this fake wired into
 * {@code networking-config.carbonio.storages.host/port}, the app's REAL {@code FilestoreProducer} /
 * {@code StoragesClient} (Retrofit2 + OkHttp3, plain HTTP, no Consul/mesh/mTLS) talks to it over a
 * real socket — no {@code @Mock} CDI {@code @Alternative} of {@link com.zextras.filestore.api.Filestore}
 * survives, which is required once {@code @QuarkusIntegrationTest} (out-of-process) is introduced.
 *
 * <p>The wire contract implemented here was recovered from the frozen legacy MockServer fixture
 * ({@code core/src/test/.../utilities/StoragesMockHelper.java}) and cross-checked against the
 * {@code storages-ce-sdk} Retrofit interface ({@code Filestore}/{@code Checks}) bytecode:
 *
 * <ul>
 *   <li>{@code GET /download?node=&version=&type=} → 200 + raw bytes (a stored/seeded blob if
 *       present, else deterministic {@code (node+version)} fallback bytes) / 404 (explicitly forced
 *       missing, e.g. after {@link #setUploadSkipsStore}); {@link #setDownloadFails} simulates a
 *       dropped connection ({@link Fault#CONNECTION_RESET_BY_PEER}).
 *   <li>{@code PUT|POST /upload?node=&version=&type=} (multipart, part name {@code "file"}) → 200 +
 *       {@code {"digest":"…","digest_algorithm":"SHA-256","size":N}} (the
 *       {@code StoragesUploadResponse} Gson shape); {@link #setUploadFails} → 500;
 *       {@link #setUploadSkipsStore} → 200 but nothing is stored (a later download 404s).
 *   <li>{@code PUT /copy?sourceNode=&sourceVersion=&destinationNode=&destinationVersion=&type=&override=}
 *       → duplicates the source blob to the destination key, 200 + the same upload-response JSON;
 *       {@link #setCopyFails} → 500.
 *   <li>{@code POST /bulk-delete?type=} (body {@code {"ids":[{"node":…,"version":…}]}}) → removes the
 *       matching blobs and returns {@code {"ids":[…failed…]}} (an empty array is the real
 *       full-success signal, deliberately NOT an omitted field); {@link #setBulkDeleteAlwaysThrows} →
 *       500 (persistent outage); {@link #setBulkDeleteReturnsNull} → 200 body {@code "{}"} (the SDK
 *       deserialises {@code ids=null} and throws an uncaught NPE out of {@code bulkDelete(...)}, which
 *       production code treats identically to any other bulk-delete failure); {@link
 *       #failBulkDeleteFor} marks specific node ids as per-item failures (their blob is kept).
 *   <li>{@code GET /health/live} → 200, or 502 once {@link #setLive} is flipped to {@code false}.
 * </ul>
 *
 * <p>Statefulness (the in-memory blob store, failure switches, download log) is implemented via a
 * single named {@link ResponseTransformerV2} attached to explicit low-priority stub mappings for each
 * route — WireMock's own declarative stub matching does the routing (path + HTTP method), the
 * transformer does the business logic. Binary bodies flow through {@code Response.Builder#body(byte[])}.
 */
public class MockStoragesService {

  private static final String TRANSFORMER_NAME = "storages-fake";
  private static final String FILES_TYPE = "files";

  private static final Pattern JSON_OBJECT_PATTERN = Pattern.compile("\\{[^{}]*}");
  private static final Pattern NODE_FIELD_PATTERN = Pattern.compile("\"node\"\\s*:\\s*\"([^\"]*)\"");
  private static final Pattern VERSION_FIELD_PATTERN = Pattern.compile("\"version\"\\s*:\\s*(-?\\d+)");

  private final WireMockServer server;

  /** node+"/"+version -> stored bytes. The single source of truth for "is this blob present". */
  private final Map<String, byte[]> blobs = new ConcurrentHashMap<>();

  /**
   * Keys explicitly forced absent by {@link #setUploadSkipsStore} (an upload that reports success
   * but stores nothing), so a later download of exactly that key 404s instead of falling back to the
   * deterministic {@code (node+version)} bytes convention used for keys that were simply never
   * touched.
   */
  private final java.util.Set<String> forcedMissingKeys = ConcurrentHashMap.newKeySet();

  /** Node ids reported as FAILED by the next bulk-delete(s); their blob is kept. */
  private final java.util.Set<String> failingBulkDeleteNodeIds = ConcurrentHashMap.newKeySet();

  /** Every download key attempted (success or failure), so {@link #verifyDownloaded} can assert on it. */
  private final List<String> downloadLog = Collections.synchronizedList(new ArrayList<>());

  private volatile boolean uploadFails = false;
  private volatile boolean uploadSkipsStore = false;
  private volatile boolean downloadFails = false;
  private volatile boolean copyFails = false;
  private volatile boolean bulkDeleteAlwaysThrows = false;
  private volatile boolean bulkDeleteReturnsNull = false;
  private volatile boolean unreachable = false;

  public MockStoragesService() {
    server =
        new WireMockServer(
            WireMockConfiguration.options().dynamicPort().extensions(new StatefulTransformer()));
    server.start();
    registerRoutes();
  }

  private void registerRoutes() {
    server.stubFor(
        get(urlPathEqualTo("/download")).willReturn(aResponse().withTransformers(TRANSFORMER_NAME)));
    server.stubFor(
        put(urlPathEqualTo("/upload")).willReturn(aResponse().withTransformers(TRANSFORMER_NAME)));
    server.stubFor(
        post(urlPathEqualTo("/upload")).willReturn(aResponse().withTransformers(TRANSFORMER_NAME)));
    server.stubFor(
        put(urlPathEqualTo("/copy")).willReturn(aResponse().withTransformers(TRANSFORMER_NAME)));
    server.stubFor(
        post(urlPathEqualTo("/bulk-delete"))
            .willReturn(aResponse().withTransformers(TRANSFORMER_NAME)));
    server.stubFor(
        get(urlPathEqualTo("/health/live"))
            .willReturn(aResponse().withTransformers(TRANSFORMER_NAME)));
  }

  /** The port the fake carbonio-storages server is listening on. */
  public int getPort() {
    return server.port();
  }

  // ------------------------------------------------------------------------------- seed / inspect

  /** Seeds deterministic bytes for {@code node/version}, so a later download serves them. */
  public void seed(String node, int version, byte[] bytes) {
    String key = key(node, version);
    blobs.put(key, bytes);
    forcedMissingKeys.remove(key);
  }

  /** {@code true} if a blob for the given node/version is currently stored. */
  public boolean has(String node, int version) {
    return blobs.containsKey(key(node, version));
  }

  public boolean wasDownloaded(String node, int version) {
    return downloadLog.contains(key(node, version));
  }

  public int downloadCount() {
    return downloadLog.size();
  }

  public void verifyDownloaded(String node, int version) {
    if (!wasDownloaded(node, version)) {
      throw new AssertionError(
          "Expected storages download for node=" + node + " version=" + version + " but none");
    }
  }

  public void verifyNeverDownloaded() {
    if (downloadCount() != 0) {
      throw new AssertionError("Expected no storages download but got " + downloadCount());
    }
  }

  public void verifyUploaded(String node, int version) {
    if (!has(node, version)) {
      throw new AssertionError(
          "Expected storages upload for node=" + node + " version=" + version + " but none");
    }
  }

  // --------------------------------------------------------------------------- failure injection

  public void failBulkDeleteFor(String... nodeIds) {
    failingBulkDeleteNodeIds.addAll(List.of(nodeIds));
  }

  public void resetBulkDeleteFailures() {
    failingBulkDeleteNodeIds.clear();
  }

  public void setBulkDeleteAlwaysThrows(boolean value) {
    this.bulkDeleteAlwaysThrows = value;
  }

  public void setBulkDeleteReturnsNull(boolean value) {
    this.bulkDeleteReturnsNull = value;
  }

  public void setUploadFails(boolean value) {
    this.uploadFails = value;
  }

  public void setUploadSkipsStore(boolean value) {
    this.uploadSkipsStore = value;
  }

  public void setDownloadFails(boolean value) {
    this.downloadFails = value;
  }

  public void setCopyFails(boolean value) {
    this.copyFails = value;
  }

  /** Drives {@code GET /health/live}: {@code true} → 200 (OK), {@code false} → 502 (ERROR). */
  public void setLive(boolean live) {
    this.unreachable = !live;
  }

  // ------------------------------------------------------------------------------------- reset

  /** Resets ALL failure-injection controls (and the download log) to the happy-path baseline. */
  public void reset() {
    resetBulkDeleteFailures();
    bulkDeleteAlwaysThrows = false;
    bulkDeleteReturnsNull = false;
    uploadFails = false;
    uploadSkipsStore = false;
    downloadFails = false;
    copyFails = false;
    unreachable = false;
    downloadLog.clear();
  }

  /** Wipes all stored blobs. Used on acceptance app close() for cross-test-class hygiene. */
  public void clearAll() {
    blobs.clear();
    forcedMissingKeys.clear();
  }

  // --------------------------------------------------------------------------------- request handling

  private Response handleDownload(Request request) {
    String node = queryParam(request, "node");
    Integer version = queryParamInt(request, "version");
    String key = key(node, version);
    downloadLog.add(key);

    if (downloadFails) {
      return Response.response().fault(Fault.CONNECTION_RESET_BY_PEER).build();
    }
    if (forcedMissingKeys.contains(key)) {
      return Response.response().status(404).build();
    }
    byte[] bytes = blobs.get(key);
    if (bytes == null) {
      // Deterministic fallback convention (mirrors the legacy StoragesMockHelper#getBlob contract):
      // a node/version that was never explicitly seeded/uploaded still serves stable bytes rather
      // than 404ing, so a test that forgets an explicit seed doesn't spuriously fail on content.
      bytes = fallbackBytes(node, version);
    }
    return Response.response()
        .status(200)
        .headers(new HttpHeaders(HttpHeader.httpHeader("Content-Type", "application/octet-stream")))
        .body(bytes)
        .build();
  }

  private Response handleUpload(Request request) {
    if (uploadFails) {
      return Response.response().status(500).build();
    }
    String node = queryParam(request, "node");
    Integer version = queryParamInt(request, "version");
    String key = key(node, version);
    byte[] content = extractUploadBytes(request);

    if (uploadSkipsStore) {
      blobs.remove(key);
      forcedMissingKeys.add(key);
    } else {
      blobs.put(key, content);
      forcedMissingKeys.remove(key);
    }
    return Response.response()
        .status(200)
        .headers(jsonHeaders())
        .body(uploadResponseJson(content.length))
        .build();
  }

  private Response handleCopy(Request request) {
    if (copyFails) {
      return Response.response().status(500).build();
    }
    String sourceNode = queryParam(request, "sourceNode");
    Integer sourceVersion = queryParamInt(request, "sourceVersion");
    String destinationNode = queryParam(request, "destinationNode");
    Integer destinationVersion = queryParamInt(request, "destinationVersion");
    String sourceKey = key(sourceNode, sourceVersion);
    String destinationKey = key(destinationNode, destinationVersion);

    if (forcedMissingKeys.contains(sourceKey)) {
      return Response.response().status(404).build();
    }
    byte[] bytes = blobs.get(sourceKey);
    if (bytes == null) {
      bytes = fallbackBytes(sourceNode, sourceVersion);
    }
    blobs.put(destinationKey, bytes);
    forcedMissingKeys.remove(destinationKey);
    return Response.response()
        .status(200)
        .headers(jsonHeaders())
        .body(uploadResponseJson(bytes.length))
        .build();
  }

  private Response handleBulkDelete(Request request) {
    if (bulkDeleteAlwaysThrows) {
      return Response.response().status(500).build();
    }
    if (bulkDeleteReturnsNull) {
      // Body-less/ids-less {} — the SDK deserialises ids=null and NPEs inside bulkDelete(...);
      // production treats that identically to any other bulk-delete failure (outage).
      return Response.response().status(200).headers(jsonHeaders()).body("{}").build();
    }

    List<Map.Entry<String, Integer>> items = parseBulkDeleteItems(request.getBodyAsString());
    List<Map.Entry<String, Integer>> failed = new ArrayList<>();
    for (Map.Entry<String, Integer> item : items) {
      String node = item.getKey();
      Integer version = item.getValue();
      if (failingBulkDeleteNodeIds.contains(node)) {
        failed.add(item);
      } else if (version != null) {
        String key = key(node, version);
        blobs.remove(key);
        forcedMissingKeys.remove(key);
      }
    }
    return Response.response()
        .status(200)
        .headers(jsonHeaders())
        .body(bulkDeleteResponseJson(failed))
        .build();
  }

  private Response handleHealth() {
    return Response.response().status(unreachable ? 502 : 200).build();
  }

  // ------------------------------------------------------------------------------------- helpers

  private static byte[] extractUploadBytes(Request request) {
    if (request.isMultipart()) {
      Request.Part part = request.getPart("file");
      if (part != null) {
        return part.getBody().asBytes();
      }
    }
    return request.getBody();
  }

  private static byte[] fallbackBytes(String node, Integer version) {
    return (node + version).getBytes(StandardCharsets.UTF_8);
  }

  private static String key(String node, Integer version) {
    return node + "/" + version;
  }

  private static String queryParam(Request request, String name) {
    QueryParameter param = request.queryParameter(name);
    return param != null && param.isPresent() ? param.firstValue() : null;
  }

  private static Integer queryParamInt(Request request, String name) {
    String value = queryParam(request, name);
    if (value == null) {
      return null;
    }
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static HttpHeaders jsonHeaders() {
    return new HttpHeaders(HttpHeader.httpHeader("Content-Type", "application/json"));
  }

  /** Builds a {@code StoragesUploadResponse}-shaped JSON body (fields read via Gson SerializedName). */
  private static String uploadResponseJson(long size) {
    return "{\"digest\":\"test-digest-"
        + size
        + "\",\"digest_algorithm\":\"SHA-256\",\"size\":"
        + size
        + "}";
  }

  /**
   * Builds a {@code StoragesBulkDeleteResponse}-shaped JSON body. An EMPTY {@code failed} list
   * serialises to the real full-success signal {@code {"ids":[]}} (a non-null empty array) —
   * deliberately not an omitted field, which the SDK would read as {@code ids=null} and NPE on.
   */
  private static String bulkDeleteResponseJson(List<Map.Entry<String, Integer>> failed) {
    StringBuilder body = new StringBuilder("{\"ids\":[");
    for (int i = 0; i < failed.size(); i++) {
      if (i > 0) {
        body.append(',');
      }
      Map.Entry<String, Integer> item = failed.get(i);
      body.append("{\"node\":\"").append(item.getKey()).append("\",\"type\":\"").append(FILES_TYPE)
          .append('"');
      if (item.getValue() != null) {
        body.append(",\"version\":").append(item.getValue());
      }
      body.append('}');
    }
    body.append("]}");
    return body.toString();
  }

  /**
   * Extracts {@code {node, version}} pairs from a {@code StoragesBulkDeleteBody}-shaped JSON body
   * ({@code {"ids":[{"version":1,"node":"…"}, …]}}). A small hand-rolled parser (no nested objects/
   * arrays in this shape) mirrors the manual JSON building already used by {@code
   * MockUserManagementService} rather than pulling in a JSON library purely for this fake.
   */
  private static List<Map.Entry<String, Integer>> parseBulkDeleteItems(String body) {
    List<Map.Entry<String, Integer>> items = new ArrayList<>();
    if (body == null) {
      return items;
    }
    Matcher objects = JSON_OBJECT_PATTERN.matcher(body);
    while (objects.find()) {
      String object = objects.group();
      Matcher nodeMatcher = NODE_FIELD_PATTERN.matcher(object);
      if (!nodeMatcher.find()) {
        continue;
      }
      String node = nodeMatcher.group(1);
      Matcher versionMatcher = VERSION_FIELD_PATTERN.matcher(object);
      Integer version = versionMatcher.find() ? Integer.valueOf(versionMatcher.group(1)) : null;
      items.add(new AbstractMap.SimpleEntry<>(node, version));
    }
    return items;
  }

  /** Routes a matched stub's response through the stateful handlers above by (method, path). */
  private final class StatefulTransformer implements ResponseTransformerV2 {

    @Override
    public String getName() {
      return TRANSFORMER_NAME;
    }

    @Override
    public boolean applyGlobally() {
      // Only stubs explicitly declaring withTransformers(TRANSFORMER_NAME) (registerRoutes()) go
      // through this transformer; unrelated traffic (there is none on a dedicated server, but this
      // keeps the extension's behaviour unambiguous) is left untouched.
      return false;
    }

    @Override
    public Response transform(Response response, ServeEvent serveEvent) {
      Request request = serveEvent.getRequest();
      String path = pathOf(request.getUrl());
      String method = request.getMethod().value();

      return switch (path) {
        case "/download" -> "GET".equals(method) ? handleDownload(request) : response;
        case "/upload" -> ("PUT".equals(method) || "POST".equals(method))
            ? handleUpload(request)
            : response;
        case "/copy" -> "PUT".equals(method) ? handleCopy(request) : response;
        case "/bulk-delete" -> "POST".equals(method) ? handleBulkDelete(request) : response;
        case "/health/live" -> "GET".equals(method) ? handleHealth() : response;
        default -> response;
      };
    }

    private String pathOf(String url) {
      int queryIndex = url.indexOf('?');
      return queryIndex >= 0 ? url.substring(0, queryIndex) : url;
    }
  }
}
