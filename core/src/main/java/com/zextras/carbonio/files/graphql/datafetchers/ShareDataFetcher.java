// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.datafetchers;

import com.google.inject.Inject;
import com.zextras.carbonio.files.Constants;
import com.zextras.carbonio.files.Constants.GraphQL.DataLoaders;
import com.zextras.carbonio.files.config.FilesConfig;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.dao.ebean.Share;
import com.zextras.carbonio.files.dal.repositories.interfaces.NodeRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.NotificationRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.ShareRepository;
import com.zextras.carbonio.files.graphql.GraphQLProvider;
import com.zextras.carbonio.files.graphql.errors.GraphQLResultErrors;
import com.zextras.carbonio.files.utilities.PermissionsChecker;
import com.zextras.carbonio.usermanagement.entities.UserMyself;
import graphql.execution.AbortExecutionException;
import graphql.execution.DataFetcherResult;
import graphql.execution.DataFetcherResult.Builder;
import graphql.schema.DataFetcher;
import graphql.schema.idl.EnumValuesProvider;
import graphql.GraphQLError;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * <p>Contains all the implementations of {@link DataFetcher}s for all the queries and mutations
 * defined in the GraphQL schema that are related to the {@link Constants.GraphQL.Share} type.</p>
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

  private final FilesConfig filesConfig;
  private final ShareRepository    shareRepository;
  private final NodeRepository     nodeRepository;
  private final PermissionsChecker permissionsChecker;
  private final NotificationRepository notificationRepository;

  @Inject
  public ShareDataFetcher(
      FilesConfig filesConfig,
      NodeRepository nodeRepository,
      ShareRepository shareRepository,
      PermissionsChecker permissionsChecker, NotificationRepository notificationRepository
  ) {
    this.filesConfig = filesConfig;
    this.shareRepository = shareRepository;
    this.nodeRepository = nodeRepository;
    this.permissionsChecker = permissionsChecker;
    this.notificationRepository = notificationRepository;
  }

  private DataFetcherResult<Map<String, Object>> convertShareToDataFetcherResult(Share share) {
    Map<String, String> shareContext = new HashMap<>();
    Map<String, Object> result = new HashMap<>();
    result.put(Constants.GraphQL.Share.CREATED_AT, share.getCreatedAt());
    result.put(Constants.GraphQL.Share.PERMISSION, share.getPermissions().getSharePermission());
    share
      .getExpiredAt()
      .ifPresent(expiration -> result.put(Constants.GraphQL.Share.EXPIRES_AT, expiration));

    shareContext.put(Constants.GraphQL.Share.NODE, share.getNodeId());
    shareContext.put(Constants.GraphQL.Share.SHARE_TARGET, share.getTargetUserId());
    return new Builder<Map<String, Object>>()
      .data(result)
      .localContext(shareContext)
      .build();
  }

  public EnumValuesProvider getSharePermissionsResolver() {
    return ACL.SharePermission::valueOf;
  }

  /**
   * <p>This {@link DataFetcher} must be used for the {@link Constants.GraphQL.Mutations#CREATE_SHARE}
   * mutation.</p>
   * <p>The request must have the following parameters in input:</p>
   * <ul>
   * <li>{@link Constants.GraphQL.InputParameters.Share#NODE_ID}: a {@link String} representing the id of the node to share
   * (this is mandatory).</li>
   * <li>{@link Constants.GraphQL.InputParameters.Share#SHARE_TARGET_ID}: a {@link String} representing the user to whom the
   * node is shared with (this is mandatory).</li>
   * <li>{@link Constants.GraphQL.InputParameters.Share#PERMISSION}: an {@link ACL.SharePermission} representing the
   * permissions that the user will have on the node.</li>>
   * <li>{@link Constants.GraphQL.InputParameters.Share#EXPIRES_AT}: a long representing the expiration timestamp.</li>
   * </ul>
   * <h2>Behaviour:</h2>
   * <p>It creates the share with the values specified in input, it saves the mandatory parameters necessary to fetch
   * the related {@link Constants.GraphQL.Node} object and the related {@link Constants.GraphQL.User} object, it propagates
   * the share on all sub nodes recursively, then ii creates the GraphQL map of the new share created.</p>
   *
   * @return an asynchronous {@link DataFetcher} containing a {@link Map} of all the attributes
   * values of the created share.
   * @throws AbortExecutionException if the share already exists.
   */
  public DataFetcher<CompletableFuture<DataFetcherResult<Map<String, Object>>>> createShareFetcher() {
    return environment -> CompletableFuture.supplyAsync(() ->
    {
      UserMyself requesterUser = (UserMyself) environment.getGraphQlContext().get(Constants.GraphQL.Context.REQUESTER);
      String requesterId = requesterUser.getId().getUserId();
      String sharedNodeId = environment.getArgument(Constants.GraphQL.InputParameters.Share.NODE_ID);
      String targetUserId = environment.getArgument(
        Constants.GraphQL.InputParameters.Share.SHARE_TARGET_ID
      );
      ACL.SharePermission permissions = environment.getArgument(
        Constants.GraphQL.InputParameters.Share.PERMISSION
      );
      Optional<Long> optExpiresAt = Optional.ofNullable(
        environment.getArgument(Constants.GraphQL.InputParameters.Share.EXPIRES_AT)
      );

      if (permissionsChecker
        .getPermissions(sharedNodeId, requesterId)
        .has(ACL.SharePermission.READ_AND_SHARE)
      ) {
        Node sharedNode = nodeRepository.getNode(sharedNodeId).get();
        String ownerId = sharedNode.getOwnerId();

        if (targetUserId.equals(ownerId)) {
          return new Builder<Map<String, Object>>()
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
            DataFetcherResult<Map<String, Object>> result = convertShareToDataFetcherResult(share);

            // Create notification after share is created
            List<String> usersToNotify = List.of(targetUserId);
            if (filesConfig.areNotificationsEnabled()) {
              notificationRepository.createNewShareNotification(
                  sharedNode, requesterUser, usersToNotify
              );
            }

            return result;
          })
          .orElse(new Builder<Map<String, Object>>()
            .error(GraphQLResultErrors.shareCreationError(
              sharedNodeId,
              targetUserId,
              environment.getExecutionStepInfo().getPath()))
            .build()
          );
      } else {
        return new Builder<Map<String, Object>>()
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
   * <p>This {@link DataFetcher} must be used for the {@link Constants.GraphQL.Queries#GET_SHARE}
   * query.</p>
   * <p>The request must have the following parameters in input:</p>
   * <ul>
   * <li>{@link Constants.GraphQL.InputParameters.Share#NODE_ID}: a {@link String} representing the id of the shared node
   * (this is mandatory).</li>
   * <li>{@link Constants.GraphQL.InputParameters.Share#SHARE_TARGET_ID}: a {@link String} representing the user to whom the
   * node is shared with (this is mandatory).</li>
   * </ul>
   * <h2>Behaviour:</h2>
   * <p>If the share exists it saves the mandatory parameters necessary to fetch the related {@link Constants.GraphQL.Node}
   * object and the related {@link Constants.GraphQL.User} object, then it creates the GraphQL map of the requested share.
   * </p>
   *
   * @return an asynchronous {@link DataFetcher} containing a {@link Map} of all the attributes
   * values of the requested share or it returns <code>null</code> if the share does not exist.
   */
  public DataFetcher<CompletableFuture<DataFetcherResult<Map<String, Object>>>> getShareFetcher() {
    return environment -> CompletableFuture.supplyAsync(() ->
    {
      String requesterId = ((UserMyself) environment.getGraphQlContext()
        .get(Constants.GraphQL.Context.REQUESTER)).getId().getUserId();
      String sharedNodeId = environment.getArgument(Constants.GraphQL.InputParameters.Share.NODE_ID);
      String targetUserId = environment.getArgument(
        Constants.GraphQL.InputParameters.Share.SHARE_TARGET_ID);

      return permissionsChecker.getPermissions(sharedNodeId, requesterId)
        .has(ACL.SharePermission.READ_ONLY)
        ? shareRepository.getShare(sharedNodeId, targetUserId)
        .map(this::convertShareToDataFetcherResult)
        .orElse(new Builder<Map<String, Object>>()
          .error(GraphQLResultErrors.shareNotfound(
            sharedNodeId,
            targetUserId,
            environment.getExecutionStepInfo()
              .getPath()))
          .build()
        )
        : new Builder<Map<String, Object>>()
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
        ((Map<String, String>) environment.getLocalContext()).get(Constants.GraphQL.Node.ID);

      int limit = environment.getArgument(Constants.GraphQL.InputParameters.LIMIT);

      Optional<String> optCursor = Optional.ofNullable(
        environment.getArgument(Constants.GraphQL.InputParameters.CURSOR)
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
   * <p>This {@link DataFetcher} must be used for the {@link Constants.GraphQL.Mutations#UPDATE_SHARES}
   * mutation.</p>
   * <p>The request must have the following parameters in input:</p>
   * <ul>
   * <li>{@link Constants.GraphQL.InputParameters.Share#NODE_ID}: a {@link String} representing the id of the shared node
   * (this is mandatory).</li>
   * <li>{@link Constants.GraphQL.InputParameters.Share#SHARE_TARGET_IDS}: a {@link List} of {@link String} representing
   * the users to whom the node is shared with (this is mandatory).</li>
   * <li>{@link Constants.GraphQL.InputParameters.Share#PERMISSION}: an {@link ACL.SharePermission} representing the new
   * permissions that the users will have on the node.</li>
   * <li>{@link Constants.GraphQL.InputParameters.Share#EXPIRES_AT}: a long representing the expiration timestamp.</li>
   * </ul>
   * <h2>Behaviour:</h2>
   * <p>For each target user, it retrieves the share, updates it with the new values specified in input,
   * propagates the updates on all sub nodes recursively, then creates the GraphQL map of the updated share.
   * Shares that do not exist or for which the requester lacks permissions are reported as errors while
   * the remaining shares are updated successfully (partial success).</p>
   *
   * @return an asynchronous {@link DataFetcher} containing a {@link List} of {@link DataFetcherResult}
   * with the successfully updated shares and errors for the failed ones.
   */
  public DataFetcher<CompletableFuture<DataFetcherResult<List<DataFetcherResult<Map<String, Object>>>>>> updateSharesFetcher() {
    return environment -> CompletableFuture.supplyAsync(() ->
    {
      String requesterId = ((UserMyself) environment.getGraphQlContext()
        .get(Constants.GraphQL.Context.REQUESTER)).getId().getUserId();
      String sharedNodeId = environment.getArgument(Constants.GraphQL.InputParameters.Share.NODE_ID);
      List<String> targetUserIds = environment.getArgument(
        Constants.GraphQL.InputParameters.Share.SHARE_TARGET_IDS);

      Optional<ACL.SharePermission> optNewPermissions = Optional.ofNullable(
        environment.getArgument(Constants.GraphQL.InputParameters.Share.PERMISSION)
      );
      Optional<Long> optNewExpiresAt = Optional.ofNullable(
        environment.getArgument(Constants.GraphQL.InputParameters.Share.EXPIRES_AT)
      );

      boolean hasPermission = permissionsChecker.getPermissions(sharedNodeId, requesterId)
        .has(ACL.SharePermission.READ_AND_SHARE);

      List<DataFetcherResult<Map<String, Object>>> successShares = new ArrayList<>();
      List<GraphQLError> errors = new ArrayList<>();

      for (String targetUserId : targetUserIds) {
        if (!hasPermission) {
          errors.add(GraphQLResultErrors.shareNotfound(
            sharedNodeId, targetUserId, environment.getExecutionStepInfo().getPath()));
          continue;
        }

        Optional<Share> optShare = shareRepository.getShare(sharedNodeId, targetUserId);
        if (optShare.isPresent()) {
          Share share = optShare.get();
          optNewPermissions.ifPresent(permissions -> {
              share.setPermissions(ACL.decode(permissions));
              cascadeUpsertShare(sharedNodeId, targetUserId, ACL.decode(optNewPermissions.get()),
                optNewExpiresAt);
            }
          );
          optNewExpiresAt.ifPresent(share::setExpiredAt);
          Share updatedShare = shareRepository.updateShare(share);
          successShares.add(convertShareToDataFetcherResult(updatedShare));
        } else {
          errors.add(GraphQLResultErrors.shareNotfound(
            sharedNodeId, targetUserId, environment.getExecutionStepInfo().getPath()));
        }
      }

      return DataFetcherResult.<List<DataFetcherResult<Map<String, Object>>>>newResult()
        .data(successShares)
        .errors(errors)
        .build();
    });
  }

  /**
   * <p>This {@link DataFetcher} must be used for the {@link Constants.GraphQL.Mutations#DELETE_SHARES}
   * mutation.</p>
   * <p>The request must have the following parameters in input:</p>
   * <ul>
   * <li>{@link Constants.GraphQL.InputParameters.Share#NODE_ID}: a {@link String} representing the id of the shared node
   * (this is mandatory).</li>
   * <li>{@link Constants.GraphQL.InputParameters.Share#SHARE_TARGET_IDS}: a {@link List} of {@link String} representing
   * the users to whom the node is shared with (this is mandatory).</li>
   * </ul>
   * <h2>Behaviour:</h2>
   * <p>For each target user, it retrieves and deletes the share (if exists). It also propagates
   * the deletion on all sub nodes recursively. Shares that do not exist or for which the requester
   * lacks permissions are reported as errors while the other shares are deleted successfully
   * (partial success).</p>
   *
   * @return an asynchronous {@link DataFetcher} containing a {@link List} of target user IDs
   * for which the share was successfully deleted, with errors for the failed ones.
   */
  public DataFetcher<CompletableFuture<DataFetcherResult<List<String>>>> deleteSharesFetcher() {
    return environment -> CompletableFuture.supplyAsync(() ->
    {
      String requesterId = ((UserMyself) environment.getGraphQlContext()
        .get(Constants.GraphQL.Context.REQUESTER)).getId().getUserId();
      final String sharedNodeId = environment.getArgument(
        Constants.GraphQL.InputParameters.Share.NODE_ID);
      List<String> targetUserIds = environment.getArgument(
        Constants.GraphQL.InputParameters.Share.SHARE_TARGET_IDS);

      List<String> deletedTargetUserIds = new ArrayList<>();
      List<GraphQLError> errors = new ArrayList<>();

      for (String targetUserId : targetUserIds) {
        boolean hasPermission = permissionsChecker.getPermissions(sharedNodeId, requesterId)
          .has(ACL.SharePermission.READ_AND_SHARE)
          || requesterId.equals(targetUserId);

        if (!hasPermission) {
          errors.add(GraphQLResultErrors.shareNotfound(
            sharedNodeId, targetUserId, environment.getExecutionStepInfo().getPath()));
          continue;
        }

        Optional<Share> optShare = shareRepository.getShare(sharedNodeId, targetUserId);
        if (optShare.isPresent()) {
          Share share = optShare.get();
          shareRepository.deleteShare(share.getNodeId(), share.getTargetUserId());

          // Recursively delete all the indirect share of targetUser (even for the trashed nodes)
          if (nodeRepository.getNode(sharedNodeId)
            .get()
            .getNodeType() == NodeType.FOLDER) {
            cascadeDeleteShare(sharedNodeId, targetUserId);
          }

          deletedTargetUserIds.add(targetUserId);
        } else {
          errors.add(GraphQLResultErrors.shareNotfound(
            sharedNodeId, targetUserId, environment.getExecutionStepInfo().getPath()));
        }
      }

      return DataFetcherResult.<List<String>>newResult()
        .data(deletedTargetUserIds)
        .errors(errors)
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
