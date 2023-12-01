// SPDX-FileCopyrightText: 2023 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql;

import static graphql.schema.idl.TypeRuntimeWiring.newTypeWiring;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.zextras.carbonio.files.Files.GraphQL.NodePage;
import com.zextras.carbonio.files.Files.GraphQL.Queries;
import com.zextras.carbonio.files.Files.GraphQL.Types;
import com.zextras.carbonio.files.graphql.datafetchers.DateTimeScalar;
import com.zextras.carbonio.files.graphql.datafetchers.PublicNodeDataFetchers;
import graphql.GraphQL;
import graphql.execution.AsyncExecutionStrategy;
import graphql.schema.DataFetcher;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;

/**
 * Setups the GraphQL instance with all the necessary properties. A GraphQL instance is necessary to
 * handle and execute every single request. It knows:
 *
 * <ul>
 *   <li>which is the right schema of reference
 *   <li>how to validate the input
 *   <li>which {@link DataFetcher} need to be called
 * </ul>
 */
@Singleton
public class PublicGraphQLProvider {

  private static final String SCHEMA_URL = "/api/public-schema.graphql";

  private final GraphQL graphQL;
  private final PublicNodeDataFetchers publicNodeDataFetchers;

  @Inject
  public PublicGraphQLProvider(PublicNodeDataFetchers publicNodeDataFetchers) {
    this.publicNodeDataFetchers = publicNodeDataFetchers;
    graphQL = this.setup();
  }

  /**
   * Creates the GraphQL instance building the following properties:
   *
   * <ul>
   *   <li>{@link RuntimeWiring}: it links each interface, query and mutation with the related
   *       {@link DataFetcher}
   *   <li>{@link GraphQLSchema}: the schema definition file is imported from the resources
   *   <li>Execution strategy: how the execution of a request is performed (async or not)
   * </ul>
   *
   * @return {@link GraphQL}
   */
  private GraphQL setup() {
    return GraphQL.newGraphQL(buildSchema(buildWiring()))
        .queryExecutionStrategy(new AsyncExecutionStrategy())
        .build();
  }

  /**
   * Creates a {@link RuntimeWiring} object associating a specific {@link DataFetcher} to one of
   * these GraphQL components:
   *
   * <ul>
   *   <li>Interface
   *   <li>Query
   * </ul>
   *
   * @return a {@link RuntimeWiring} instance.
   */
  private RuntimeWiring buildWiring() {
    return RuntimeWiring.newRuntimeWiring()
        .scalar(new DateTimeScalar().graphQLScalarType())
        .type(
            newTypeWiring(Types.NODE_TYPE).enumValues(publicNodeDataFetchers.getNodeTypeResolver()))
        .type(
            newTypeWiring(Types.NODE_INTERFACE)
                .typeResolver(publicNodeDataFetchers.getNodeInterfaceResolver()))
        .type(
            newTypeWiring(Types.NODE_PAGE)
                .dataFetcher(NodePage.NODES, publicNodeDataFetchers.findNodesByNodePage()))
        .type(
            newTypeWiring("Query")
                .dataFetcher(Queries.GET_NODE, publicNodeDataFetchers.getNodeByPublicLinkId())
                .dataFetcher(Queries.FIND_NODES, publicNodeDataFetchers.findNodes()))
        .build();
  }

  /**
   * Imports the GraphQL schema file (from res folder) and creates the {@link GraphQLSchema} object
   * associating the schema file with the RuntimeWiring object.
   *
   * @param wiring is a {@link RuntimeWiring} that contains the association between the GraphQL
   *     components and their specific {@link DataFetcher}s.
   * @return the {@link GraphQLSchema}.
   */
  private GraphQLSchema buildSchema(RuntimeWiring wiring) {
    // Fetch the content of the GraphQL schema file into an InputStreamReader
    InputStream inputStream = getClass().getResourceAsStream(SCHEMA_URL);

    if (inputStream == null) {
      throw new RuntimeException(String.format("The resource %s does not exist", SCHEMA_URL));
    }

    Reader schema = new InputStreamReader(inputStream);

    // Create the GraphQLSchema object
    return new SchemaGenerator().makeExecutableSchema(new SchemaParser().parse(schema), wiring);
  }

  public GraphQL getGraphQL() {
    return this.graphQL;
  }
}
