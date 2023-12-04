// SPDX-FileCopyrightText: 2023 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.datafetchers;

import com.google.inject.Inject;
import com.zextras.carbonio.files.Files;
import com.zextras.carbonio.files.Files.GraphQL.InputParameters.FindNodes;
import com.zextras.carbonio.files.Files.GraphQL.InputParameters.GetPublicNode;
import com.zextras.carbonio.files.Files.GraphQL.NodePage;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.repositories.interfaces.LinkRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.graphql.errors.GraphQLResultErrors;
import com.zextras.carbonio.files.graphql.types.PublicNode;
import graphql.execution.DataFetcherResult;
import graphql.execution.ResultPath;
import graphql.schema.DataFetcher;
import graphql.schema.GraphQLObjectType;
import graphql.schema.TypeResolver;
import graphql.schema.idl.EnumValuesProvider;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import org.apache.commons.lang3.tuple.ImmutablePair;

public class PublicNodeDataFetchers {

  private final NodeRepository nodeRepository;
  private final LinkRepository linkRepository;

  @Inject
  public PublicNodeDataFetchers(NodeRepository nodeRepository, LinkRepository linkRepository) {
    this.nodeRepository = nodeRepository;
    this.linkRepository = linkRepository;
  }

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

  public DataFetcher<CompletableFuture<DataFetcherResult<Map<String, Object>>>>
      getNodeByPublicLinkId() {
    return environment ->
        CompletableFuture.supplyAsync(
            () -> {
              ResultPath path = environment.getExecutionStepInfo().getPath();
              String publicLinkId = environment.getArgument(GetPublicNode.NODE_LINK_ID);
              return linkRepository
                  .getLinkByNotExpiredPublicId(publicLinkId)
                  .map(
                      publicLink ->
                          nodeRepository
                              .getNode(publicLink.getNodeId())
                              .map(
                                  node ->
                                      DataFetcherResult.<Map<String, Object>>newResult()
                                          .data(PublicNode.createFromNode(node).convertToMap())
                                          .build())
                              .orElse(
                                  DataFetcherResult.<Map<String, Object>>newResult()
                                      .error(
                                          GraphQLResultErrors.nodeNotFound(
                                              publicLink.getNodeId(), path))
                                      .build()))
                  .orElse(
                      DataFetcherResult.<Map<String, Object>>newResult()
                          .error(GraphQLResultErrors.linkNotFound(publicLinkId, path))
                          .build());
            });
  }

  public DataFetcher<CompletableFuture<DataFetcherResult<Map<String, String>>>> findNodes() {
    return environment ->
        CompletableFuture.supplyAsync(
            () -> {
              ResultPath path = environment.getExecutionStepInfo().getPath();
              String folderId = environment.getArgument(FindNodes.FOLDER_ID);
              Integer limit = environment.getArgument(FindNodes.LIMIT);
              String pageToken = environment.getArgument(FindNodes.PAGE_TOKEN);

              Optional<Node> optFolder = nodeRepository.getNode(folderId);

              if (optFolder.isPresent()
                  && linkRepository.hasNodeANotExpiredPublicLink(optFolder.get())) {
                ImmutablePair<List<Node>, String> findResult =
                    nodeRepository.publicFindNodes(folderId, limit, pageToken);

                Map<String, String> nextPageToken = new HashMap<>();
                nextPageToken.put(NodePage.PAGE_TOKEN, findResult.getRight());

                return new DataFetcherResult.Builder<Map<String, String>>()
                    .data(nextPageToken)
                    .localContext(Map.of(NodePage.NODES, findResult.getLeft()))
                    .build();
              }

              return new DataFetcherResult.Builder<Map<String, String>>()
                  .error(GraphQLResultErrors.nodeNotFound(folderId, path))
                  .build();
            });
  }

  public DataFetcher<CompletableFuture<List<DataFetcherResult<Map<String, Object>>>>>
      findNodesByNodePage() {
    return environment ->
        CompletableFuture.supplyAsync(
            () -> {
              Optional<Map<String, List<Node>>> optLocalContext =
                  Optional.ofNullable(environment.getLocalContext());

              if (optLocalContext.isPresent()) {
                final Stream<Node> nodes = optLocalContext.get().get(NodePage.NODES).stream();
                return nodes
                    .map(
                        node ->
                            DataFetcherResult.<Map<String, Object>>newResult()
                                .data(PublicNode.createFromNode(node).convertToMap())
                                .build())
                    .toList();
              }

              return Collections.emptyList();
            });
  }
}
