// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.errors;

import graphql.ErrorClassification;
import graphql.GraphQLError;
import graphql.language.SourceLocation;
import jakarta.json.Json;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.graphql.GraphQLException;

public class FilesGraphQLException extends GraphQLException implements GraphQLError {
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

  public FilesGraphQLException withPartialResults(Object partialResults) {
    return new FilesGraphQLException(errorCode, getMessage(), partialResults, data);
  }

  @Override
  public List<SourceLocation> getLocations() {
    return null;
  }

  @Override
  public ErrorClassification getErrorType() {
    return null;
  }

  @Override
  public Map<String, Object> getExtensions() {
    Map<String, Object> extensions = new LinkedHashMap<>();
    data.forEach(
        (key, value) -> {
          if (value instanceof Integer i) {
            extensions.put(key, Json.createValue(i));
          } else if (value != null) {
            extensions.put(key, Json.createValue(value.toString()));
          }
        });
    return extensions;
  }
}
