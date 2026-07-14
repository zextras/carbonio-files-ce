// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.api.utilities;

public class GraphqlCommandBuilder {
  private StringBuilder query;
  private boolean hasArguments = false;

  public GraphqlCommandBuilder(String queryType, String queryMethod) { // findNodes for searching
    query = new StringBuilder(queryType + " { ");
    query.append(queryMethod).append("(");
  }

  public static GraphqlCommandBuilder aQueryBuilder(String queryMethod) {
    return new GraphqlCommandBuilder("query", queryMethod);
  }

  public static GraphqlCommandBuilder aMutationBuilder(String queryMethod) {
    return new GraphqlCommandBuilder("mutation", queryMethod);
  }

  public GraphqlCommandBuilder withBoolean(String key, boolean value) {
    query.append(key).append(": ").append(value).append(", ");
    this.hasArguments = true;
    return this;
  }

  public GraphqlCommandBuilder withString(String key, String value) {
    query.append(key).append(": \\\"").append(value).append("\\\", ");
    this.hasArguments = true;
    return this;
  }

  public GraphqlCommandBuilder withInteger(String key, Integer value) {
    query.append(key).append(": ").append(value).append(", ");
    this.hasArguments = true;
    return this;
  }

  public GraphqlCommandBuilder withEnum(String key, Enum<?> value) {
    query.append(key).append(": ").append(value.toString()).append(", ");
    this.hasArguments = true;
    return this;
  }

  /**
   * Emits a GraphQL enum argument from its raw literal name (unquoted), e.g. {@code
   * withEnumLiteral("sort", "NAME_ASC")}. Use this instead of {@link #withEnum(String, Enum)} when
   * the caller must not depend on a production Java enum type (e.g. the {@code sort} argument is
   * part of the GraphQL API contract, not an internal implementation detail).
   */
  public GraphqlCommandBuilder withEnumLiteral(String key, String enumLiteral) {
    query.append(key).append(": ").append(enumLiteral).append(", ");
    this.hasArguments = true;
    return this;
  }

  public GraphqlCommandBuilder withListOfStrings(String key, String[] values) {
    query.append(key).append(": [");
    for (String value : values) {
      query.append("\\\"").append(value).append("\\\", ");
    }
    query.append("], ");
    this.hasArguments = true;
    return this;
  }

  public GraphqlCommandBuilder withListOfIntegers(String key, int[] values) {
    query.append(key).append(": [");
    for (int value : values) {
      query.append(value).append(", ");
    }
    query.append("], ");
    this.hasArguments = true;
    return this;
  }

  public GraphqlCommandBuilder withWantedResultFormat(String wantedResultFormat) {
    if (wantedResultFormat.isEmpty()){
      if (hasArguments) {
        query.setLength(query.length() - 2);
        query.append(") }");
      } else {
        query.setLength(query.length() - 1);
        query.append(" }");
      }
    } else {
      query.setLength(query.length() - 2);
      query.append(") ").append(wantedResultFormat).append(" }");
    }
    return this;
  }

  public String build() {
    return query.toString();
  }
}
