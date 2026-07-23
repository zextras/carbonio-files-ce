// SPDX-FileCopyrightText: 2023 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.utilities.http;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import jakarta.annotation.Nullable;

public class HttpRequest {
  private final String method;
  private final String endpoint;

  @Nullable private final String cookie;
  @Nullable private final String bodyPayload;
  @Nullable private final byte[] binaryBody;
  @Nullable private final List<Map.Entry<String, String>> headers;

  private HttpRequest(
      String method,
      String endpoint,
      @Nullable String cookie,
      @Nullable List<Map.Entry<String, String>> headers,
      @Nullable String bodyPayload,
      @Nullable byte[] binaryBody) {
    this.method = method;
    this.endpoint = endpoint;
    this.cookie = cookie;
    this.bodyPayload = bodyPayload;
    this.headers = headers;
    this.binaryBody = binaryBody;
  }

  public String getMethod() {
    return method;
  }

  public String getEndpoint() {
    return endpoint;
  }

  public Optional<String> getCookie() {
    return Optional.ofNullable(cookie);
  }

  public Optional<String> getBodyPayload() {
    return Optional.ofNullable(bodyPayload);
  }

  /**
   * The raw bytes for a binary/streamed upload request (see {@code FilesTestApp#upload}). Empty
   * for every other request kind, which instead carry a text {@link #getBodyPayload()}.
   */
  public Optional<byte[]> getBinaryBody() {
    return Optional.ofNullable(binaryBody);
  }

  public Optional<List<Map.Entry<String, String>>> getHeaders() {
    return Optional.ofNullable(headers);
  }

  public static HttpRequest of(
      String method, String endpoint, @Nullable String cookie, @Nullable String bodyPayload) {
    return new HttpRequest(method, endpoint, cookie, null, bodyPayload, null);
  }

  public static HttpRequest of(
      String method, String endpoint, @Nullable String cookie, @Nullable List<Map.Entry<String, String>> headers, @Nullable String bodyPayload) {
    return new HttpRequest(method, endpoint, cookie, headers, bodyPayload, null);
  }

  /**
   * Factory for a binary/streamed upload request (the upload routes — {@code /upload},
   * {@code /upload-version}, {@code /internal/upload}, {@code /upload-to} — read the body as a
   * raw byte stream, not as JSON/form text, so they cannot use {@link #of}). Paired with {@code
   * FilesTestApp#upload(HttpRequest)}.
   *
   * @param body the raw bytes to upload; the Content-Length sent on the wire is always computed
   *     from this array's length by the {@code FilesTestApp} implementations (never taken from
   *     {@code headers}).
   */
  public static HttpRequest ofUpload(
      String method,
      String endpoint,
      @Nullable String cookie,
      @Nullable List<Map.Entry<String, String>> headers,
      byte[] body) {
    return new HttpRequest(method, endpoint, cookie, headers, null, body);
  }
}
