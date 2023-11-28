// SPDX-FileCopyrightText: 2023 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.utilities.http;

import java.util.Optional;
import javax.annotation.Nullable;

public class HttpRequest {
  private final String method;
  private final String endpoint;

  @Nullable private final String cookie;
  @Nullable private final String bodyPayload;

  private HttpRequest(
      String method, String endpoint, @Nullable String cookie, @Nullable String bodyPayload) {
    this.method = method;
    this.endpoint = endpoint;
    this.cookie = cookie;
    this.bodyPayload = bodyPayload;
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

  public static HttpRequest of(
      String method, String endpoint, @Nullable String cookie, @Nullable String bodyPayload) {
    return new HttpRequest(method, endpoint, cookie, bodyPayload);
  }
}
