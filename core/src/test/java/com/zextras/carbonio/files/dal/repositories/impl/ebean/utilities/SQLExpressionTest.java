// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zextras.carbonio.files.Files.Db.Node;
import java.util.List;
import java.util.stream.Stream;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class SQLExpressionTest {

  private SQLExpression createAComplexSQLExpression() {
    return SQLExpression.or(
      List.of(
        new NodeSQLCondition(Node.CATEGORY, SortOrder.ASCENDING, 1),
        SQLExpression.and(
          List.of(
            new NodeSQLCondition(Node.CATEGORY, SortOrder.EQUAL, 1),
            new NodeSQLCondition(Node.NAME, SortOrder.ASCENDING, "f.txt"))
        ),
        SQLExpression.and(
          List.of(
            new NodeSQLCondition(Node.CATEGORY, SortOrder.EQUAL, 1),
            new NodeSQLCondition(Node.NAME, SortOrder.EQUAL, "f.txt"),
            new NodeSQLCondition(Node.ID, SortOrder.ASCENDING, "00000000"))
        )
      )
    );
  }

  @Test
  void givenALogicalOperatorAndAListOfSqlPartsTheConstructorShouldCreateASqlExpression() {
    // Given
    final LogicalOperator logicalOperator = LogicalOperator.AND;
    final List<SQLPart> sqlParts = List.of(
      new SQLCondition("field", SortOrder.ASCENDING, "test"),
      new SQLCondition("field2", SortOrder.EQUAL, "test2")
    );

    // When
    SQLExpression sqlExpression = new SQLExpression(logicalOperator, sqlParts);

    // Then
    Assertions.assertThat(sqlExpression.getLogicalOperator()).isEqualTo(LogicalOperator.AND);
    Assertions.assertThat(sqlExpression.getSqlParts()).hasSize(2);

    SQLCondition sqlCondition1 = (SQLCondition) sqlExpression.getSqlParts().get(0);
    Assertions.assertThat(sqlCondition1.getField()).isEqualTo("field");
    Assertions.assertThat(sqlCondition1.getOperator()).isEqualTo(SortOrder.ASCENDING);
    Assertions.assertThat(sqlCondition1.getParameter()).isEqualTo("test");

    SQLCondition sqlCondition2 = (SQLCondition) sqlExpression.getSqlParts().get(1);
    Assertions.assertThat(sqlCondition2.getField()).isEqualTo("field2");
    Assertions.assertThat(sqlCondition2.getOperator()).isEqualTo(SortOrder.EQUAL);
    Assertions.assertThat(sqlCondition2.getParameter()).isEqualTo("test2");
  }

  @Test
  void givenAListOfSqlPartsTheOrMethodShouldCreateASqlExpressionWithTheOrOperator() {
    // Given
    final List<SQLPart> sqlParts = List.of(new SQLCondition("field", SortOrder.ASCENDING, "test"));

    // When
    SQLExpression sqlExpression = SQLExpression.or(sqlParts);

    // Then
    Assertions.assertThat(sqlExpression.getLogicalOperator()).isEqualTo(LogicalOperator.OR);
    Assertions.assertThat(sqlExpression.getSqlParts()).hasSize(1);
  }

  @Test
  void givenAListOfSqlPartsTheAndMethodShouldCreateASqlExpressionWithTheAndOperator() {
    // Given
    final List<SQLPart> sqlParts = List.of(new SQLCondition("field", SortOrder.ASCENDING, "test"));

    // When
    SQLExpression sqlExpression = SQLExpression.and(sqlParts);

    // Then
    Assertions.assertThat(sqlExpression.getLogicalOperator()).isEqualTo(LogicalOperator.AND);
    Assertions.assertThat(sqlExpression.getSqlParts()).hasSize(1);
  }

  @Test
  void givenAnSqlExpressionTheJsonSerializationAndDeserializationWorksCorrectly() throws Throwable {
    // Given & When
    ObjectMapper mapper = new ObjectMapper();

    SQLExpression sqlExpression = SQLExpression.or(
      List.of(
        new SQLCondition("field1", SortOrder.ASCENDING, 1),
        SQLExpression.and(
          List.of(
            new SQLCondition("field3", SortOrder.DESCENDING, "test"),
            new SQLCondition("field4", SortOrder.EQUAL, (short) 2)
          )
        )
      )
    );

    // Then
    Assertions.assertThatNoException().isThrownBy(() -> {
        // Serialize
        String serializedExpression = mapper.writeValueAsString(sqlExpression);
        // Deserialize
        mapper.readValue(serializedExpression, SQLExpression.class);
      }
    );
  }

  @Test
  void givenAComplexSqlExpressionTheToExpressionShouldReturnAStringRepresentingTheExpression() {
    // Given
    SQLExpression sqlExpression = createAComplexSQLExpression();

    // When
    String sqlExpressionAsString = sqlExpression.toExpression();

    // Then
    Assertions
      .assertThat(sqlExpressionAsString)
      .isEqualTo("(node_category > ? OR (node_category = ? AND LOWER(name) > ?) OR (node_category"
        + " = ? AND LOWER(name) = ? AND t0.node_id > ?))");
  }

  @Test
  void givenAComplexSqlExpressionTheGetParametersShouldReturnAStreamOfAllTheConditionParameters() {
    // Given
    SQLExpression sqlExpression = createAComplexSQLExpression();

    // When
    Stream<Object> sqlParameters = sqlExpression.getParameters();

    // Then
    Assertions.assertThat(sqlParameters).containsExactly(1, 1, "f.txt", 1, "f.txt", "00000000");
  }
}
