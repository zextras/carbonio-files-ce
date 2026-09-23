// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.errors;

import java.util.HashMap;
import java.util.Map;
import org.eclipse.microprofile.graphql.GraphQLException;

public class FilesGraphQLException extends GraphQLException {
  private final ErrorCodes errorCode;
  private final Map<String, Object> data;

  public FilesGraphQLException(ErrorCodes errorCode, String message, Map<String, Object> data) {
    super(message);
    this.errorCode = errorCode;
    this.data = data;
  }

  public FilesGraphQLException(
      ErrorCodes errorCode, String message, Object partialResults, Map<String, Object> data) {
    super(message, partialResults);
    this.errorCode = errorCode;
    this.data = data;
  }

  public ErrorCodes getErrorCode() {
    return errorCode;
  }

  public Map<String, Object> getData() {
    return data;
  }

  public static FilesGraphQLException of(ErrorCodes code, Object... kv) {
    Map<String, Object> d = new HashMap<>();
    for (int i = 0; i + 1 < kv.length; i += 2) d.put(String.valueOf(kv[i]), kv[i + 1]);
    return new FilesGraphQLException(code, code.name(), d);
  }
}
