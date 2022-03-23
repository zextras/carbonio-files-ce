// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.datafetchers;

import com.zextras.carbonio.files.graphql.GraphQLProvider;
import graphql.language.IntValue;
import graphql.language.StringValue;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.GraphQLScalarType;

/**
 * {@inheritDoc}
 * <p>
 * This implementation aims to serialize and parse the scalar type Date into a timestamp EPOCH
 * format.
 * <p>
 * It is necessary because, by default, there is not a GraphQL primitive type representing a {@link
 * Long}.
 * <p>
 * It allows to create a {@link GraphQLScalarType} of Date that can be bound during the GraphQL
 * wiring (see {@link GraphQLProvider}).
 */
public class DateTimeScalar implements Coercing<Long, Long> {

  @Override
  public Long serialize(Object dataFetcherResult) {
    return Long.valueOf(dataFetcherResult.toString());
  }

  @Override
  public Long parseValue(Object input) throws CoercingParseValueException {
    if (input instanceof Long) {
      return (Long) input;
    }

    // This check is needed because clients sends 0 as an integer
    if (input instanceof Integer) {
      return ((Integer) input).longValue();
    }

    throw new CoercingParseValueException("Unable to parse the input value as a date");
  }

  @Override
  public Long parseLiteral(Object input) throws CoercingParseLiteralException {
    if (input instanceof StringValue) {
      return Long.valueOf(((StringValue) input).getValue());
    }

    if (input instanceof IntValue) {
      return ((IntValue) input).getValue().longValue();
    }

    throw new CoercingParseLiteralException("Unable to parse the input value as a date");
  }

  /**
   * @return a {@link GraphQLScalarType} of the scalar type Date.
   */
  public GraphQLScalarType graphQLScalarType() {
    return GraphQLScalarType.newScalar()
      .name("DateTime")
      .description("A custom scalar representing a date in a timestamp format")
      .coercing(this)
      .build();
  }
}

