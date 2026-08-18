// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl;

import com.zextras.carbonio.files.Constants;
import com.zextras.carbonio.files.dal.dao.UserMyself;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.AddedNodeNotification;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.BaseNotification;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.NewShareNotification;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.RemovedNodeNotification;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.utils.NotificationTypeRegistry;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.utils.UserNotificationInterest;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.utils.UserNotificationsInfo;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.utils.snapshot.SnapshotNode;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.utils.snapshot.SnapshotUser;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.AddedNodeType;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.RemovedNodeType;
import com.zextras.carbonio.files.dal.repositories.interfaces.NotificationRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.apache.commons.lang3.tuple.ImmutablePair;

/**
 * Panache/JPA implementation of {@link NotificationRepository}. Replaces the old Ebean-based {@code
 * NotificationRepositoryEbean}.
 *
 * <p>Unlike the Ebean implementation, the concrete notification subtypes ({@link
 * NewShareNotification}, {@link AddedNodeNotification}, {@link RemovedNodeNotification}) are each
 * mapped to their own Hibernate {@code @Entity}/{@code @Table}, so there is no need for the manual
 * {@code NotificationType -> Class} dispatch table the Ebean version used to work around the lack
 * of JPA inheritance: {@link #getNotifications} simply queries each concrete subtype directly
 * (filtering by the id list) and merges the results back into id order.
 *
 * <p>{@link BaseNotification} carries a {@code @OneToOne(cascade = ALL) @MapsId} to the base {@code
 * Notification} row, so persisting a subtype instance cascades the base row insert too.
 *
 * <p>Multiple unrelated entity types are involved here (notifications, snapshots, user notification
 * bookkeeping), so this repository is backed by a directly injected {@link EntityManager} rather
 * than a single {@code PanacheRepositoryBase} (which can only be parameterized for one entity/id
 * pair per class).
 */
@ApplicationScoped
public class NotificationRepositoryImpl implements NotificationRepository {

  private final EntityManager entityManager;
  private final NotificationTypeRegistry notificationTypeRegistry;

  @Inject
  public NotificationRepositoryImpl(
      EntityManager entityManager, NotificationTypeRegistry notificationTypeRegistry) {
    this.entityManager = entityManager;
    this.notificationTypeRegistry = notificationTypeRegistry;
  }

  @Override
  public ImmutablePair<List<BaseNotification>, String> getNotifications(
      String userId, Optional<Integer> limit, Optional<String> pageToken) {
    int max = limit.orElse(25);
    List<String> notificationIds = findNotificationIdsForPage(userId, max, pageToken);
    List<BaseNotification> notifications = fetchNotificationsByIds(notificationIds);

    // The page token is simply the id of the last notification returned, in createdAt-desc order,
    // so the next query can resume from there. No token is returned once the last page is hit.
    String nextPageToken =
        notifications.size() < max
            ? null
            : notifications.get(notifications.size() - 1).getNotificationId();

    return new ImmutablePair<>(notifications, nextPageToken);
  }

  private List<String> findNotificationIdsForPage(
      String userId, int max, Optional<String> pageToken) {
    if (pageToken.isPresent()) {
      Optional<UserNotificationInterest> cursor = getUserNotification(userId, pageToken.get());
      if (cursor.isEmpty()) {
        return List.of();
      }
      return entityManager
          .createQuery(
              "select i.notificationId from UserNotificationInterest i "
                  + "where i.userId = :userId and i.createdAt < :cursor "
                  + "order by i.createdAt desc, i.notificationId desc",
              String.class)
          .setParameter("userId", userId)
          .setParameter("cursor", cursor.get().getCreatedAt())
          .setMaxResults(max)
          .getResultList();
    }

    return entityManager
        .createQuery(
            "select i.notificationId from UserNotificationInterest i where i.userId = :userId "
                + "order by i.createdAt desc, i.notificationId desc",
            String.class)
        .setParameter("userId", userId)
        .setMaxResults(max)
        .getResultList();
  }

  private List<BaseNotification> fetchNotificationsByIds(List<String> notificationIds) {
    if (notificationIds.isEmpty()) {
      return List.of();
    }

    // Query each registered notification subtype (CE built-ins + any Advanced additions), driven by
    // the NotificationTypeRegistry instead of a closed enum -> Class dispatch table.
    Map<String, BaseNotification> notificationsById = new HashMap<>();
    for (Class<? extends BaseNotification> notificationClass :
        notificationTypeRegistry.notificationClasses()) {
      entityManager
          .createQuery(
              "select n from "
                  + notificationClass.getSimpleName()
                  + " n where n.notificationId in :ids",
              notificationClass)
          .setParameter("ids", notificationIds)
          .getResultList()
          .forEach(n -> notificationsById.put(n.getNotificationId(), n));
    }

    // Keep the original (createdAt-desc) order established by the id query.
    return notificationIds.stream().map(notificationsById::get).filter(Objects::nonNull).toList();
  }

