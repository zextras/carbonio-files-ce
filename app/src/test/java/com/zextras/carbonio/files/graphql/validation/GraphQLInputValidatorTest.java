// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zextras.carbonio.files.graphql.errors.ErrorCodes;
import com.zextras.carbonio.files.graphql.errors.FilesGraphQLException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GraphQLInputValidatorTest {

  private GraphQLInputValidator validator;

  @BeforeEach
  void setUp() {
    validator = new GraphQLInputValidator();
  }

  @Test
  void validateDoesNotThrowWhenNoErrors() {
    assertThatCode(() -> validator.checkNodeId("LOCAL_ROOT").checkUserId("some-user-id").validate())
        .doesNotThrowAnyException();
  }

  @Test
  void checkNodeIdThrowsForInvalidLength() {
    assertThatThrownBy(() -> validator.checkNodeId("short-id").validate())
        .isInstanceOf(FilesGraphQLException.class)
        .satisfies(
            e -> {
              FilesGraphQLException fge = (FilesGraphQLException) e;
              assertThat(fge.getErrorCode()).isEqualTo(ErrorCodes.MISSING_FIELD);
              assertThat(fge.getMessage())
                  .isEqualTo("Invalid node ID: \"short-id\". Length must be 36 characters");
            });
  }

  @Test
  void checkNodeIdAllowsLocalRoot() {
    assertThatCode(() -> validator.checkNodeId("LOCAL_ROOT").validate()).doesNotThrowAnyException();
  }

  @Test
  void checkNodeIdAllowsTrashRoot() {
    assertThatCode(() -> validator.checkNodeId("TRASH_ROOT").validate()).doesNotThrowAnyException();
  }

  @Test
  void checkNodeIdAllows36CharId() {
    assertThatCode(() -> validator.checkNodeId("12345678-1234-1234-1234-123456789012").validate())
        .doesNotThrowAnyException();
  }

  @Test
  void checkNodeIdAllowsNull() {
    assertThatCode(() -> validator.checkNodeId(null).validate()).doesNotThrowAnyException();
  }

  @Test
  void checkNodeIdsThrowsWhenOneIsInvalid() {
    assertThatThrownBy(
            () ->
                validator
                    .checkNodeIds(List.of("12345678-1234-1234-1234-123456789012", "bad"))
                    .validate())
        .isInstanceOf(FilesGraphQLException.class)
        .satisfies(
            e -> {
              FilesGraphQLException fge = (FilesGraphQLException) e;
              assertThat(fge.getErrorCode()).isEqualTo(ErrorCodes.MISSING_FIELD);
            });
  }

  @Test
  void checkNodeNameThrowsForBlankName() {
    assertThatThrownBy(() -> validator.checkNodeName("   ").validate())
        .isInstanceOf(FilesGraphQLException.class)
        .satisfies(
            e -> {
              FilesGraphQLException fge = (FilesGraphQLException) e;
              assertThat(fge.getErrorCode()).isEqualTo(ErrorCodes.MISSING_FIELD);
              assertThat(fge.getMessage())
                  .isEqualTo(
                      "Invalid node name. The name cannot be empty, longer than 1024 characters,"
                          + " nor be composed only by blank spaces.");
            });
  }

  @Test
  void checkNodeNameAllowsNull() {
    assertThatCode(() -> validator.checkNodeName(null).validate()).doesNotThrowAnyException();
  }

  @Test
  void checkNodeNameThrowsForTooLongName() {
    String tooLong = "a".repeat(1025);
    assertThatThrownBy(() -> validator.checkNodeName(tooLong).validate())
        .isInstanceOf(FilesGraphQLException.class);
  }

  @Test
  void checkUserIdThrowsForEmptyUserId() {
    assertThatThrownBy(() -> validator.checkUserId("").validate())
        .isInstanceOf(FilesGraphQLException.class)
        .satisfies(
            e -> {
              FilesGraphQLException fge = (FilesGraphQLException) e;
              assertThat(fge.getErrorCode()).isEqualTo(ErrorCodes.MISSING_FIELD);
            });
  }

  @Test
  void checkUserIdAllowsNull() {
    assertThatCode(() -> validator.checkUserId(null).validate()).doesNotThrowAnyException();
  }

  @Test
  void checkLinkIdThrowsForWrongLength() {
    assertThatThrownBy(() -> validator.checkLinkId("short").validate())
        .isInstanceOf(FilesGraphQLException.class);
  }

  @Test
  void checkLinkDescriptionThrowsForTooLong() {
    String tooLong = "a".repeat(301);
    assertThatThrownBy(() -> validator.checkLinkDescription(tooLong).validate())
        .isInstanceOf(FilesGraphQLException.class)
        .satisfies(
            e -> {
              FilesGraphQLException fge = (FilesGraphQLException) e;
              assertThat(fge.getErrorCode()).isEqualTo(ErrorCodes.MISSING_FIELD);
            });
  }

  @Test
  void checkLinkAccessCodeThrowsForTooShort() {
    assertThatThrownBy(() -> validator.checkLinkAccessCode("short").validate())
        .isInstanceOf(FilesGraphQLException.class);
  }

  @Test
  void checkLinkAccessCodeAllowsNull() {
    assertThatCode(() -> validator.checkLinkAccessCode(null).validate()).doesNotThrowAnyException();
  }

  @Test
  void checkLinkAccessCodeAllowsEmpty() {
    assertThatCode(() -> validator.checkLinkAccessCode("").validate()).doesNotThrowAnyException();
  }

  @Test
  void checkEmailThrowsForInvalidEmail() {
    assertThatThrownBy(() -> validator.checkEmail("not-an-email").validate())
        .isInstanceOf(FilesGraphQLException.class)
        .satisfies(
            e -> {
              FilesGraphQLException fge = (FilesGraphQLException) e;
              assertThat(fge.getErrorCode()).isEqualTo(ErrorCodes.MISSING_FIELD);
            });
  }

  @Test
  void checkEmailThrowsForEmailWithoutTld() {
    // Inline RFC-lite regex (commons-validator removed) still requires a dotted domain with a TLD.
    assertThatThrownBy(() -> validator.checkEmail("user@nodomain").validate())
        .isInstanceOf(FilesGraphQLException.class)
        .satisfies(
            e -> {
              FilesGraphQLException fge = (FilesGraphQLException) e;
              assertThat(fge.getErrorCode()).isEqualTo(ErrorCodes.MISSING_FIELD);
            });
  }

  @Test
  void checkEmailPassesForValidEmail() {
    assertThatCode(() -> validator.checkEmail("user@example.com").validate())
        .doesNotThrowAnyException();
    assertThatCode(() -> validator.checkEmail("first.last+tag@sub.example.co").validate())
        .doesNotThrowAnyException();
  }

  @Test
  void multipleErrorsAreAccumulated() {
    assertThatThrownBy(() -> validator.checkNodeId("bad").checkNodeName("   ").validate())
        .isInstanceOf(FilesGraphQLException.class)
        .satisfies(
            e -> {
              FilesGraphQLException fge = (FilesGraphQLException) e;
              String errors = (String) fge.getData().get("errors");
              assertThat(errors).contains("Invalid node ID");
              assertThat(errors).contains("Invalid node name");
            });
  }
}
