// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@JsonTypeName("expression")
public class SQLExpression implements SQLPart {

  private final LogicalOperator logicalOperator;
  @JsonProperty("parts")
  private final List<SQLPart> sqlParts;

  @JsonCreator
  public SQLExpression(
    @JsonProperty("logicalOperator") LogicalOperator logicalOperator,
    @JsonProperty("parts") List<SQLPart> sqlParts) {

    this.logicalOperator = logicalOperator;
    this.sqlParts = sqlParts;
  }

  public LogicalOperator getLogicalOperator() {
    return logicalOperator;
  }

  public List<SQLPart> getSqlParts() {
    return sqlParts;
  }

  public static SQLExpression and(List<SQLPart> sqlParts) {
    return new SQLExpression(LogicalOperator.AND, sqlParts);
  }

  public static SQLExpression or(List<SQLPart> sqlParts) {
    return new SQLExpression(LogicalOperator.OR, sqlParts);
  }

  @JsonIgnore
  public String toExpression() {
    String logicalOperatorName = " " + logicalOperator.name() + " ";
    return sqlParts
      .stream()
      .map(SQLPart::toExpression)
      .collect(Collectors.joining(logicalOperatorName, "(", ")"));
  }

  @JsonIgnore
  public Stream<Object> getParameters() {
    return sqlParts.stream().flatMap(SQLPart::getParameters);
  }

}