  @Override
  public Optional<UserNotificationsInfo> getUserNotificationsInfo(String userId) {
    return Optional.ofNullable(entityManager.find(UserNotificationsInfo.class, userId));
  }

  @Override
  @Transactional
  public UserNotificationsInfo updateUserNotificationsInfo(UserNotificationsInfo info) {
    return entityManager.merge(info);
  }

  @Override
  @Transactional
  public UserNotificationsInfo createUserNotificationsInfo(String userId) {
    UserNotificationsInfo info = new UserNotificationsInfo(userId, System.currentTimeMillis(), 0);
    entityManager.persist(info);
    return info;
  }

  @Override
  @Transactional
  public UserNotificationsInfo upsertUserNotificationsInfo(
      String userId, long lastSeen, int unread) {
    entityManager
        .createNativeQuery(
            "INSERT INTO "
                + Constants.Db.Tables.USER_NOTIFICATIONS_INFO
                + " (user_id, last_seen, unread) VALUES (:userId, :lastSeen, :unread) "
                + "ON CONFLICT (user_id) DO UPDATE SET last_seen = EXCLUDED.last_seen, unread ="
                + " EXCLUDED.unread")
        .setParameter("userId", userId)
        .setParameter("lastSeen", lastSeen)
        .setParameter("unread", unread)
        .executeUpdate();
    return new UserNotificationsInfo(userId, lastSeen, unread);
  }

  @Override
  public Optional<SnapshotUser> getSnapshotUser(String snapshotUserId) {
    return Optional.ofNullable(entityManager.find(SnapshotUser.class, snapshotUserId));
  }

  @Override
  public Optional<SnapshotUser> getLatestSnapshotOfUser(String userId) {
    return entityManager
        .createQuery(
            "select s from SnapshotUser s where s.userId = :userId order by s.snapshotTimestamp"
                + " desc",
            SnapshotUser.class)
        .setParameter("userId", userId)
        .setMaxResults(1)
        .getResultList()
        .stream()
        .findFirst();
  }

  @Override
  public Optional<SnapshotNode> getSnapshotNode(String snapshotNodeId) {
    return Optional.ofNullable(entityManager.find(SnapshotNode.class, snapshotNodeId));
  }

  @Override
  public Optional<SnapshotNode> getLatestSnapshotOfNode(String nodeId) {
    return entityManager
        .createQuery(
            "select s from SnapshotNode s where s.nodeId = :nodeId order by s.snapshotTimestamp"
                + " desc",
            SnapshotNode.class)
        .setParameter("nodeId", nodeId)
        .setMaxResults(1)
        .getResultList()
        .stream()
        .findFirst();
  }

  @Override
  public Optional<UserNotificationInterest> getUserNotification(
      String userId, String notificationId) {
    return entityManager
        .createQuery(
            "select i from UserNotificationInterest i "
                + "where i.userId = :userId and i.notificationId = :notificationId",
            UserNotificationInterest.class)
        .setParameter("userId", userId)
        .setParameter("notificationId", notificationId)
        .getResultList()
        .stream()
        .findFirst();
  }

  @Override
  @Transactional
  public UserNotificationInterest createUserNotification(
      UserNotificationsInfo userNotificationsInfo, BaseNotification notification) {
    UserNotificationInterest interest =
        new UserNotificationInterest(
            UUID.randomUUID().toString(),
            userNotificationsInfo.getUserId(),
            notification.getNotificationId(),
            // Duplicate the notification's createdAt so the interest rows can be sorted/paginated
            // without joining back to the notification tables.
            notification.getCreatedAt());
    entityManager.persist(interest);
    return interest;
  }

