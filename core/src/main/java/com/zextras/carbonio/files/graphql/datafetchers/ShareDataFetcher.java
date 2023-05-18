// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.datafetchers;

import com.google.inject.Inject;
import com.zextras.carbonio.files.Files;
import com.zextras.carbonio.files.Files.GraphQL.DataLoaders;
import com.zextras.carbonio.files.dal.dao.User;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.dao.ebean.Share;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.ShareRepository;
import com.zextras.carbonio.files.graphql.GraphQLProvider;
import com.zextras.carbonio.files.graphql.errors.GraphQLResultErrors;
import com.zextras.carbonio.files.utilities.PermissionsChecker;
import graphql.execution.AbortExecutionException;
import graphql.execution.DataFetcherResult;
import graphql.execution.DataFetcherResult.Builder;
import graphql.schema.DataFetcher;
import graphql.schema.idl.EnumValuesProvider;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * <p>Contains all the implementations of {@link DataFetcher}s for all the queries and mutations
 * defined in the GraphQL schema that are related to the {@link Files.GraphQL.Share} type.</p>
 * <p>Each {@link DataFetcher} implementation is asynchronous and returns an {@link HashMap}
 * containing the data fetched from the database. Each key of the resulting map must match the name
 * of the related Share attribute defined in the GraphQL schema.</p>
 * <p>These {@link DataFetcher}s will be used in the {@link GraphQLProvider} where they are bound
 * with the related queries, mutations and composed attributes.</p>
 * <p><strong>GraphQL behaviour:</strong> When a {@link DataFetcher} returns an empty {@link Map}
 * the GraphQL library has two distinct behaviours:
 * <ul>
 *   <li>
 *     if the related attribute was defined not <code>null</code> in the schema, it returns an error because it cannot
 *     find the mandatory attributes related to the Share inside the {@link Map}.
 *   </li>
 *   <li>
 *     it associates <code>null</code> to the attribute specified, if it was defined that can be <code>null</code>.
 *   </li>
 * </ul>
 */
public class ShareDataFetcher {

  private final ShareRepository    shareRepository;
  private final NodeRepository     nodeRepository;
  private final PermissionsChecker permissionsChecker;

  @Inject
  public ShareDataFetcher(
    NodeRepository nodeRepository,
    ShareRepository shareRepository,
    PermissionsChecker permissionsChecker
  ) {
    this.shareRepository = shareRepository;
    this.nodeRepository = nodeRepository;
    this.permissionsChecker = permissionsChecker;
  }

  private DataFetcherResult<Map<String, Object>> convertShareToDataFetcherResult(Share share) {
    Map<String, String> shareContext = new HashMap<>();
    Map<String, Object> result = new HashMap<>();
    result.put(Files.GraphQL.Share.CREATED_AT, share.getCreationAt());
    result.put(Files.GraphQL.Share.PERMISSION, share.getPermissions().getSharePermission());
    share
      .getExpiredAt()
      .ifPresent(expiration -> result.put(Files.GraphQL.Share.EXPIRES_AT, expiration));

    shareContext.put(Files.GraphQL.Share.NODE, share.getNodeId());
    shareContext.put(Files.GraphQL.Share.SHARE_TARGET, share.getTargetUserId());
    return new DataFetcherResult.Builder<Map<String, Object>>()
      .data(result)
      .localContext(shareContext)
      .build();
  }

  public EnumValuesProvider getSharePermissionsResolver() {
    return ACL.SharePermission::valueOf;
  }

