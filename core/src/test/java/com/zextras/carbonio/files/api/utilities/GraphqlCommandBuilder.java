// SPDX-FileCopyrightText: 2023 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.api.utilities;

public class GraphqlCommandBuilder {
  private StringBuilder query;

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
    return this;
  }

  public GraphqlCommandBuilder withString(String key, String value) {
    query.append(key).append(": \\\"").append(value).append("\\\", ");
    return this;
  }

  public GraphqlCommandBuilder withInteger(String key, Integer value) {
    query.append(key).append(": ").append(value).append(", ");
    return this;
  }

  public GraphqlCommandBuilder withEnum(String key, Enum<?> value) {
    query.append(key).append(": ").append(value.toString()).append(", ");
    return this;
  }

  public GraphqlCommandBuilder withListOfStrings(String key, String[] values) {
    query.append(key).append(": [");
    for (String value : values) {
      query.append("\\\"").append(value).append("\\\", ");
    }
    query.append("], ");
    return this;
  }

  public String build(String wantedResultFormat) { // { nodes { id name }, page_token for searching
    query.setLength(query.length() - 2);
    query.append(") { ").append(wantedResultFormat).append(" } }");
    return query.toString();
  }
}