  /**
   * Returns the latest snapshot of the given node if it already represents it unchanged, otherwise
   * persists (and returns) a brand-new snapshot.
   */
  private SnapshotNode conditionallySnapshotNode(Node node) {
    Optional<SnapshotNode> latest = getLatestSnapshotOfNode(node.getId());
    if (latest.isPresent() && latest.get().representNode(node)) {
      return latest.get();
    }

    SnapshotNode snapshotNode =
        new SnapshotNode(
            UUID.randomUUID().toString(),
            System.currentTimeMillis(),
            node.getId(),
            node.getOwnerId(),
            node.getCreatedAt(),
            node.getNodeType(),
            node.getName());
    entityManager.persist(snapshotNode);
    return snapshotNode;
  }

  /**
   * Returns the latest snapshot of the given user if it already represents it unchanged, otherwise
   * persists (and returns) a brand-new snapshot.
   */
  private SnapshotUser conditionallySnapshotUser(UserMyself user) {
    Optional<SnapshotUser> latest = getLatestSnapshotOfUser(user.getId().getUserId());
    if (latest.isPresent() && latest.get().representUser(user)) {
      return latest.get();
    }

    SnapshotUser snapshotUser =
        new SnapshotUser(
            UUID.randomUUID().toString(),
            System.currentTimeMillis(),
            user.getId().getUserId(),
            user.getFullName(),
            user.getEmail());
    entityManager.persist(snapshotUser);
    return snapshotUser;
  }

  /**
   * Persists the notification (cascading the base {@code Notification} row) and, for each user to
   * notify, creates their {@link UserNotificationsInfo} if missing, bumps their unread counter, and
   * records their interest in the new notification.
   */
  private BaseNotification notifyUsers(
      BaseNotification notification, List<String> usersIdsToNotify) {
    entityManager.persist(notification);

    usersIdsToNotify.forEach(
        userId -> {
          UserNotificationsInfo existing =
              getUserNotificationsInfo(userId)
                  .orElse(new UserNotificationsInfo(userId, System.currentTimeMillis(), 0));
          UserNotificationsInfo info =
              upsertUserNotificationsInfo(userId, existing.getLastSeen(), existing.getUnread() + 1);
          createUserNotification(info, notification);
        });

    return notification;
  }

  @Override
  @Transactional
  public NewShareNotification createNewShareNotification(
      Node sharedNode, UserMyself triggeringUser, List<String> usersIdsToNotify) {
    SnapshotNode snapshotNode = conditionallySnapshotNode(sharedNode);
    SnapshotUser snapshotUser = conditionallySnapshotUser(triggeringUser);

    NewShareNotification notification =
        new NewShareNotification(
            UUID.randomUUID().toString(),
            System.currentTimeMillis(),
            snapshotNode.getSnapshotNodeId(),
            snapshotUser.getSnapshotUserId());

    return (NewShareNotification) notifyUsers(notification, usersIdsToNotify);
  }

  @Override
  @Transactional
  public AddedNodeNotification createAddedNodeNotification(
      Node addedNode,
      Node destinationNode,
      UserMyself triggeringUser,
      AddedNodeType type,
      List<String> usersIdsToNotify) {
    SnapshotNode snapshotAddedNode = conditionallySnapshotNode(addedNode);
    SnapshotNode snapshotDestinationNode = conditionallySnapshotNode(destinationNode);
    SnapshotUser snapshotUser = conditionallySnapshotUser(triggeringUser);

    AddedNodeNotification notification =
        new AddedNodeNotification(
            UUID.randomUUID().toString(),
            System.currentTimeMillis(),
            snapshotAddedNode.getSnapshotNodeId(),
            snapshotDestinationNode.getSnapshotNodeId(),
            snapshotUser.getSnapshotUserId(),
            type);

    return (AddedNodeNotification) notifyUsers(notification, usersIdsToNotify);
  }

  @Override
  @Transactional
  public RemovedNodeNotification createRemovedNodeNotification(
      Node removedNode,
      Node originNode,
      UserMyself triggeringUser,
      RemovedNodeType type,
      List<String> usersIdsToNotify) {
    SnapshotNode snapshotRemovedNode = conditionallySnapshotNode(removedNode);
    SnapshotNode snapshotOriginNode = conditionallySnapshotNode(originNode);
    SnapshotUser snapshotUser = conditionallySnapshotUser(triggeringUser);

    RemovedNodeNotification notification =
        new RemovedNodeNotification(
            UUID.randomUUID().toString(),
            System.currentTimeMillis(),
            snapshotRemovedNode.getSnapshotNodeId(),
            snapshotOriginNode.getSnapshotNodeId(),
            snapshotUser.getSnapshotUserId(),
            type);

    return (RemovedNodeNotification) notifyUsers(notification, usersIdsToNotify);
  }
}
