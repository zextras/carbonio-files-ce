// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities;


public class CompareExpression {

  String expression;

  public CompareExpression(String key, String operator, String value){
    this.expression = key + " " + operator + " " + value;
  }

  public CompareExpression or(CompareExpression compareExpression){
    this.expression = "(" + this.expression + " OR " + compareExpression.getExpression() +")";
    return this;
  }

  public CompareExpression and(CompareExpression compareExpression){
    this.expression = "(" + this.expression + " AND " + compareExpression.getExpression() +")";
    return this;
  }

  public String getExpression(){
    return expression;
  }

}
