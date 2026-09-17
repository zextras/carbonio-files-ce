// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.errors;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import org.junit.jupiter.api.Test;

class FilesGraphQLExceptionTest {

  @Test
  void ofBuildsExceptionWithCorrectErrorCodeAndMessage() {
    FilesGraphQLException ex = FilesGraphQLException.of(ErrorCodes.NODE_NOT_FOUND, "node", "x");

    assertThat(ex.getErrorCode()).isEqualTo(ErrorCodes.NODE_NOT_FOUND);
    assertThat(ex.getMessage()).isEqualTo(ErrorCodes.NODE_NOT_FOUND.name());
    assertThat(ex.getData()).containsEntry("node", "x");
  }

  @Test
  void ofWithMultipleKvPopulatesDataMap() {
    FilesGraphQLException ex =
        FilesGraphQLException.of(ErrorCodes.MISSING_FIELD, "key1", "val1", "key2", 42);

    assertThat(ex.getData()).containsEntry("key1", "val1").containsEntry("key2", 42);
  }

  @Test
  void extensionGetKeyReturnsErrorCode() {
    FilesErrorCodeExtension ext = new FilesErrorCodeExtension();
    assertThat(ext.getKey()).isEqualTo("errorCode");
  }

  @Test
  void extensionMapValueFromReturnsJsonStringForFilesException() {
    FilesErrorCodeExtension ext = new FilesErrorCodeExtension();
    FilesGraphQLException ex = FilesGraphQLException.of(ErrorCodes.NODE_NOT_FOUND);

    JsonValue value = ext.mapValueFrom(ex);

    assertThat(value).isInstanceOf(JsonString.class);
    assertThat(((JsonString) value).getString()).isEqualTo("NODE_NOT_FOUND");
  }

  @Test
  void extensionMapValueFromReturnsNullForPlainException() {
    FilesErrorCodeExtension ext = new FilesErrorCodeExtension();
    JsonValue value = ext.mapValueFrom(new RuntimeException("boom"));

    assertThat(value).isNull();
  }
}
