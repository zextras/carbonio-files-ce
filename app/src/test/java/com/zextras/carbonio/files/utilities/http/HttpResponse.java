// SPDX-FileCopyrightText: 2023 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.utilities.http;

import java.util.List;
import java.util.Map;

public class HttpResponse {
  private final int status;

  private final List<Map.Entry<String, String>> headers;
  private final String bodyPayload;

  private HttpResponse(int status, List<Map.Entry<String, String>> headers, String bodyPayload) {
    this.status = status;
    this.headers = headers;
    this.bodyPayload = bodyPayload;
  }

  public int getStatus() {
    return status;
  }

  public String getBodyPayload() {
    return bodyPayload;
  }

  public List<Map.Entry<String, String>> getHeaders() {
    return headers;
  }

  public static HttpResponse of(int status, List<Map.Entry<String, String>> headers, String bodyPayload) {
    return new HttpResponse(status, headers, bodyPayload);
  }
}
