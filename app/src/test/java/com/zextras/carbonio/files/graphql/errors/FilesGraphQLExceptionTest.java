// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.errors;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.json.JsonNumber;
import jakarta.json.JsonString;
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
  void overQuotaCopyErrorsCarryMainsMessageWithTheNodeIdAndNoExtras() {
    for (FilesGraphQLException ex :
        List.of(
            FilesGraphQLErrors.nodeCopyError(ErrorCodes.OVER_QUOTA_REACHED, "n", 2),
            FilesGraphQLErrors.cloneCopyError(ErrorCodes.OVER_QUOTA_REACHED, "n", 2))) {
      assertThat(ex.getErrorCode()).isEqualTo(ErrorCodes.OVER_QUOTA_REACHED);
      assertThat(ex.getMessage()).isEqualTo("User quota has been reached for user n");
      assertThat(ex.getExtensions()).isEmpty();
    }
  }

  @Test
  void missingFieldHasNoExtraExtensions() {
    FilesGraphQLException ex = FilesGraphQLErrors.missingField();

    assertThat(ex.getMessage()).isEqualTo("Could not find data to retrieve for requested field");
    assertThat(ex.getExtensions()).isEmpty();
  }
}
