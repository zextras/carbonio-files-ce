// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.api;

import com.zextras.carbonio.files.config.FilesConfig;
import com.zextras.carbonio.files.dal.dao.UserMyself;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.AddedNodeNotification;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.BaseNotification;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.NewShareNotification;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.RemovedNodeNotification;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.utils.NotificationTypeCodes;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.utils.UserNotificationsInfo;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.utils.snapshot.SnapshotNode;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.utils.snapshot.SnapshotUser;
import com.zextras.carbonio.files.dal.repositories.interfaces.NotificationRepository;
import com.zextras.carbonio.files.graphql.auth.AuthenticatedUser;
import com.zextras.carbonio.files.graphql.model.AddedNodeModel;
import com.zextras.carbonio.files.graphql.model.AddedNodeType;
import com.zextras.carbonio.files.graphql.model.NewShareModel;
import com.zextras.carbonio.files.graphql.model.NodeType;
import com.zextras.carbonio.files.graphql.model.Notification;
import com.zextras.carbonio.files.graphql.model.NotificationPageModel;
import com.zextras.carbonio.files.graphql.model.NotificationType;
import com.zextras.carbonio.files.graphql.model.RemovedNodeModel;
import com.zextras.carbonio.files.graphql.model.RemovedNodeType;
import com.zextras.carbonio.files.graphql.model.SnapshotNodeModel;
import com.zextras.carbonio.files.graphql.model.SnapshotUserModel;
import com.zextras.carbonio.files.graphql.spi.NotificationModelContributor;
import io.quarkus.security.Authenticated;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Query;

@GraphQLApi
@Authenticated
public class NotificationApi {

  @Inject FilesConfig filesConfig;
  @Inject NotificationRepository notificationRepository;
  @Inject @AuthenticatedUser UserMyself requester;
  @Inject @Any Instance<NotificationModelContributor> notificationModelContributors;

  private static SnapshotNodeModel toSnapshotNodeModel(SnapshotNode node) {
    if (node == null) return null;
    return new SnapshotNodeModel(
        node.getSnapshotNodeId(),
        node.getNodeId(),
        node.getOwnerId(),
        node.getName(),
        NodeType.valueOf(node.getNodeType().name()),
        node.getCreatedAt());
  }

  private static SnapshotUserModel toSnapshotUserModel(SnapshotUser user) {
    if (user == null) return null;
    return new SnapshotUserModel(
        user.getSnapshotUserId(), user.getUserId(), user.getFullName(), user.getEmail());
  }

  private Notification toNotificationModel(BaseNotification notification) {
    String id = notification.getNotificationId();
    long createdAt = notification.getCreatedAt();
    NotificationType type = NotificationType.valueOf(notification.getNotificationType());

    return switch (notification.getNotificationType()) {
      case NotificationTypeCodes.NEW_SHARE -> {
        NewShareNotification n = (NewShareNotification) notification;
        yield new NewShareModel(
            id,
            createdAt,
            type,
            toSnapshotNodeModel(n.getSnapshotNode()),
            toSnapshotUserModel(n.getSnapshotUser()));
      }
      case NotificationTypeCodes.ADDED_NODE -> {
        AddedNodeNotification n = (AddedNodeNotification) notification;
        yield new AddedNodeModel(
            id,
            createdAt,
            type,
            toSnapshotNodeModel(n.getAddedNodeSnapshot()),
            toSnapshotNodeModel(n.getDestinationFolderSnapshot()),
            toSnapshotUserModel(n.getTriggeringUserSnapshot()),
            AddedNodeType.valueOf(n.getAddedNodeType().name()));
      }
      case NotificationTypeCodes.REMOVED_NODE -> {
        RemovedNodeNotification n = (RemovedNodeNotification) notification;
        yield new RemovedNodeModel(
            id,
            createdAt,
            type,
            toSnapshotNodeModel(n.getRemovedNodeSnapshot()),
            toSnapshotNodeModel(n.getOriginFolderSnapshot()),
            toSnapshotUserModel(n.getTriggeringUserSnapshot()),
            RemovedNodeType.valueOf(n.getRemovedNodeType().name()));
      }
      default -> {
        for (NotificationModelContributor contributor : notificationModelContributors) {
          Optional<Notification> model = contributor.toModel(notification);
          if (model.isPresent()) {
            yield model.get();
          }
        }
        throw new IllegalStateException(
            "Unknown notification type: " + notification.getNotificationType());
      }
    };
  }

  @Query("getNotifications")
  public NotificationPageModel getNotifications(
      @Name("update_last_seen") @NonNull Boolean updateLastSeen,
      Integer limit,
      @Name("page_token") String pageToken) {
    String me = requester.getId().getUserId();

    ImmutablePair<List<BaseNotification>, String> findResult =
        filesConfig.areNotificationsEnabled()
            ? notificationRepository.getNotifications(
                me, Optional.ofNullable(limit), Optional.ofNullable(pageToken))
            : new ImmutablePair<>(Collections.emptyList(), null);

    List<Notification> notifications =
        findResult.getLeft().stream().map(this::toNotificationModel).collect(Collectors.toList());

    Optional<UserNotificationsInfo> optUserInfo =
        filesConfig.areNotificationsEnabled()
            ? notificationRepository.getUserNotificationsInfo(me)
            : Optional.empty();

    UserNotificationsInfo userInfo = optUserInfo.orElse(new UserNotificationsInfo(me, 0L, 0));

    if (Boolean.TRUE.equals(updateLastSeen) && filesConfig.areNotificationsEnabled()) {
      notificationRepository.upsertUserNotificationsInfo(me, System.currentTimeMillis(), 0);
    }

    return new NotificationPageModel(
        notifications, userInfo.getUnread(), userInfo.getLastSeen(), findResult.getRight());
  }
}