  /**
   * <p>This {@link DataFetcher} must be used for the {@link Files.GraphQL.Mutations#CREATE_SHARE}
   * mutation.</p>
   * <p>The request must have the following parameters in input:</p>
   * <ul>
   * <li>{@link Files.GraphQL.InputParameters.Share#NODE_ID}: a {@link String} representing the id of the node to share
   * (this is mandatory).</li>
   * <li>{@link Files.GraphQL.InputParameters.Share#SHARE_TARGET_ID}: a {@link String} representing the user to whom the
   * node is shared with (this is mandatory).</li>
   * <li>{@link Files.GraphQL.InputParameters.Share#PERMISSION}: an {@link ACL.SharePermission} representing the
   * permissions that the user will have on the node.</li>>
   * <li>{@link Files.GraphQL.InputParameters.Share#EXPIRES_AT}: a long representing the expiration timestamp.</li>
   * </ul>
   * <h2>Behaviour:</h2>
   * <p>It creates the share with the values specified in input, it saves the mandatory parameters necessary to fetch
   * the related {@link Files.GraphQL.Node} object and the related {@link Files.GraphQL.User} object, it propagates
   * the share on all sub nodes recursively, then ii creates the GraphQL map of the new share created.</p>
   *
   * @return an asynchronous {@link DataFetcher} containing a {@link Map} of all the attributes
   * values of the created share.
   * @throws AbortExecutionException if the share already exists.
   */
  public DataFetcher<CompletableFuture<DataFetcherResult<Map<String, Object>>>> createShareFetcher() {
    return environment -> CompletableFuture.supplyAsync(() ->
    {
      String requesterId = ((User) environment.getGraphQlContext()
        .get(Files.GraphQL.Context.REQUESTER)).getId();
      String sharedNodeId = environment.getArgument(Files.GraphQL.InputParameters.Share.NODE_ID);
      String targetUserId = environment.getArgument(
        Files.GraphQL.InputParameters.Share.SHARE_TARGET_ID
      );
      ACL.SharePermission permissions = environment.getArgument(
        Files.GraphQL.InputParameters.Share.PERMISSION
      );
      Optional<Long> optExpiresAt = Optional.ofNullable(
        environment.getArgument(Files.GraphQL.InputParameters.Share.EXPIRES_AT)
      );

      if (permissionsChecker
        .getPermissions(sharedNodeId, requesterId)
        .has(ACL.SharePermission.READ_AND_SHARE)
      ) {
        String ownerId = nodeRepository.getNode(sharedNodeId).get().getOwnerId();

        if (targetUserId.equals(ownerId)) {
          return new DataFetcherResult.Builder<Map<String, Object>>()
            .error(GraphQLResultErrors.shareCreationError(
              sharedNodeId,
              targetUserId,
              environment.getExecutionStepInfo().getPath())
            )
            .build();
        }
        return shareRepository.upsertShare(
            sharedNodeId,
            targetUserId,
            ACL.decode(permissions),
            true,
            false,
            optExpiresAt
          )
          .map(share -> {
            cascadeUpsertShare(sharedNodeId, targetUserId, ACL.decode(permissions), optExpiresAt);
            return convertShareToDataFetcherResult(share);
          })
          .orElse(new DataFetcherResult.Builder<Map<String, Object>>()
            .error(GraphQLResultErrors.shareCreationError(
              sharedNodeId,
              targetUserId,
              environment.getExecutionStepInfo().getPath()))
            .build()
          );
      } else {
        return new DataFetcherResult.Builder<Map<String, Object>>()
          .error(GraphQLResultErrors.shareCreationError(
            sharedNodeId,
            targetUserId,
            environment.getExecutionStepInfo().getPath()))
          .build();
      }
    });
  }

  public void cascadeUpsertShare(
    String nodeId,
    String userId,
    ACL permission,
    Optional<Long> expiredAt
  ) {
    List<String> childrenIds = nodeRepository
      .getChildrenIds(nodeId, Optional.empty(), Optional.empty(), false);
    if (!childrenIds.isEmpty()) {
      List<Node> childrenNodes = nodeRepository.getNodes(childrenIds, Optional.empty())
        .collect(Collectors.toList());
      List<Node> folderNodes = childrenNodes.stream()
        .filter(n -> n.getNodeType() == NodeType.FOLDER)
        .collect(Collectors.toList());

      shareRepository.upsertShareBulk(childrenIds, userId, permission, false, false, expiredAt);

      folderNodes.forEach(folderNode ->
        cascadeUpsertShare(folderNode.getId(), userId, permission, expiredAt)
      );
    }
  }

