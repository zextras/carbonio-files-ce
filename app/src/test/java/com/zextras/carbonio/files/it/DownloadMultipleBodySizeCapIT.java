// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.it;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.it.support.AbstractFilesIT;
import io.restassured.RestAssured;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * F2 (Quarkus-rewrite hardening restoration): the legacy Netty pipeline capped the four {@code
 * download-multiple} routes' body at 1MB with {@code new HttpObjectAggregator(1048576)} (see {@code
 * core/.../HttpRoutingHandler#channelRead0}, lines ~110-119 for the authenticated pair, ~152-167
 * for the public pair). The Quarkus port dropped this cap entirely: {@code
 * BlobResource#downloadMultiple}/{@code #checkDownloadMultiple} and {@code
 * PublicBlobResource#downloadPublicMultiple}/{@code #checkDownloadPublicMultiple} buffered whatever
 * body arrived with no limit.
 *
 * <p>Every request below is sent via the JDK {@link HttpClient} with {@code
 * BodyPublishers.ofInputStream} — chunked transfer-encoding, NO {@code Content-Length} header at
 * all — specifically to prove the restored cap is enforced against bytes ACTUALLY READ, not a
 * trusted request header (the same bypass class as F1): {@code quarkus.http.limits.max-body-size}
 * is deliberately left blank (see {@code application.properties}) so a global framework-level cap
 * cannot be relied upon either.
 */
class DownloadMultipleBodySizeCapIT extends AbstractFilesIT {

  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String REQUESTER_COOKIE = "ZM_AUTH_TOKEN=fake-token";

  /** Comfortably over the legacy 1MB (1048576 byte) cap. */
  private static final int OVER_ONE_MB = 1024 * 1024 + 4096;

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", REQUESTER_ID);
  }

  private static String baseUrl() {
    return RestAssured.baseURI + ":" + RestAssured.port;
  }

  /**
   * A body whose declared field's value alone is over the 1MB cap. Deliberately NOT valid
   * JSON/form-encoding: the size cap must trip before any parsing is attempted.
   */
  private static byte[] oversizedBody(String prefix) {
    byte[] filler = new byte[OVER_ONE_MB];
    Arrays.fill(filler, (byte) 'a');
    byte[] prefixBytes = prefix.getBytes(StandardCharsets.UTF_8);
    byte[] result = new byte[prefixBytes.length + filler.length];
    System.arraycopy(prefixBytes, 0, result, 0, prefixBytes.length);
    System.arraycopy(filler, 0, result, prefixBytes.length, filler.length);
    return result;
  }

  private static HttpResponse<String> postChunked(
      String path, String contentType, byte[] body, String cookie) throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(baseUrl() + path))
            .version(HttpClient.Version.HTTP_1_1)
            .header("Content-Type", contentType)
            .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body)));
    if (cookie != null) {
      builder.header("Cookie", cookie);
    }
    HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void givenAnOversizedChunkedBodyDownloadMultipleShouldReturn413() throws Exception {
    HttpResponse<String> response =
        postChunked(
            "/download-multiple",
            "application/x-www-form-urlencoded",
            oversizedBody("nodeIds="),
            REQUESTER_COOKIE);

    Assertions.assertThat(response.statusCode()).isEqualTo(413);
  }

  @Test
  void givenAnOversizedChunkedBodyDownloadMultipleCheckShouldReturn413() throws Exception {
    HttpResponse<String> response =
        postChunked(
            "/download-multiple/check",
            "application/json",
            oversizedBody("{\"nodeIds\":\""),
            REQUESTER_COOKIE);

    Assertions.assertThat(response.statusCode()).isEqualTo(413);
  }

  @Test
  void givenAnOversizedChunkedBodyPublicDownloadMultipleShouldReturn413() throws Exception {
    // No cookie: /public/download-multiple needs none.
    HttpResponse<String> response =
        postChunked(
            "/public/download-multiple",
            "application/x-www-form-urlencoded",
            oversizedBody("nodeIds="),
            null);

    Assertions.assertThat(response.statusCode()).isEqualTo(413);
  }

  @Test
  void givenAnOversizedChunkedBodyPublicDownloadMultipleCheckShouldReturn413() throws Exception {
    HttpResponse<String> response =
        postChunked(
            "/public/download-multiple/check",
            "application/json",
            oversizedBody("{\"nodeIds\":\""),
            null);

    Assertions.assertThat(response.statusCode()).isEqualTo(413);
  }
}
