// SPDX-FileCopyrightText: 2025 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.datafetchers;
import com.zextras.carbonio.files.graphql.SyncCompletableFuture;

import com.zextras.carbonio.files.Constants;
import com.zextras.carbonio.files.Constants.GraphQL.Context;
import com.zextras.carbonio.files.Constants.GraphQL.NotificationPage;
import com.zextras.carbonio.files.config.FilesConfig;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.AddedNodeNotification;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.BaseNotification;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.NewShareNotification;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.RemovedNodeNotification;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.utils.NotificationTypeCodes;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.utils.UserNotificationsInfo;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.utils.snapshot.SnapshotNode;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.utils.snapshot.SnapshotUser;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.AddedNodeType;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.RemovedNodeType;
import com.zextras.carbonio.files.dal.repositories.interfaces.NotificationRepository;
import com.zextras.carbonio.files.dal.dao.UserMyself;
import graphql.GraphQLError;
import graphql.execution.DataFetcherResult;
import graphql.execution.DataFetcherResult.Builder;
import graphql.execution.ResultPath;
import graphql.schema.DataFetcher;
import graphql.schema.TypeResolver;
import graphql.schema.idl.EnumValuesProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@ApplicationScoped
public class NotificationDataFetcher {

  private static final Logger logger =
      LoggerFactory.getLogger(NotificationDataFetcher.class);

  private final FilesConfig filesConfig;
  private final NotificationRepository notificationRepository;

  @Inject
  public NotificationDataFetcher(
      FilesConfig filesConfig,
      NotificationRepository notificationRepository
  ) {
    this.filesConfig = filesConfig;
    this.notificationRepository = notificationRepository;
  }

  public EnumValuesProvider getAddedNodeTypeResolver() {
    return AddedNodeType::valueOf;
  }

  public EnumValuesProvider getRemovedNodeTypeResolver() {
    return RemovedNodeType::valueOf;
  }

  private Map<String, Object> mapSnapshotNode(SnapshotNode node) {
    if (node == null) return null;
    Map<String, Object> map = new HashMap<>();
    map.put(Constants.GraphQL.SnapshotNode.SNAPSHOT_NODE_ID, node.getSnapshotNodeId());
    map.put(Constants.GraphQL.SnapshotNode.NODE_ID, node.getNodeId());
    map.put(Constants.GraphQL.SnapshotNode.OWNER_ID, node.getOwnerId()); // Can be null
    map.put(Constants.GraphQL.SnapshotNode.NAME, node.getName());
    map.put(Constants.GraphQL.SnapshotNode.TYPE, node.getNodeType());
    map.put(Constants.GraphQL.SnapshotNode.CREATED_AT, node.getCreatedAt());
    return map;
  }

  private Map<String, Object> mapSnapshotUser(SnapshotUser user) {
    if (user == null) return null;
    return Map.of(
        Constants.GraphQL.SnapshotUser.SNAPSHOT_USER_ID, user.getSnapshotUserId(),
        Constants.GraphQL.SnapshotUser.USER_ID, user.getUserId(),
        Constants.GraphQL.SnapshotUser.FULL_NAME, user.getFullName(),
        Constants.GraphQL.SnapshotUser.EMAIL, user.getEmail()
    );
  }

  public TypeResolver getNotificationInterfaceResolver() {
    return environment -> {
      Map<String, Object> notification = environment.getObject();
      String type = (String) notification.get(Constants.GraphQL.Notification.NOTIFICATION_TYPE);

      return switch (type) {
        case NotificationTypeCodes.NEW_SHARE -> environment.getSchema().getObjectType(Constants.GraphQL.Types.NEW_SHARE);
        case NotificationTypeCodes.ADDED_NODE -> environment.getSchema().getObjectType(Constants.GraphQL.Types.ADDED_NODE);
        case NotificationTypeCodes.REMOVED_NODE -> environment.getSchema().getObjectType(Constants.GraphQL.Types.REMOVED_NODE);
        default -> throw new IllegalStateException("Unknown notification type: " + type);
      };
    };
  }

