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
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * F1 (Quarkus-rewrite hardening restoration): a missing/unparseable {@code Content-Length} header
 * must be REFUSED (400), not silently treated as "no size limit applies". Legacy parity: {@code
 * core/.../BlobController#isRequestSizeOverLimit} did {@code
 * Long.parseLong(httpRequest.headers().get(CONTENT_LENGTH))} as its very first act — a request
 * with no {@code Content-Length} header (e.g. chunked transfer-encoding) threw a {@code
 * NumberFormatException} (an {@link IllegalArgumentException} subtype), mapped to HTTP 400 by the
 * legacy {@code ExceptionsHandler}, independent of whether {@code max-uploadable-size-in-mb} was
 * even configured. {@code BlobResource#isRequestSizeOverLimit} instead returned {@code false} for
 * a {@code null} content length, bypassing the size check entirely.
 *
 * <p>Uses the JDK {@link HttpClient} with {@link BodyPublishers#ofInputStream} on purpose: its
 * content length is unknown until the stream is exhausted, so the JDK client always sends the
 * request with {@code Transfer-Encoding: chunked} and NO {@code Content-Length} header at all —
 * RestAssured (backed by Apache HttpClient) always computes a {@code Content-Length} for an
 * in-memory {@code byte[]}/{@code String} body, so it cannot reproduce this exact request shape.
 */
class UploadContentLengthApiIT extends AbstractFilesIT {

  private static final String REQUESTER_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String REQUESTER_COOKIE = "ZM_AUTH_TOKEN=fake-token";

  @BeforeAll
  static void registerUsers() {
    FilesStackTestResource.getUserManagementService().registerToken("fake-token", REQUESTER_ID);
  }

  private static String baseUrl() {
    return RestAssured.baseURI + ":" + RestAssured.port;
  }

  private static String base64(String value) {
    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  /** POSTs {@code body} to {@code path} via chunked transfer-encoding: no Content-Length header. */
  private static HttpResponse<String> postWithoutContentLength(
      String path, byte[] body, Map<String, String> headers) throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(baseUrl() + path))
            .version(HttpClient.Version.HTTP_1_1)
            .POST(BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body)));
    headers.forEach(builder::header);
    HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void givenUploadWithNoContentLengthHeaderTheApiShouldReturn400() throws Exception {
    // Given — a perfectly ordinary small body, sent WITHOUT a Content-Length header.
    byte[] content = "some file content".getBytes(StandardCharsets.UTF_8);

    // When
    HttpResponse<String> response =
        postWithoutContentLength(
            "/upload",
            content,
            Map.of("Filename", base64("chunked.bin"), "Cookie", REQUESTER_COOKIE));

    // Then — legacy parity: a missing Content-Length is refused, not treated as "no cap".
    Assertions.assertThat(response.statusCode()).isEqualTo(400);
  }

  @Test
  void givenUploadVersionWithNoContentLengthHeaderTheApiShouldReturn400() throws Exception {
    // Given
    String nodeId =
        seedFile(
            "versioned.bin", LOCAL_ROOT, "v1".getBytes(StandardCharsets.UTF_8), REQUESTER_COOKIE);
    byte[] content = "some new version content".getBytes(StandardCharsets.UTF_8);

    // When
    HttpResponse<String> response =
        postWithoutContentLength(
            "/upload-version",
            content,
            Map.of(
                "NodeId", nodeId,
                "Filename", base64("chunked-version.bin"),
                "OverwriteVersion", "false",
                "Cookie", REQUESTER_COOKIE));

    // Then
    Assertions.assertThat(response.statusCode()).isEqualTo(400);
  }
}
