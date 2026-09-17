// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.errors;

import io.smallrye.graphql.api.ErrorExtensionProvider;
import jakarta.json.Json;
import jakarta.json.JsonValue;

public class FilesErrorCodeExtension implements ErrorExtensionProvider {

  @Override
  public String getKey() {
    return "errorCode";
  }

  @Override
  public JsonValue mapValueFrom(Throwable t) {
    if (t instanceof FilesGraphQLException f) {
      return Json.createValue(f.getErrorCode().name());
    }
    return null;
  }
}
