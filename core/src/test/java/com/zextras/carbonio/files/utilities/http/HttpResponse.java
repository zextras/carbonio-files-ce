// SPDX-FileCopyrightText: 2023 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.utilities.http;

public class HttpResponse {
  private final int status;
  private final String bodyPayload;

  private HttpResponse(int status, String bodyPayload) {
    this.status = status;
    this.bodyPayload = bodyPayload;
  }

  public int getStatus() {
    return status;
  }

  public String getBodyPayload() {
    return bodyPayload;
  }

  public static HttpResponse of(int status, String bodyPayload) {
    return new HttpResponse(status, bodyPayload);
  }
}
