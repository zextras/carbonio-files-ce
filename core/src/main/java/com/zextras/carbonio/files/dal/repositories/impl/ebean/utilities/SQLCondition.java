// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;
import java.util.stream.Stream;

@JsonTypeName("condition")
class SQLCondition implements SQLPart {

  private String field;
  private final SortOrder operator;
  private final Object parameter;

  protected SQLCondition(
    @JsonProperty("field") String field,
    @JsonProperty("operator") SortOrder operator,
    @JsonProperty("parameter") Object parameter) {

    this.field = field;
    this.operator = operator;
    this.parameter = parameter;
  }

  public String getField() {
    return field;
  }

  public void setField(String field) {
    this.field = field;
  }

  public SortOrder getOperator() {
    return operator;
  }

  public Object getParameter() {
    return parameter;
  }

  @JsonIgnore
  public String toExpression() {
    return String.format("%s %s ?", field, operator.getSymbol());
  }

  @JsonIgnore
  public Stream<Object> getParameters() {
    return Stream.of(getParameter());
  }
}
