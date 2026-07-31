// SPDX-FileCopyrightText: 2023 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.utilities.http;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;

public class HttpRequest {
  private final String method;
  private final String endpoint;

  @Nullable private final String cookie;
  @Nullable private final String bodyPayload;
  @Nullable private final List<Map.Entry<String, String>> headers;

  private HttpRequest(
      String method,
      String endpoint,
      @Nullable String cookie,
      @Nullable List<Map.Entry<String, String>> headers,
      @Nullable String bodyPayload) {
    this.method = method;
    this.endpoint = endpoint;
    this.cookie = cookie;
    this.bodyPayload = bodyPayload;
    this.headers = headers;
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

  public Optional<List<Map.Entry<String, String>>> getHeaders() {
    return Optional.ofNullable(headers);
  }

  public static HttpRequest of(
      String method, String endpoint, @Nullable String cookie, @Nullable String bodyPayload) {
    return new HttpRequest(method, endpoint, cookie, null, bodyPayload);
  }

  public static HttpRequest of(
      String method,
      String endpoint,
      @Nullable String cookie,
      @Nullable List<Map.Entry<String, String>> headers,
      @Nullable String bodyPayload) {
    return new HttpRequest(method, endpoint, cookie, headers, bodyPayload);
  }
}
