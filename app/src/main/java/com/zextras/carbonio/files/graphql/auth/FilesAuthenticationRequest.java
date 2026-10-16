// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.auth;

import io.quarkus.security.identity.request.BaseAuthenticationRequest;

public class FilesAuthenticationRequest extends BaseAuthenticationRequest {

  private final String cookies;
  private final String uri;

  public FilesAuthenticationRequest(String cookies, String uri) {
    this.cookies = cookies;
    this.uri = uri;
  }

  public String getCookies() {
    return cookies;
  }

  public String getUri() {
    return uri;
  }
}
