package com.zextras.carbonio.files.graphql.dataloaders;

import com.google.inject.Inject;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.exceptions.NodeNotFoundException;
import graphql.schema.DataFetcher;
import java.text.MessageFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;
import org.dataloader.BatchLoader;
import org.dataloader.Try;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This custom {@link BatchLoader} allows to queue a list of node ids that will be used to make a
 * single sql query to fetch in batch the necessary {@link Node}s to populate a GraphQL response.
 * This is useful when a GraphQL request requires to fetch single nodes on different level of the
 * query or when nodes cannot be fetched in batch directly on a single {@link DataFetcher}.
 */
public class NodeBatchLoader implements BatchLoader<String, Try<Node>> {

  private final static Logger logger = LoggerFactory.getLogger(NodeBatchLoader.class);

  private final NodeRepository nodeRepository;

  @Inject
  public NodeBatchLoader(NodeRepository nodeRepository) {
    this.nodeRepository = nodeRepository;
  }

  /**
   * This method will be called by the GraphQL dataloader scheduler when all the
   * {@link DataFetcher}s, necessary to create a GraphQL response, are called.
   * </p>
   * It is only responsible to fetch the {@link Node}s and it does <strong>not</strong>  check if
   * the requester has the read permission on them.
   *
   * @param nodeIds the {@link List} of node ids to fetch
   *
   * @return a {@link CompletionStage} containing a {@link List} of {@link Try<Node>}. The list has
   * as many elements as there are node ids in input. If a node ids does not correspond to a
   * {@link Node} then the method return a {@link Try#failed} containing a
   * {@link NodeNotFoundException}.
   */
  @Override
  public CompletionStage<List<Try<Node>>> load(List<String> nodeIds) {
    return CompletableFuture.supplyAsync(() -> {

      logger.debug(MessageFormat.format("Start fetching nodes in batch: {0}", nodeIds));

      List<Node> nodes = nodeRepository
        .getNodes(nodeIds, Optional.empty())
        .collect(Collectors.toList());

      List<String> nodeIdsFound = nodes
        .stream()
        .map(Node::getId)
        .collect(Collectors.toList());

      List<String> nodeIdsNotFound = nodeIds
        .stream()
        .filter(nodeId -> !nodeIdsFound.contains(nodeId))
        .collect(Collectors.toList());

      List<Try<Node>> results = nodes
        .stream()
        .map(Try::succeeded)
        .collect(Collectors.toList());

      nodeIdsNotFound.forEach(nodeIdNotFound ->
        results.add(Try.failed(new NodeNotFoundException()))
      );

      logger.debug(MessageFormat.format(
        "End fetching nodes in batch.\n - Nodes found: {0}\n - Nodes not found: {1}",
        nodeIdsFound,
        nodeIdsNotFound
      ));

      return results;
    });
  }
}
