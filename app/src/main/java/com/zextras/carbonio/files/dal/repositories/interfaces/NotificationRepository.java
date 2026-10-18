// SPDX-FileCopyrightText: 2025 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.interfaces;

import com.zextras.carbonio.files.dal.dao.UserMyself;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.*;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.utils.UserNotificationInterest;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.utils.UserNotificationsInfo;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.utils.snapshot.SnapshotNode;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.utils.snapshot.SnapshotUser;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.AddedNodeType;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.RemovedNodeType;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.Optional;
import org.apache.commons.lang3.tuple.ImmutablePair;

public interface NotificationRepository {
  ImmutablePair<List<BaseNotification>, String> getNotifications(
      String userId, Optional<Integer> limit, Optional<String> pageToken);

  Optional<UserNotificationsInfo> getUserNotificationsInfo(String userId);

  UserNotificationsInfo upsertUserNotificationsInfo(String userId, long lastSeen, int unread);

  Optional<SnapshotUser> getSnapshotUser(String snapshotUserId);

  Optional<SnapshotUser> getLatestSnapshotOfUser(String userId);

  Optional<SnapshotNode> getSnapshotNode(String snapshotNodeId);

  Optional<SnapshotNode> getLatestSnapshotOfNode(String nodeId);

  Optional<UserNotificationInterest> getUserNotification(String userId, String notificationId);

  /**
   * Persists the notification and, for each user to notify, records their interest in it. The
   * unread counters are bumped right after the commit.
   */
  @Transactional
  BaseNotification notifyUsers(BaseNotification notification, List<String> usersIdsToNotify);

  @Transactional
  NewShareNotification createNewShareNotification(
      Node node, UserMyself triggeringUser, List<String> usersIdsToNotify);

  @Transactional
  AddedNodeNotification createAddedNodeNotification(
      Node addedNode,
      Node destinationNode,
      UserMyself triggeringUser,
      AddedNodeType type,
      List<String> usersIdsToNotify);

  @Transactional
  RemovedNodeNotification createRemovedNodeNotification(
      Node removedNode,
      Node originNode,
      UserMyself triggeringUser,
      RemovedNodeType type,
      List<String> usersIdsToNotify);
}