  /**
   * <p>This {@link DataFetcher} must be used for the {@link Files.GraphQL.Queries#GET_SHARE}
   * query.</p>
   * <p>The request must have the following parameters in input:</p>
   * <ul>
   * <li>{@link Files.GraphQL.InputParameters.Share#NODE_ID}: a {@link String} representing the id of the shared node
   * (this is mandatory).</li>
   * <li>{@link Files.GraphQL.InputParameters.Share#SHARE_TARGET_ID}: a {@link String} representing the user to whom the
   * node is shared with (this is mandatory).</li>
   * </ul>
   * <h2>Behaviour:</h2>
   * <p>If the share exists it saves the mandatory parameters necessary to fetch the related {@link Files.GraphQL.Node}
   * object and the related {@link Files.GraphQL.User} object, then it creates the GraphQL map of the requested share.
   * </p>
   *
   * @return an asynchronous {@link DataFetcher} containing a {@link Map} of all the attributes
   * values of the requested share or it returns <code>null</code> if the share does not exist.
   */
  public DataFetcher<CompletableFuture<DataFetcherResult<Map<String, Object>>>> getShareFetcher() {
    return environment -> CompletableFuture.supplyAsync(() ->
    {
      String requesterId = ((User) environment.getGraphQlContext()
        .get(Files.GraphQL.Context.REQUESTER)).getId();
      String sharedNodeId = environment.getArgument(Files.GraphQL.InputParameters.Share.NODE_ID);
      String targetUserId = environment.getArgument(
        Files.GraphQL.InputParameters.Share.SHARE_TARGET_ID);

      return permissionsChecker.getPermissions(sharedNodeId, requesterId)
        .has(ACL.SharePermission.READ_ONLY)
        ? shareRepository.getShare(sharedNodeId, targetUserId)
        .map(this::convertShareToDataFetcherResult)
        .orElse(new DataFetcherResult.Builder<Map<String, Object>>()
          .error(GraphQLResultErrors.shareNotfound(
            sharedNodeId,
            targetUserId,
            environment.getExecutionStepInfo()
              .getPath()))
          .build()
        )
        : new DataFetcherResult.Builder<Map<String, Object>>()
          .error(GraphQLResultErrors.shareNotfound(
            sharedNodeId,
            targetUserId,
            environment.getExecutionStepInfo()
              .getPath()))
          .build();
    });
  }

  public DataFetcher<CompletableFuture<List<DataFetcherResult<Map<String, Object>>>>> getSharesFetcher() {
    return environment -> {

      String sharedNodeId =
        ((Map<String, String>) environment.getLocalContext()).get(Files.GraphQL.Node.ID);

      int limit = environment.getArgument(Files.GraphQL.InputParameters.LIMIT);

      Optional<String> optCursor = Optional.ofNullable(
        environment.getArgument(Files.GraphQL.InputParameters.CURSOR)
      );

      //TODO: At the moment the sorting is not supported

      return environment
        .getDataLoader(DataLoaders.SHARE_BATCH_LOADER)
        .load(sharedNodeId)
        .thenApply(shares -> {
          int numberNodesToSkip = optCursor
            .map(cursor ->
              ((List<Share>) shares)
                .stream()
                .map(Share::getTargetUserId)
                .collect(Collectors.toList())
                .indexOf(cursor) + 1
            )
            .orElse(0);

          return ((List<Share>) shares)
            .stream()
            .skip(numberNodesToSkip)
            .limit(limit)
            .map(this::convertShareToDataFetcherResult)
            .collect(Collectors.toList());
        })
        .exceptionally(failure ->
          Collections.singletonList(new Builder<Map<String, Object>>().build())
        );
    };
  }

