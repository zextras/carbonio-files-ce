// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities;

// represents a sql compare expression, for example (A < B OR B > C)
public class CompareExpression {

  String expression;
  boolean shouldAddPharentesis;

  private CompareExpression(String expression) {
    this.expression = expression;
    shouldAddPharentesis = false;
  }

  public static CompareExpression aCompareExpression(String key, String operator, String value) {
    return new CompareExpression(key + " " + operator + " " + value);
  }

  public CompareExpression or(CompareExpression compareExpression) {
    shouldAddPharentesis = true;
    this.expression = this.expression + " OR " + compareExpression.toExpression();
    return this;
  }

  public CompareExpression and(CompareExpression compareExpression) {
    shouldAddPharentesis = true;
    this.expression = this.expression + " AND " + compareExpression.toExpression();
    return this;
  }

  public String toExpression() {
    return shouldAddPharentesis ? "(" + expression + ")" : expression;
  }
}
