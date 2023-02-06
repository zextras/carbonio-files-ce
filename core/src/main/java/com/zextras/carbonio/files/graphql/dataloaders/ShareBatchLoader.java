// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.dataloaders;

import com.google.inject.Inject;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.Share;
import com.zextras.carbonio.files.dal.repositories.interfaces.ShareRepository;
import graphql.schema.DataFetcher;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;
import org.dataloader.BatchLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This custom {@link BatchLoader} allows to queue a list of node ids that will be used to make a
 * single sql query to fetch in batch all the {@link Share}s to populate a GraphQL response. This is
 * useful when a GraphQL request requires to fetch single shares on different level of the query or
 * when shares cannot be fetched in batch directly on a single {@link DataFetcher}.
 */
public class ShareBatchLoader implements BatchLoader<String, List<Share>> {

  private static final Logger logger = LoggerFactory.getLogger(ShareBatchLoader.class);

  private final ShareRepository shareRepository;

  @Inject
  public ShareBatchLoader(ShareRepository shareRepository) {
    this.shareRepository = shareRepository;
  }

  /**
   * This method will be invoked by the GraphQL dataloader scheduler when all the
   * {@link DataFetcher}s, necessary to create a GraphQL response, are called.
   * </p>
   * It is only responsible to fetch the {@link Share}s and it does <strong>not</strong> check if
   * the requester has the read permission on the {@link Node}s.
   *
   * @param nodeIds the {@link List} of node ids. For each one of them it retrieves all the related
   * shares.
   *
   * @return a {@link CompletionStage} containing a {@link List} of {@link List<Share>}. The list
   * has as many elements as there are node ids in input. If a node does not have a share, then it
   * will be associated to an empty list.
   */
  @Override
  public CompletionStage<List<List<Share>>> load(List<String> nodeIds) {
    return CompletableFuture.supplyAsync(() -> {

      logger.debug(MessageFormat.format(
        "Start fetching shares in batch for the following nodes: {0}",
        nodeIds
      ));

      List<Share> shares = shareRepository.getShares(nodeIds);

      List<List<Share>> results = new ArrayList<>();

      // It populates the results: for each node ids it associates all the related shares.
      // If a node does not have a share then the .collect(Collectors.toList()) generates
      // an empty List: this is why is not necessary using a Try.
      nodeIds.forEach(nodeId ->
        results.add(
          shares
            .stream()
            .filter(share -> nodeId.equals(share.getNodeId()))
            .collect(Collectors.toList())
        )
      );

      logger.debug(MessageFormat.format(
        "End fetching shares in batch. {0} shares found for {1} nodes",
        shares.size(),
        nodeIds.size()
      ));

      return results;
    });
  }
}
