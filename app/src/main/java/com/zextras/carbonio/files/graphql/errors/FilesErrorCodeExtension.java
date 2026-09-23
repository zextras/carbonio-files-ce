// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.errors;

import io.smallrye.graphql.api.ErrorExtensionProvider;
import jakarta.json.Json;
import jakarta.json.JsonValue;

public class FilesErrorCodeExtension implements ErrorExtensionProvider {

  private static final String UNAUTHORIZED_CLASS = "io.quarkus.security.UnauthorizedException";

  @Override
  public String getKey() {
    return "errorCode";
  }

  @Override
  public JsonValue mapValueFrom(Throwable t) {
    if (t instanceof FilesGraphQLException f) {
      return Json.createValue(f.getErrorCode().name());
    }
    if (isUnauthenticated(t)) {
      return Json.createValue(ErrorCodes.UNAUTHENTICATED.name());
    }
    return null;
  }

  private static boolean isUnauthenticated(Throwable t) {
    Class<?> c = t.getClass();
    while (c != null && c != Object.class) {
      if (UNAUTHORIZED_CLASS.equals(c.getName())) {
        return true;
      }
      c = c.getSuperclass();
    }
    return false;
  }
}
