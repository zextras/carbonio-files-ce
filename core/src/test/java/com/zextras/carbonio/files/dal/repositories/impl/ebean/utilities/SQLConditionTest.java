// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class SQLConditionTest {

  @Test
  void givenAFieldAnOperatorAndAParameterTheConstructorShouldCreateASQLCondition() {
    // Given
    final String field = "field";
    final SortOrder operator = SortOrder.EQUAL;
    final int parameter = 2;

    // When
    SQLCondition sqlCondition = new SQLCondition(field, operator, parameter);

    // Then
    Assertions.assertThat(sqlCondition.getField()).isEqualTo("field");
    Assertions.assertThat(sqlCondition.getOperator()).isEqualTo(SortOrder.EQUAL);
    Assertions.assertThat(sqlCondition.getParameter()).isEqualTo(2);
  }

  @ParameterizedTest()
  @EnumSource(SortOrder.class)
  void givenASQLConditionTheToExpressionShouldReturnTheConditionWithQuestionMarkParameter(
    SortOrder operator) {
    // Given
    final String field = "field";
    final int parameter = 2;

    SQLCondition sqlCondition = new SQLCondition(field, operator, parameter);

    // When
    String sqlExpression = sqlCondition.toExpression();

    // Then
    Assertions.assertThat(sqlExpression).isEqualTo("field " + operator.getSymbol() + " ?");
  }
}
