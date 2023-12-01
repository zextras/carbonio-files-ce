// SPDX-FileCopyrightText: 2023 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.datafetchers;

import com.zextras.carbonio.files.Files;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import graphql.execution.DataFetcherResult;
import graphql.schema.DataFetcher;
import graphql.schema.GraphQLObjectType;
import graphql.schema.TypeResolver;
import graphql.schema.idl.EnumValuesProvider;
import java.util.Collections;
import java.util.Map;

public class PublicNodeDataFetchers {

  /**
   * This {@link TypeResolver} checks which type of Node was requested. The type can be a File or a
   * Folder.
   *
   * @return a {@link TypeResolver} that resolve the type of Node requested.
   */
  public TypeResolver getNodeInterfaceResolver() {
    return environment -> {
      Map<String, Object> result = environment.getObject();
      return (result.get(Files.GraphQL.Node.TYPE).equals(NodeType.FOLDER)
              || result.get(Files.GraphQL.Node.TYPE).equals(NodeType.ROOT))
          ? (GraphQLObjectType) environment.getSchema().getType(Files.GraphQL.Types.FOLDER)
          : (GraphQLObjectType) environment.getSchema().getType(Files.GraphQL.Types.FILE);
    };
  }

  public EnumValuesProvider getNodeTypeResolver() {
    return NodeType::valueOf;
  }

  public DataFetcher<DataFetcherResult<Map<String, String>>> getNodeByPublicLinkId() {
    return environment ->
        DataFetcherResult.<Map<String, String>>newResult().data(Collections.emptyMap()).build();
  }

  public DataFetcher<DataFetcherResult<Map<String, String>>> findNodes() {
    return environment ->
        DataFetcherResult.<Map<String, String>>newResult().data(Collections.emptyMap()).build();
  }

  public DataFetcher<DataFetcherResult<Map<String, String>>> findNodesByNodePage() {
    return environment ->
        DataFetcherResult.<Map<String, String>>newResult().data(Collections.emptyMap()).build();
  }
}