  /**
   * <p>This {@link DataFetcher} must be used for the {@link Files.GraphQL.Mutations#UPDATE_SHARE}
   * mutation.</p>
   * <p>The request must have the following parameters in input:</p>
   * <ul>
   * <li>{@link Files.GraphQL.InputParameters.Share#NODE_ID}: a {@link String} representing the id of the shared node
   * (this is mandatory).</li>
   * <li>{@link Files.GraphQL.InputParameters.Share#SHARE_TARGET_ID}: a {@link String} representing the user to whom the
   * node is shared with (this is mandatory).</li>
   * <li>{@link Files.GraphQL.InputParameters.Share#PERMISSION}: an {@link ACL.SharePermission} representing the new
   * permissions that the user will have on the node.</li>>
   * <li>{@link Files.GraphQL.InputParameters.Share#EXPIRES_AT}: a long representing the expiration timestamp.</li>
   * </ul>
   * <h2>Behaviour:</h2>
   * <p>It retrieves the share, it updates that with the new values specified in input, it saves the mandatory
   * parameters necessary to fetch the related {@link Files.GraphQL.Node} object and the related
   * {@link Files.GraphQL.User} object, it propagates the updates on all sub nodes recursively, then it creates the
   * GraphQL map of the updated share.</p>
   *
   * @return an asynchronous {@link DataFetcher} containing a {@link Map} of all the attributes
   * values of the updated share or <code>null</code> if the share does not exist.
   */
  public DataFetcher<CompletableFuture<DataFetcherResult<Map<String, Object>>>> updateShareFetcher() {
    return environment -> CompletableFuture.supplyAsync(() ->
    {
      String requesterId = ((User) environment.getGraphQlContext()
        .get(Files.GraphQL.Context.REQUESTER)).getId();
      String sharedNodeId = environment.getArgument(Files.GraphQL.InputParameters.Share.NODE_ID);
      String targetUserId = environment.getArgument(
        Files.GraphQL.InputParameters.Share.SHARE_TARGET_ID);

      return permissionsChecker.getPermissions(sharedNodeId, requesterId)
        .has(ACL.SharePermission.READ_AND_SHARE)
        ? shareRepository.getShare(sharedNodeId, targetUserId)
        .map(share -> {
          Optional<ACL.SharePermission> optNewPermissions = Optional.ofNullable(
            environment.getArgument(Files.GraphQL.InputParameters.Share.PERMISSION)
          );
          Optional<Long> optNewExpiresAt = Optional.ofNullable(
            environment.getArgument(Files.GraphQL.InputParameters.Share.EXPIRES_AT)
          );

          optNewPermissions.ifPresent(permissions -> {
              share.setPermissions(ACL.decode(permissions));
              cascadeUpsertShare(sharedNodeId, targetUserId, ACL.decode(optNewPermissions.get()),
                optNewExpiresAt);
            }
          );
          optNewExpiresAt.ifPresent(share::setExpiredAt);
          Share updatedShare = shareRepository.updateShare(share);

          return convertShareToDataFetcherResult(updatedShare);
        })
        .orElse(new DataFetcherResult.Builder<Map<String, Object>>()
          .error(GraphQLResultErrors.shareNotfound(
            sharedNodeId,
            targetUserId,
            environment.getExecutionStepInfo()
              .getPath()))
          .build())
        : new DataFetcherResult.Builder<Map<String, Object>>()
          .error(GraphQLResultErrors.shareNotfound(
            sharedNodeId,
            targetUserId,
            environment.getExecutionStepInfo()
              .getPath()))
          .build();
    });
  }

