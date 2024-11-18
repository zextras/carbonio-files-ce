// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NodeSQLConditionTest {

  @ParameterizedTest
  @ValueSource(strings = {"node_id", "owner_id", "editor_id", "node_category", "name",
    "updated_timestamp", "creation_timestamp", "size"})
  void givenAValidFieldTheConstructorShouldUpdateTheFieldCorrectly(String field) {
    // Given & When & Then
    Assertions
      .assertThatNoException()
      .isThrownBy(() -> new NodeSQLCondition(field, SortOrder.EQUAL, 2));
  }

  @Test
  void givenAnInvalidFieldTheSetFieldShouldThrownAnIllegalArgumentException() {
    // Given & When & Then
    Assertions
      .assertThatIllegalArgumentException()
      .isThrownBy(() -> new NodeSQLCondition("invalid-field", SortOrder.EQUAL, 2))
      .withMessage("The invalid-field field is invalid");
  }

  @Test
  void givenTheNodeIdFieldTheGetFieldShouldReturnTheFieldWithTheT0TableSuffix() {
    // Given
    SQLCondition sqlCondition = new NodeSQLCondition("node_id", SortOrder.EQUAL, "test");

    // When
    String field = sqlCondition.getField();

    // Then
    Assertions.assertThat(field).isEqualTo("t0.node_id");
  }

  @Test
  void givenTheNameFieldTheGetFieldShouldReturnTheFieldWithTheSqlLowerSyntaxSuffix() {
    // Given
    SQLCondition sqlCondition = new NodeSQLCondition("name", SortOrder.EQUAL, "test");

    // When
    String field = sqlCondition.getField();

    // Then
    Assertions.assertThat(field).isEqualTo("LOWER(name)");
  }
}
