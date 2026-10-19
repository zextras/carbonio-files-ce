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
  void checkNodeIdAllowsLocalRoot() {
    assertThatCode(() -> validator.checkNodeId("LOCAL_ROOT").validate()).doesNotThrowAnyException();
  }

  @Test
  void checkNodeIdAllowsTrashRoot() {
    assertThatCode(() -> validator.checkNodeId("TRASH_ROOT").validate()).doesNotThrowAnyException();
  }

  @Test
  void requireNodeIdsDoesNotValidateTheFormatOfTheIds() {
    assertThatCode(() -> validator.requireNodeIds(List.of("bad")).validate())
        .doesNotThrowAnyException();
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