  /**
   * <p>This {@link DataFetcher} must be used for the {@link Files.GraphQL.Mutations#DELETE_SHARE}
   * mutation.</p>
   * <p>The request must have the following parameters in input:</p>
   * <ul>
   * <li>{@link Files.GraphQL.InputParameters.Share#NODE_ID}: a {@link String} representing the id of the shared node
   * (this is mandatory).</li>
   * <li>{@link Files.GraphQL.InputParameters.Share#SHARE_TARGET_ID}: a {@link String} representing the user to whom the
   * node is shared with (this is mandatory).</li>
   * </ul>
   * <h2>Behaviour:</h2>
   * <p>It retrieves and deletes the share (if exists). It also propagates the deletion on all sub nodes recursively.</p>
   *
   * @return an asynchronous {@link DataFetcher} containing a {@link Boolean} that is true if the
   * share exists and the deletion is done successfully, false otherwise.
   */
  public DataFetcher<CompletableFuture<DataFetcherResult<Boolean>>> deleteShareFetcher() {
    return environment -> CompletableFuture.supplyAsync(() ->
    {
      String requesterId = ((User) environment.getGraphQlContext()
        .get(Files.GraphQL.Context.REQUESTER)).getId();
      final String sharedNodeId = environment.getArgument(
        Files.GraphQL.InputParameters.Share.NODE_ID);
      final String targetUserId = environment.getArgument(
        Files.GraphQL.InputParameters.Share.SHARE_TARGET_ID);

      return permissionsChecker.getPermissions(sharedNodeId, requesterId)
        .has(ACL.SharePermission.READ_AND_SHARE)
        || requesterId.equals(targetUserId)
        ? shareRepository.getShare(sharedNodeId, targetUserId)
        .map(share -> {
          //TODO when adding the shareId we will return the removed shareId/share instead of the boolean
          boolean shareDeleted = shareRepository.deleteShare(share.getNodeId(),
            share.getTargetUserId());

          // Recursively delete all the indirect share of targetUser (even for the trashed nodes)
          if (nodeRepository.getNode(sharedNodeId)
            .get()
            .getNodeType() == NodeType.FOLDER) {
            cascadeDeleteShare(sharedNodeId, targetUserId);
          }

          return new DataFetcherResult.Builder<Boolean>()
            .data(shareDeleted)
            .build();
        })
        .orElse(new DataFetcherResult.Builder<Boolean>()
          .error(GraphQLResultErrors.shareNotfound(
            sharedNodeId,
            targetUserId,
            environment.getExecutionStepInfo()
              .getPath()))
          .build())
        : new DataFetcherResult.Builder<Boolean>()
          .error(GraphQLResultErrors.shareNotfound(
            sharedNodeId,
            targetUserId,
            environment.getExecutionStepInfo()
              .getPath()))
          .build();
    });
  }

  void cascadeDeleteShare(
    String nodeId,
    String userId
  ) {
    List<String> childrenIds = nodeRepository.getChildrenIds(nodeId, Optional.empty(),
      Optional.empty(), true);
    List<String> trashedChildrenIds = nodeRepository.getTrashedNodeIdsByOldParent(nodeId);
    childrenIds.addAll(trashedChildrenIds);
    if (!childrenIds.isEmpty()) {
      List<Node> childrenNodes = nodeRepository.getNodes(childrenIds, Optional.empty())
        .collect(Collectors.toList());
      // Retrieve all the direct shares of the children nodes of the folder
      List<Share> shares = shareRepository
        .getShares(childrenNodes.stream()
          .map(Node::getId)
          .collect(Collectors.toList()), userId)
        .stream()
        .filter(Share::isDirect)
        .collect(Collectors.toList());
      // I delete the shares only for nodes that don't have a direct share for the user i'm propagating
      List<Node> deletableNodes = childrenNodes.stream()
        .filter(node -> shares.stream()
          .noneMatch(share -> share.getNodeId()
            .equals(node.getId())))
        .collect(Collectors.toList());
      List<Node> folderNodes = deletableNodes
        .stream()
        .filter(n -> n.getNodeType() == NodeType.FOLDER)
        .collect(Collectors.toList());

      shareRepository.deleteSharesBulk(deletableNodes.stream()
        .map(Node::getId)
        .collect(Collectors.toList()), userId);

      folderNodes.forEach(folderNode ->
        cascadeDeleteShare(folderNode.getId(), userId)
      );
    }
  }
}