  private DataFetcherResult<Map<String, Object>> convertNotificationToPageResult(
      BaseNotification notification,
      ResultPath path
  ) {
    Map<String, Object> result = new HashMap<>();
    Map<String, String> nodeContext = new HashMap<>();
    Optional<GraphQLError> error = Optional.empty();

    String type = notification.getNotificationType();
    result.put(Constants.GraphQL.Notification.ID, notification.getNotificationId());
    result.put(Constants.GraphQL.Notification.CREATED_AT, notification.getCreatedAt());
    result.put(Constants.GraphQL.Notification.NOTIFICATION_TYPE, type);

    switch (type) {
      case NotificationTypeCodes.NEW_SHARE -> {
        NewShareNotification newShareNotification = (NewShareNotification) notification;
        result.put(Constants.GraphQL.NewShareNotification.NODE_SNAPSHOT, mapSnapshotNode(newShareNotification.getSnapshotNode()));
        result.put(Constants.GraphQL.NewShareNotification.USER_SNAPSHOT, mapSnapshotUser(newShareNotification.getSnapshotUser()));
      }
      case NotificationTypeCodes.ADDED_NODE -> {
        AddedNodeNotification addedNodeNotification = (AddedNodeNotification) notification;
        result.put(Constants.GraphQL.AddedNodeNotification.ADDED_NODE_SNAPSHOT, mapSnapshotNode(addedNodeNotification.getAddedNodeSnapshot()));
        result.put(Constants.GraphQL.AddedNodeNotification.ADDED_NODE_TYPE, addedNodeNotification.getAddedNodeType());
        result.put(Constants.GraphQL.AddedNodeNotification.DESTINATION_FOLDER, mapSnapshotNode(addedNodeNotification.getDestinationFolderSnapshot()));
        result.put(Constants.GraphQL.AddedNodeNotification.TRIGGERING_USER, mapSnapshotUser(addedNodeNotification.getTriggeringUserSnapshot()));
      }
      case NotificationTypeCodes.REMOVED_NODE -> {
        RemovedNodeNotification removedNodeNotification = (RemovedNodeNotification) notification;
        result.put(Constants.GraphQL.RemovedNodeNotification.REMOVED_NODE_TYPE, removedNodeNotification.getRemovedNodeType());
        result.put(Constants.GraphQL.RemovedNodeNotification.REMOVED_NODE, mapSnapshotNode(removedNodeNotification.getRemovedNodeSnapshot()));
        result.put(Constants.GraphQL.RemovedNodeNotification.ORIGIN_FOLDER, mapSnapshotNode(removedNodeNotification.getOriginFolderSnapshot()));
        result.put(Constants.GraphQL.RemovedNodeNotification.TRIGGERING_USER, mapSnapshotUser(removedNodeNotification.getTriggeringUserSnapshot()));
      }
      default -> { /* Unknown/Advanced-only type: base fields already populated. */ }
    }

    DataFetcherResult.Builder<Map<String, Object>> resultBuilder = new DataFetcherResult
        .Builder<Map<String, Object>>()
        .data(result)
        .localContext(nodeContext);

    return error
        .map(err -> resultBuilder.error(err).build())
        .orElse(resultBuilder.build());
  }

  public DataFetcher<CompletableFuture<List<DataFetcherResult<Map<String, Object>>>>> notificationPageFetcher() {
    return environment -> SyncCompletableFuture.supplyAsync(() -> {
      return Optional.ofNullable(environment.getLocalContext())
          .map(context -> {
            return ((Map<String, List<BaseNotification>>) context).get(Constants.GraphQL.NotificationPage.NOTIFICATIONS)
                .stream()
                .map(notification ->
                    convertNotificationToPageResult(
                        notification,
                        environment.getExecutionStepInfo().getPath()
                    )
                )
                .collect(Collectors.toList());
          })
          .orElse(Collections.emptyList());
    });
  }

  public DataFetcher<CompletableFuture<DataFetcherResult<Map<String, String>>>> getNotificationsFetcher() {
    return environment -> SyncCompletableFuture.supplyAsync(() -> {
      String requesterId = ((UserMyself) environment.getGraphQlContext()
          .get(Context.REQUESTER)).getId().getUserId();

      Boolean updateLastSeen = environment.getArgument(Constants.GraphQL.InputParameters.UPDATE_LAST_SEEN);
      Optional<Integer> optLimit = Optional.ofNullable(
          environment.getArgument(Constants.GraphQL.InputParameters.LIMIT)
      );
      Optional<String> optPageToken = Optional.ofNullable(
          environment.getArgument(Constants.GraphQL.InputParameters.PAGE_TOKEN)
      );

      Map<String, List<BaseNotification>> localContext = new HashMap<>();
      Map<String, String> result = new HashMap<>();
      ImmutablePair<List<BaseNotification>, String> findResult = null;
      findResult = filesConfig.areNotificationsEnabled() ?
          notificationRepository.getNotifications(requesterId, optLimit, optPageToken) :
          new ImmutablePair<>(Collections.emptyList(), null);

      result.put(NotificationPage.PAGE_TOKEN, findResult.getRight());
      localContext.put(NotificationPage.NOTIFICATIONS, findResult.getLeft());

      Optional<UserNotificationsInfo> optUserNotificationsInfo =
          filesConfig.areNotificationsEnabled() ?
              notificationRepository.getUserNotificationsInfo(requesterId) :
              Optional.empty();

      // If user is not present in table, it has no notification (so zero unread, and user never seen before)
      UserNotificationsInfo userNotificationsInfo = optUserNotificationsInfo.orElse(
          new UserNotificationsInfo(requesterId, 0L, 0)
      );

      result.put(NotificationPage.UNREAD, userNotificationsInfo.getUnread().toString());
      result.put(NotificationPage.LAST_SEEN, userNotificationsInfo.getLastSeen().toString());

      if (Boolean.TRUE.equals(updateLastSeen) && filesConfig.areNotificationsEnabled()) {
        if (optUserNotificationsInfo.isPresent()) {
          // User is present in table, so we update the last seen time and assume all news are now read
          userNotificationsInfo.setLastSeen(System.currentTimeMillis());
          userNotificationsInfo.setUnread(0);
          notificationRepository.updateUserNotificationsInfo(userNotificationsInfo);
        } else {
          // User is not present in table, we create it here for the first time as fallback,
          // setting last seen implicitly
          notificationRepository.createUserNotificationsInfo(requesterId);
        }
      }

      return new Builder<Map<String, String>>()
          .data(result)
          .localContext(localContext)
          .build();
    });
  }
}
