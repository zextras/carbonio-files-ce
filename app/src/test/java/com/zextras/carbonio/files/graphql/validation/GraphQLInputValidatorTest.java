// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zextras.carbonio.files.graphql.errors.FilesGraphQLException;
import com.zextras.carbonio.files.graphql.errors.InputValidationException;
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
              assertThat(fge.getErrorCode()).isNull();
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
              assertThat(fge.getErrorCode()).isNull();
            });
  }

  @Test
  void checkNodeIdsThrowsWhenTheListIsNull() {
    assertThatThrownBy(() -> validator.checkNodeIds(null).validate())
        .isInstanceOf(InputValidationException.class)
        .hasMessage("Invalid node ids. The list cannot be null.");
  }

  @Test
  void requireNodeIdsThrowsWhenTheListIsNull() {
    assertThatThrownBy(() -> validator.requireNodeIds(null).validate())
        .isInstanceOf(InputValidationException.class)
        .hasMessage("Invalid node ids. The list cannot be null.");
  }

  @Test
  void requireNodeIdsDoesNotValidateTheFormatOfTheIds() {
    assertThatCode(() -> validator.requireNodeIds(List.of("bad")).validate())
        .doesNotThrowAnyException();
  }

  @Test
  void checkNodeNameThrowsForBlankName() {
    assertThatThrownBy(() -> validator.checkNodeName("   ").validate())
        .isInstanceOf(FilesGraphQLException.class)
        .satisfies(
            e -> {
              FilesGraphQLException fge = (FilesGraphQLException) e;
              assertThat(fge.getErrorCode()).isNull();
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
              assertThat(fge.getErrorCode()).isNull();
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
              assertThat(fge.getErrorCode()).isNull();
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
              assertThat(fge.getErrorCode()).isNull();
            });
  }

  @Test
  void checkEmailThrowsForEmailWithoutTld() {
    assertThatThrownBy(() -> validator.checkEmail("user@nodomain").validate())
        .isInstanceOf(FilesGraphQLException.class)
        .satisfies(
            e -> {
              FilesGraphQLException fge = (FilesGraphQLException) e;
              assertThat(fge.getErrorCode()).isNull();
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
  void checkEmailMatchesCommonsValidator() {
    assertThatCode(() -> validator.checkEmail("o'brien@example.com").validate())
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> validator.checkEmail("x@example..com").validate())
        .isInstanceOf(InputValidationException.class)
        .hasMessage("Invalid Email");
    assertThatThrownBy(() -> validator.checkEmail("user@host.local").validate())
        .isInstanceOf(InputValidationException.class);
  }

  @Test
  void checkLimitPaginationRejectsBelowOneAndOverCap() {
    assertThatCode(() -> validator.checkLimitPagination(1).validate()).doesNotThrowAnyException();
    assertThatCode(() -> validator.checkLimitPagination(50).validate()).doesNotThrowAnyException();
    assertThatThrownBy(() -> validator.checkLimitPagination(0).validate())
        .isInstanceOf(InputValidationException.class)
        .hasMessage("Invalid limit value. The allowed range is between 1 and 50.");
    assertThatThrownBy(() -> validator.checkLimitPagination(-1).validate())
        .isInstanceOf(InputValidationException.class)
        .hasMessage("Invalid limit value. The allowed range is between 1 and 50.");
    assertThatThrownBy(() -> validator.checkLimitPagination(51).validate())
        .isInstanceOf(InputValidationException.class);
  }

  @Test
  void checkPositiveLimitAcceptsNullAndPositiveAndRejectsBelowOne() {
    assertThatCode(() -> validator.checkPositiveLimit(null).validate()).doesNotThrowAnyException();
    assertThatCode(() -> validator.checkPositiveLimit(1).validate()).doesNotThrowAnyException();
    assertThatCode(() -> validator.checkPositiveLimit(500).validate()).doesNotThrowAnyException();
    assertThatThrownBy(() -> validator.checkPositiveLimit(0).validate())
        .isInstanceOf(InputValidationException.class)
        .hasMessage("Invalid limit value. The limit must be greater than 0.");
    assertThatThrownBy(() -> validator.checkPositiveLimit(-1).validate())
        .isInstanceOf(InputValidationException.class)
        .hasMessage("Invalid limit value. The limit must be greater than 0.");
  }

  @Test
  void checkUserIdsRejectsEmptyEntries() {
    assertThatThrownBy(() -> validator.checkUserIds(List.of("ok", "")).validate())
        .isInstanceOf(InputValidationException.class)
        .hasMessage("Invalid user ID. Length cannot be empty");
  }

  @Test
  void multipleErrorsAreAccumulated() {
    assertThatThrownBy(() -> validator.checkNodeId("bad").checkNodeName("   ").validate())
        .isInstanceOf(FilesGraphQLException.class)
        .satisfies(
            e -> {
              FilesGraphQLException fge = (FilesGraphQLException) e;
              assertThat(fge.getMessage()).contains("Invalid node ID");
              assertThat(fge.getMessage()).contains("Invalid node name");
            });
  }
}
