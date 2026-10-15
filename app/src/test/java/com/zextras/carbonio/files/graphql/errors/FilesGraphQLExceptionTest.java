// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.errors;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.security.UnauthorizedException;
import jakarta.json.JsonNumber;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import java.util.List;
import org.junit.jupiter.api.Test;

class FilesGraphQLExceptionTest {

  @Test
  void nodeNotFoundMirrorsLegacyMessageAndExtensions() {
    FilesGraphQLException ex = FilesGraphQLErrors.nodeNotFound("x");

    assertThat(ex.getErrorCode()).isEqualTo(ErrorCodes.NODE_NOT_FOUND);
    assertThat(ex.getMessage()).isEqualTo("Could not find node with id x");
    assertThat(ex.getExtensions()).containsOnlyKeys("nodeId");
    assertThat(((JsonString) ex.getExtensions().get("nodeId")).getString()).isEqualTo("x");
  }

  @Test
  void duplicateNodeCarriesBothIds() {
    FilesGraphQLException ex = FilesGraphQLErrors.duplicateNode("n", "d");

    assertThat(ex.getMessage())
        .isEqualTo("Trying to create a duplicate for the node n in destination folder d");
    assertThat(ex.getExtensions()).containsOnlyKeys("nodeId", "destinationFolderId");
  }

  @Test
  void fileVersionNotFoundEmitsVersionAsJsonNumber() {
    FilesGraphQLException ex = FilesGraphQLErrors.fileVersionNotFound("n", 3);

    assertThat(ex.getMessage()).isEqualTo("Could not find version: 3 for node with id n");
    assertThat(ex.getExtensions()).containsOnlyKeys("nodeId", "fileVersion");
    assertThat(((JsonNumber) ex.getExtensions().get("fileVersion")).intValue()).isEqualTo(3);
  }

  @Test
  void missingFieldHasNoExtraExtensions() {
    FilesGraphQLException ex = FilesGraphQLErrors.missingField();

    assertThat(ex.getMessage()).isEqualTo("Could not find data to retrieve for requested field");
    assertThat(ex.getExtensions()).isEmpty();
  }

  @Test
  void inputValidationExceptionHasNoErrorCodeAndNoExtensions() {
    InputValidationException ex = new InputValidationException("Invalid Email");

    assertThat(ex.getErrorCode()).isNull();
    assertThat(ex.getExtensions()).isEmpty();
  }

  @Test
  void withPartialResultsKeepsCodeMessageAndExtensions() {
    FilesGraphQLException ex = FilesGraphQLErrors.linkNotFound("l").withPartialResults(List.of(1));

    assertThat(ex.getErrorCode()).isEqualTo(ErrorCodes.LINK_NOT_FOUND);
    assertThat(ex.getMessage()).isEqualTo("Could not find link with id l");
    assertThat(ex.getPartialResults()).isEqualTo(List.of(1));
    assertThat(ex.getExtensions()).containsOnlyKeys("linkId");
  }

  @Test
  void extensionGetKeyReturnsErrorCode() {
    FilesErrorCodeExtension ext = new FilesErrorCodeExtension();
    assertThat(ext.getKey()).isEqualTo("errorCode");
  }

  @Test
  void extensionMapValueFromReturnsJsonStringForFilesException() {
    FilesErrorCodeExtension ext = new FilesErrorCodeExtension();
    FilesGraphQLException ex = FilesGraphQLErrors.nodeNotFound("x");

    JsonValue value = ext.mapValueFrom(ex);

    assertThat(value).isInstanceOf(JsonString.class);
    assertThat(((JsonString) value).getString()).isEqualTo("NODE_NOT_FOUND");
  }

  @Test
  void extensionMapValueFromReturnsUnauthenticatedForUnauthorizedException() {
    FilesErrorCodeExtension ext = new FilesErrorCodeExtension();
    JsonValue value = ext.mapValueFrom(new UnauthorizedException());

    assertThat(value).isInstanceOf(JsonString.class);
    assertThat(((JsonString) value).getString()).isEqualTo("UNAUTHENTICATED");
  }

  @Test
  void extensionMapValueFromReturnsNullForPlainException() {
    FilesErrorCodeExtension ext = new FilesErrorCodeExtension();
    JsonValue value = ext.mapValueFrom(new RuntimeException("boom"));

    assertThat(value).isNull();
  }
}
