// SPDX-FileCopyrightText: 2025 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.interfaces;

import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.*;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.utils.UserNotificationInterest;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.utils.snapshot.SnapshotNode;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.utils.snapshot.SnapshotUser;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.utils.UserNotificationsInfo;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.AddedNodeType;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.RemovedNodeType;
import com.zextras.carbonio.files.dal.dao.UserMyself;
import jakarta.transaction.Transactional;
import org.apache.commons.lang3.tuple.ImmutablePair;

import java.util.List;
import java.util.Optional;

public interface NotificationRepository {
  ImmutablePair<List<BaseNotification>, String> getNotifications(
    String userId,
    Optional<Integer> limit,
    Optional<String> pageToken
  );

  Optional<UserNotificationsInfo> getUserNotificationsInfo(String userId);

  UserNotificationsInfo updateUserNotificationsInfo(UserNotificationsInfo info);

  UserNotificationsInfo createUserNotificationsInfo(
      String userId);

  Optional<SnapshotUser> getSnapshotUser(String snapshotUserId);

  Optional<SnapshotUser> getLatestSnapshotOfUser(String userId);

  Optional<SnapshotNode> getSnapshotNode(String snapshotNodeId);

  Optional<SnapshotNode> getLatestSnapshotOfNode(String nodeId);

  Optional<UserNotificationInterest> getUserNotification(String userId, String notificationId);

  UserNotificationInterest createUserNotification(UserNotificationsInfo userNotificationsInfo, BaseNotification notification);

  @Transactional
  NewShareNotification createNewShareNotification(Node node, UserMyself triggeringUser, List<String> usersIdsToNotify);

  @Transactional
  AddedNodeNotification createAddedNodeNotification(Node addedNode, Node destinationNode, UserMyself triggeringUser, AddedNodeType type, List<String> usersIdsToNotify);

  @Transactional
  RemovedNodeNotification createRemovedNodeNotification(Node removedNode, Node originNode, UserMyself triggeringUser, RemovedNodeType type, List<String> usersIdsToNotify);
}
