// SPDX-FileCopyrightText: 2025 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl.ebean;

import com.google.inject.Inject;
import com.zextras.carbonio.files.Files;
import com.zextras.carbonio.files.dal.EbeanDatabaseManager;
import com.zextras.carbonio.files.dal.dao.User;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.*;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.utils.NotificationType;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.utils.UserNotificationInterest;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.utils.UserNotificationsInfo;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.utils.snapshot.SnapshotNode;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.utils.snapshot.SnapshotUser;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.AddedNodeType;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.RemovedNodeType;
import com.zextras.carbonio.files.dal.repositories.interfaces.NotificationRepository;
import io.ebean.annotation.Transactional;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public class NotificationRepositoryEbean implements NotificationRepository {

  private static final Logger logger = LoggerFactory.getLogger(NotificationRepositoryEbean.class);

  private EbeanDatabaseManager mDB;

  @Inject
  public NotificationRepositoryEbean(EbeanDatabaseManager ebeanDatabaseManager) {
    mDB = ebeanDatabaseManager;
  }

  public ImmutablePair<List<BaseNotification>, String> getNotifications(
      String userId,
      Optional<Integer> limit,
      Optional<String> pageToken) {

    List<BaseNotification> notifications = doFind(userId, limit.orElse(25), pageToken);
    return new ImmutablePair<>(
        notifications,
        createPageToken(notifications, limit.orElse(25)));
  }

  // This page token is really simple and definitely does not need the complexity of the page token used for the
  // findNodes. It just is the id of the last notification returned, so the next query can start from there.
  private String createPageToken(List<BaseNotification> notification, Integer limit) {
    // Do not return a page token if this is the last page
    if (notification.size() < limit) {
      return null;
    }

    // Maybe the number of notifications is exactly the limit and we'll do an useless
    // query to get the next page, but this is not a big deal.
    return notification.get(notification.size() - 1).getNotificationId();
  }

  // Finds the N most recent notifications for a user.
  // A bit ugly without using native inheritance, but gives the best performances.
  // Also notifications are not update-able, so it's the only ugly query.
  // Since notifications are paginated, I'm not particularly worried about performance.
  @Transactional
  private List<BaseNotification> doFind(String userId, Integer limit, Optional<String> pageToken) {
    AtomicReference<List<String>> notificationIdsAtomic = new AtomicReference<>();

    // Seems difficult but it's really simple: if page token is present,
    // we decode it and get the notifications from the last notification returned (it's id is inside the token);
    // if the token is not present we just return the most recent N notifications.
    pageToken.ifPresentOrElse(
        token -> {
          String lastNotificationId = token;
          mDB.getEbeanDatabase()
              .find(UserNotificationInterest.class)
              .where()
              .eq(Files.Db.UserNotificationInterest.USER_ID, userId)
              .eq(Files.Db.UserNotificationInterest.NOTIFICATION_ID, lastNotificationId)
              .findOneOrEmpty()
              .ifPresentOrElse(userNotification -> {
                Long lastNotificationCreatedAt = userNotification.getCreatedAt();
                notificationIdsAtomic.set(mDB.getEbeanDatabase()
                    .find(UserNotificationInterest.class)
                    .where()
                    .eq(Files.Db.UserNotificationInterest.USER_ID, userId)
                    .lt(Files.Db.UserNotificationInterest.CREATED_AT, lastNotificationCreatedAt)
                    .orderBy()
                    .desc(Files.Db.UserNotificationInterest.CREATED_AT)
                    .orderBy()
                    .desc(Files.Db.UserNotificationInterest.NOTIFICATION_ID) // discriminate on equal timestamps
                    .setMaxRows(limit)
                    .findList()
                    .stream()
                    .map(UserNotificationInterest::getNotificationId)
                    .toList());
              },
              () -> notificationIdsAtomic.set(Collections.emptyList()));
        },
        () -> // Limit and order before joining, so from here we have max N rows from pagination and we can write
            // more readable code with Ebean without caring too much about performance.
            // The alternative was raw sql.
            notificationIdsAtomic.set(mDB.getEbeanDatabase()
                .find(UserNotificationInterest.class)
                .where()
                .eq(Files.Db.UserNotificationInterest.USER_ID, userId)
                .orderBy()
                .desc(Files.Db.UserNotificationInterest.CREATED_AT)
                .orderBy()
                .desc(Files.Db.UserNotificationInterest.NOTIFICATION_ID) // discriminate on equal timestamps
                .setMaxRows(limit)
                .findList()
                .stream()
                .map(UserNotificationInterest::getNotificationId)
                .toList()));

    List<String> notificationIds = notificationIdsAtomic.get();
    if (notificationIds.isEmpty()) {
      return Collections.emptyList();
    }

    // Here we get generic notification objects; since we can't have inheritance we manually fetch results from this list
    List<Notification> notifications =
        mDB.getEbeanDatabase()
            .find(Notification.class)
            .where()
            .in(Files.Db.Notification.NOTIFICATION_ID, notificationIds)
            .findList();

    // Manual fetching of real notifications:
    // We start by mapping ids to the types to make at most one query for each type
    Map<NotificationType, List<String>> groupedIds = notifications.stream()
        .collect(Collectors.groupingBy(
            Notification::getNotificationType,
            Collectors.mapping(Notification::getNotificationId, Collectors.toList())
        ));

    // Using the NotificationType enum we can get the class of the notification and thus do
    // a query for each type, so we can get the real notification objects.
    // It's a bit ugly but doing it this way we can avoid having to update the repository
    // every time we add a new notification type.
    Map<String, BaseNotification> notificationsById = new HashMap<>();
    Arrays.stream(NotificationType.values()).forEach(type -> {
      List<String> idsForType = groupedIds.get(type);
      if (idsForType != null && !idsForType.isEmpty()) {
        Class<? extends BaseNotification> notifClass = type.getNotificationClass();
        List<? extends BaseNotification> resultList =
            mDB.getEbeanDatabase()
                .find(notifClass)
                .where()
                .in(Files.Db.Notification.NOTIFICATION_ID, idsForType)
                .findList();
        resultList.forEach(notif -> notificationsById.put(notif.getNotificationId(), notif));
      }
    });

    // We keep initial order, so they are already ordered by createdAt
    return notificationIds.stream()
        .map(notificationsById::get)
        .collect(Collectors.toList());
  }

  @Override
  public Optional<UserNotificationsInfo> getUserNotificationsInfo(String userId) {
    return mDB.getEbeanDatabase().find(UserNotificationsInfo.class).where().idEq(userId).findOneOrEmpty();
  }

  @Override
  public UserNotificationsInfo updateUserNotificationsInfo(UserNotificationsInfo info) {
    mDB.getEbeanDatabase().update(info);
    return info;
  }

  @Override
  public UserNotificationsInfo createUserNotificationsInfo(
      String userId) {
    UserNotificationsInfo info = new UserNotificationsInfo(userId, System.currentTimeMillis(), 0);
    mDB.getEbeanDatabase().save(info);

    return getUserNotificationsInfo(userId).get();
  }

  @Override
  public Optional<SnapshotUser> getSnapshotUser(String snapshotUserId) {
    return mDB.getEbeanDatabase().find(SnapshotUser.class).where().idEq(snapshotUserId).findOneOrEmpty();
  }

  @Override
  public Optional<SnapshotUser> getLatestSnapshotOfUser(String userId) {
    return mDB.getEbeanDatabase()
        .find(SnapshotUser.class)
        .where()
        .eq(Files.Db.SnapshotUser.USER_ID, userId)
        .orderBy()
        .desc(Files.Db.SnapshotUser.SNAPSHOT_TIMESTAMP)
        .setMaxRows(1)
        .findOneOrEmpty();
  }

  private SnapshotUser createSnapshottedUser(User user) {
    SnapshotUser snapshotUser = new SnapshotUser(
        UUID.randomUUID().toString(),
        System.currentTimeMillis(),
        user.getId(),
        user.getFullName(),
        user.getEmail()
    );

    mDB.getEbeanDatabase().save(snapshotUser);
    return getSnapshotUser(snapshotUser.getSnapshotUserId()).get();
  }

  @Override
  public Optional<SnapshotNode> getSnapshotNode(String snapshotNodeId) {
    return mDB.getEbeanDatabase().find(SnapshotNode.class).where().idEq(snapshotNodeId).findOneOrEmpty();
  }

  @Override
  public Optional<SnapshotNode> getLatestSnapshotOfNode(String nodeId) {
    return mDB.getEbeanDatabase()
        .find(SnapshotNode.class)
        .where()
        .eq(Files.Db.SnapshotNode.NODE_ID, nodeId)
        .orderBy()
        .desc(Files.Db.SnapshotNode.SNAPSHOT_TIMESTAMP)
        .setMaxRows(1)
        .findOneOrEmpty();
  }

  private SnapshotNode createSnapshottedNode(Node node) {
    SnapshotNode snapshotNode = new SnapshotNode(
        UUID.randomUUID().toString(),
        System.currentTimeMillis(),
        node.getId(),
        node.getOwnerId(),
        node.getCreatedAt(),
        node.getNodeType(),
        node.getName()
    );

    mDB.getEbeanDatabase().save(snapshotNode);
    return getSnapshotNode(snapshotNode.getSnapshotNodeId()).get();
  }

  @Override
  public Optional<UserNotificationInterest> getUserNotification(String userId, String notificationId) {
    return mDB.getEbeanDatabase()
        .find(UserNotificationInterest.class)
        .where()
        .eq(Files.Db.UserNotificationInterest.USER_ID, userId)
        .eq(Files.Db.UserNotificationInterest.NOTIFICATION_ID, notificationId)
        .findOneOrEmpty();
  }

  @Override
  public UserNotificationInterest createUserNotification(UserNotificationsInfo userNotificationsInfo, BaseNotification notification) {
    UserNotificationInterest userNotificationInterest = new UserNotificationInterest(
        UUID.randomUUID().toString(),
        userNotificationsInfo.getUserId(),
        notification.getNotificationId(),
        notification.getCreatedAt() // duplicate the createdAt of the notification so we can sort them before joining
    );

    mDB.getEbeanDatabase().save(userNotificationInterest);
    return getUserNotification(userNotificationInterest.getUserId(), userNotificationInterest.getNotificationId()).get();
  }

  private SnapshotNode conditionallySnapshotNode(Node node) {
    AtomicReference<SnapshotNode> snapshotNodeRef = new AtomicReference<>();
    getLatestSnapshotOfNode(node.getId()).ifPresentOrElse(
        snap -> {
          // check if identical, if not still create a snapshot
          if (snap.representNode(node)) {
            logger.debug("Not creating a new snapshot for node {}", node.getId());
            snapshotNodeRef.set(snap);
          } else {
            logger.debug("Found snapshots but not equal to node, creating a new snapshot for node {}", node.getId());
            snapshotNodeRef.set(createSnapshottedNode(node));
          }
        },
        () -> {
          logger.debug("Not found snapshots for node, creating a new snapshot for node {}", node.getId());
          snapshotNodeRef.set(createSnapshottedNode(node));
        }
    );
    return snapshotNodeRef.get();
  }

  private SnapshotUser conditionallySnapshotUser(User user) {
    AtomicReference<SnapshotUser> snapshotUserRef = new AtomicReference<>();
    getLatestSnapshotOfUser(user.getId()).ifPresentOrElse(
        snap -> {
          // check if identical, if not still create a snapshot
          if (snap.representUser(user)) {
            logger.debug("Not creating a new snapshot for user {}", user.getId());
            snapshotUserRef.set(snap);
          } else {
            logger.debug("Found snapshots but not equal to user, creating a new snapshot for user {}", user.getId());
            snapshotUserRef.set(createSnapshottedUser(user));
          }
        },
        () -> {
          logger.debug("Creating a new snapshot for user {}", user.getId());
          snapshotUserRef.set(createSnapshottedUser(user));
        }
    );
    return snapshotUserRef.get();
  }

  private BaseNotification handleNewNotification(BaseNotification notification, List<String> usersIdsToNotify) {
    mDB.getEbeanDatabase().save(notification);

    usersIdsToNotify.forEach(userId -> {
      // Create info for user if not existing
      if (getUserNotificationsInfo(userId).isEmpty()) createUserNotificationsInfo(userId);

      // Increase unread counter
      UserNotificationsInfo userNotificationsInfo = getUserNotificationsInfo(userId).get();
      userNotificationsInfo.setUnread(userNotificationsInfo.getUnread() + 1);
      updateUserNotificationsInfo(userNotificationsInfo);

      // Add user to table with users-notifications
      createUserNotification(userNotificationsInfo, notification);
    });
    return notification;
  }

  // Here we handle the logic of the creation of a new share notification
  // This means snapshotting the node and the user and creating the notification object while also
  // saving the users that will receive the notification; we also have to create
  // UserNotificationsInfo for all interest users if not present, while increasing their unread counter, all in one transaction.
  // Will not comment the others since they are very similar.
  @Override
  @Transactional
  public NewShareNotification createNewShareNotification(Node sharedNode, User triggeringUser, List<String> usersIdsToNotify) {
    // FIRST STEP: SNAPSHOT THE NODE AND THE TRIGGERING USER
    SnapshotNode snapshotNode = conditionallySnapshotNode(sharedNode);
    SnapshotUser snapshotUser = conditionallySnapshotUser(triggeringUser);

    // SECOND STEP: CREATE THE NOTIFICATION
    NewShareNotification newShareNotification = new NewShareNotification(
        UUID.randomUUID().toString(),
        System.currentTimeMillis(),
        snapshotNode.getSnapshotNodeId(),
        snapshotUser.getSnapshotUserId()
    );

    // THIRD STEP: SET USER TO NOTIFY, UPDATING THEIR UNREAD COUNTER (AND CREATING USER INFO IF NOT PRESENT)
    return (NewShareNotification) handleNewNotification(newShareNotification, usersIdsToNotify);
  }

  @Override
  @Transactional
  public AddedNodeNotification createAddedNodeNotification(Node addedNode, Node destinationNode, User triggeringUser, AddedNodeType type, List<String> usersIdsToNotify) {
    // FIRST STEP: SNAPSHOT THE NODES AND THE TRIGGERING USER
    SnapshotNode snapshotAddedNode = conditionallySnapshotNode(addedNode);
    SnapshotNode snapshotDestinationNode = conditionallySnapshotNode(destinationNode);
    SnapshotUser snapshotUser = conditionallySnapshotUser(triggeringUser);

    // SECOND STEP: CREATE THE NOTIFICATION
    AddedNodeNotification addedNodeNotification = new AddedNodeNotification(
        UUID.randomUUID().toString(),
        System.currentTimeMillis(),
        snapshotAddedNode.getSnapshotNodeId(),
        snapshotDestinationNode.getSnapshotNodeId(),
        snapshotUser.getSnapshotUserId(),
        type
    );

    // THIRD STEP: SET USER TO NOTIFY, UPDATING THEIR UNREAD COUNTER (AND CREATING USER INFO IF NOT PRESENT)
    return (AddedNodeNotification) handleNewNotification(addedNodeNotification, usersIdsToNotify);
  }

  @Override
  @Transactional
  public RemovedNodeNotification createRemovedNodeNotification(Node removedNode, Node originNode, User triggeringUser, RemovedNodeType type, List<String> usersIdsToNotify) {
    // FIRST STEP: SNAPSHOT THE NODES AND THE TRIGGERING USER
    SnapshotNode snapshotRemovedNode = conditionallySnapshotNode(removedNode);
    SnapshotNode snapshotOriginNode = conditionallySnapshotNode(originNode);
    SnapshotUser snapshotUser = conditionallySnapshotUser(triggeringUser);

    // SECOND STEP: CREATE THE NOTIFICATION
    RemovedNodeNotification removedNodeNotification = new RemovedNodeNotification(
        UUID.randomUUID().toString(),
        System.currentTimeMillis(),
        snapshotRemovedNode.getSnapshotNodeId(),
        snapshotOriginNode.getSnapshotNodeId(),
        snapshotUser.getSnapshotUserId(),
        type
    );

    // THIRD STEP: SET USER TO NOTIFY, UPDATING THEIR UNREAD COUNTER (AND CREATING USER INFO IF NOT PRESENT)
    return (RemovedNodeNotification) handleNewNotification(removedNodeNotification, usersIdsToNotify);
  }
}
