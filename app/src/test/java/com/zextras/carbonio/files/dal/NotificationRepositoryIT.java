// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal;

import static org.assertj.core.api.Assertions.assertThat;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.dal.dao.UserId;
import com.zextras.carbonio.files.dal.dao.UserMyself;
import com.zextras.carbonio.files.dal.dao.UserStatus;
import com.zextras.carbonio.files.dal.dao.UserType;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.AddedNodeNotification;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.BaseNotification;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.NewShareNotification;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.RemovedNodeNotification;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.utils.UserNotificationInterest;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.utils.UserNotificationsInfo;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.utils.snapshot.SnapshotNode;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.utils.snapshot.SnapshotUser;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.AddedNodeType;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.RemovedNodeType;
import com.zextras.carbonio.files.dal.repositories.interfaces.NotificationRepository;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.junit.jupiter.api.Test;

/**
 * P2c: validates the clean Panache/JPA {@code NotificationRepositoryImpl}, which replaces {@code
 * NotificationRepositoryEbean}.
 */
@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class NotificationRepositoryIT {

  @Inject NotificationRepository notificationRepository;

  @Inject EntityManager entityManager;

  private Node persistNode(String nodeId, String ownerId, String name) {
    Node node =
        new Node(
            nodeId,
            "creator-id",
            ownerId,
            "",
            1L,
            1L,
            name,
            "description",
            NodeType.FOLDER,
            "",
            0L);
    entityManager.persist(node);
    return node;
  }

  private UserMyself buildUser(String userId, String fullName) {
    return new UserMyself(
        new UserId(userId),
        userId + "@example.com",
        fullName,
        "example.com",
        UserStatus.ACTIVE,
        Locale.ENGLISH,
        UserType.INTERNAL,
        List.<String>of());
  }

  private SnapshotNode persistSnapshotNode(String snapshotNodeId, String nodeId, long timestamp) {
    SnapshotNode snapshot =
        new SnapshotNode(
            snapshotNodeId, timestamp, nodeId, "owner-id", timestamp, NodeType.FOLDER,
            "snapshot-name");
    entityManager.persist(snapshot);
    return snapshot;
  }

  private SnapshotUser persistSnapshotUser(String snapshotUserId, String userId, long timestamp) {
    SnapshotUser snapshot =
        new SnapshotUser(snapshotUserId, timestamp, userId, "Full Name", userId + "@example.com");
    entityManager.persist(snapshot);
    return snapshot;
  }

  private void persistInterest(String userId, String notificationId, long createdAt) {
    entityManager.persist(
        new UserNotificationInterest(UUID.randomUUID().toString(), userId, notificationId, createdAt));
  }

  @Test
  @TestTransaction
  void createNewShareNotificationShouldSnapshotNotifyAndBeRetrievable() {
    String nodeId = "77777777-7777-7777-7777-777777777701";
    Node node = persistNode(nodeId, "owner-1", "shared-node");
    UserMyself triggeringUser = buildUser("owner-1", "Owner One");

    NewShareNotification notification =
        notificationRepository.createNewShareNotification(
            node, triggeringUser, List.of("target-user-1"));

    assertThat(notification.getNotificationId()).isNotBlank();
    assertThat(notification.getNodeSnapshotId()).isNotBlank();
    assertThat(notification.getTriggeringUserSnapshotId()).isNotBlank();

    assertThat(notificationRepository.getSnapshotNode(notification.getNodeSnapshotId()))
        .hasValueSatisfying(snap -> assertThat(snap.getNodeId()).isEqualTo(nodeId));
    assertThat(
            notificationRepository.getSnapshotUser(notification.getTriggeringUserSnapshotId()))
        .hasValueSatisfying(snap -> assertThat(snap.getUserId()).isEqualTo("owner-1"));

    assertThat(notificationRepository.getUserNotificationsInfo("target-user-1"))
        .hasValueSatisfying(info -> assertThat(info.getUnread()).isEqualTo(1));
    assertThat(
            notificationRepository.getUserNotification(
                "target-user-1", notification.getNotificationId()))
        .isPresent();
  }

  @Test
  @TestTransaction
  void createNewShareNotificationShouldReuseNodeAndUserSnapshotsWhenUnchanged() {
    String nodeId = "77777777-7777-7777-7777-777777777702";
    Node node = persistNode(nodeId, "owner-2", "shared-node");
    UserMyself triggeringUser = buildUser("owner-2", "Owner Two");

    NewShareNotification first =
        notificationRepository.createNewShareNotification(node, triggeringUser, List.of("t1"));
    NewShareNotification second =
        notificationRepository.createNewShareNotification(node, triggeringUser, List.of("t2"));

    assertThat(second.getNodeSnapshotId()).isEqualTo(first.getNodeSnapshotId());
    assertThat(second.getTriggeringUserSnapshotId()).isEqualTo(first.getTriggeringUserSnapshotId());
  }

  @Test
  @TestTransaction
  void createNewShareNotificationShouldCreateNewNodeSnapshotWhenNodeChanged() {
    String nodeId = "77777777-7777-7777-7777-777777777703";
    Node node = persistNode(nodeId, "owner-3", "original-name");
    UserMyself triggeringUser = buildUser("owner-3", "Owner Three");

    NewShareNotification first =
        notificationRepository.createNewShareNotification(node, triggeringUser, List.of("t1"));

    node.setName("renamed");
    NewShareNotification second =
        notificationRepository.createNewShareNotification(node, triggeringUser, List.of("t1"));

    assertThat(second.getNodeSnapshotId()).isNotEqualTo(first.getNodeSnapshotId());
  }

  @Test
  @TestTransaction
  void createNewShareNotificationShouldCreateNewUserSnapshotWhenTriggeringUserChanged() {
    String nodeId = "77777777-7777-7777-7777-777777777704";
    Node node = persistNode(nodeId, "owner-4", "shared-node");
    UserMyself triggeringUser = buildUser("owner-4", "Original Name");

    NewShareNotification first =
        notificationRepository.createNewShareNotification(node, triggeringUser, List.of("t1"));

    UserMyself changedUser = buildUser("owner-4", "Changed Name");
    NewShareNotification second =
        notificationRepository.createNewShareNotification(node, changedUser, List.of("t1"));

    assertThat(second.getTriggeringUserSnapshotId()).isNotEqualTo(first.getTriggeringUserSnapshotId());
  }

  @Test
  @TestTransaction
  void createAddedNodeNotificationShouldPersistAndNotifyAllUsers() {
    String addedNodeId = "77777777-7777-7777-7777-777777777705";
    String destinationNodeId = "77777777-7777-7777-7777-777777777706";
    Node addedNode = persistNode(addedNodeId, "owner-5", "added-node");
    Node destinationNode = persistNode(destinationNodeId, "owner-5", "destination-node");
    UserMyself triggeringUser = buildUser("owner-5", "Owner Five");

    AddedNodeNotification notification =
        notificationRepository.createAddedNodeNotification(
            addedNode, destinationNode, triggeringUser, AddedNodeType.MOVE, List.of("t1", "t2"));

    assertThat(notification.getAddedNodeType()).isEqualTo(AddedNodeType.MOVE);
    assertThat(notificationRepository.getUserNotificationsInfo("t1"))
        .hasValueSatisfying(info -> assertThat(info.getUnread()).isEqualTo(1));
    assertThat(notificationRepository.getUserNotificationsInfo("t2"))
        .hasValueSatisfying(info -> assertThat(info.getUnread()).isEqualTo(1));
    assertThat(
            notificationRepository.getUserNotification("t1", notification.getNotificationId()))
        .isPresent();
    assertThat(
            notificationRepository.getUserNotification("t2", notification.getNotificationId()))
        .isPresent();
  }

  @Test
  @TestTransaction
  void createRemovedNodeNotificationShouldPersistAndNotifyUsers() {
    String removedNodeId = "77777777-7777-7777-7777-777777777707";
    String originNodeId = "77777777-7777-7777-7777-777777777708";
    Node removedNode = persistNode(removedNodeId, "owner-6", "removed-node");
    Node originNode = persistNode(originNodeId, "owner-6", "origin-node");
    UserMyself triggeringUser = buildUser("owner-6", "Owner Six");

    RemovedNodeNotification notification =
        notificationRepository.createRemovedNodeNotification(
            removedNode, originNode, triggeringUser, RemovedNodeType.DELETE, List.of("t3"));

    assertThat(notification.getRemovedNodeType()).isEqualTo(RemovedNodeType.DELETE);
    assertThat(notificationRepository.getUserNotificationsInfo("t3"))
        .hasValueSatisfying(info -> assertThat(info.getUnread()).isEqualTo(1));
    assertThat(
            notificationRepository.getUserNotification("t3", notification.getNotificationId()))
        .isPresent();
  }

  @Test
  @TestTransaction
  void createUserNotificationsInfoAndUpdateShouldPersistUnreadAndLastSeen() {
    String userId = "info-user-1";

    UserNotificationsInfo created = notificationRepository.createUserNotificationsInfo(userId);
    assertThat(created.getUnread()).isEqualTo(0);

    created.setUnread(5);
    created.setLastSeen(12345L);
    notificationRepository.updateUserNotificationsInfo(created);

    assertThat(notificationRepository.getUserNotificationsInfo(userId))
        .hasValueSatisfying(
            info -> {
              assertThat(info.getUnread()).isEqualTo(5);
              assertThat(info.getLastSeen()).isEqualTo(12345L);
            });
  }

  @Test
  @TestTransaction
  void getUserNotificationsInfoShouldReturnEmptyWhenMissing() {
    assertThat(notificationRepository.getUserNotificationsInfo("missing-user")).isEmpty();
  }

  @Test
  @TestTransaction
  void getUserNotificationShouldReturnEmptyWhenMissing() {
    assertThat(notificationRepository.getUserNotification("missing-user", "missing-notification"))
        .isEmpty();
  }

  @Test
  @TestTransaction
  void getSnapshotNodeAndGetSnapshotUserShouldReturnEmptyWhenMissing() {
    assertThat(notificationRepository.getSnapshotNode("missing-snapshot-node")).isEmpty();
    assertThat(notificationRepository.getSnapshotUser("missing-snapshot-user")).isEmpty();
    assertThat(notificationRepository.getLatestSnapshotOfNode("missing-node")).isEmpty();
    assertThat(notificationRepository.getLatestSnapshotOfUser("missing-user")).isEmpty();
  }

  @Test
  @TestTransaction
  void getNotificationsShouldReturnAllSubtypesOrderedByCreatedAtDescending() {
    String userId = "list-user-1";
    entityManager.persist(new UserNotificationsInfo(userId, 0L, 0));

    SnapshotNode snapshotNode = persistSnapshotNode("snap-node-list-1", "node-list-1", 1_000L);
    SnapshotNode destinationSnapshot =
        persistSnapshotNode("snap-node-list-2", "node-list-2", 1_000L);
    SnapshotUser snapshotUser = persistSnapshotUser("snap-user-list-1", "trigger-user-list-1", 1_000L);

    entityManager.persist(
        new NewShareNotification(
            "notif-list-1", 1_000L, snapshotNode.getSnapshotNodeId(), snapshotUser.getSnapshotUserId()));
    entityManager.persist(
        new AddedNodeNotification(
            "notif-list-2",
            2_000L,
            snapshotNode.getSnapshotNodeId(),
            destinationSnapshot.getSnapshotNodeId(),
            snapshotUser.getSnapshotUserId(),
            AddedNodeType.CREATE));
    entityManager.persist(
        new RemovedNodeNotification(
            "notif-list-3",
            3_000L,
            snapshotNode.getSnapshotNodeId(),
            destinationSnapshot.getSnapshotNodeId(),
            snapshotUser.getSnapshotUserId(),
            RemovedNodeType.DELETE));

    persistInterest(userId, "notif-list-1", 1_000L);
    persistInterest(userId, "notif-list-2", 2_000L);
    persistInterest(userId, "notif-list-3", 3_000L);

    ImmutablePair<List<BaseNotification>, String> page =
        notificationRepository.getNotifications(userId, Optional.empty(), Optional.empty());

    assertThat(page.getLeft())
        .extracting(BaseNotification::getNotificationId)
        .containsExactly("notif-list-3", "notif-list-2", "notif-list-1");
    assertThat(page.getLeft().get(0)).isInstanceOf(RemovedNodeNotification.class);
    assertThat(page.getLeft().get(1)).isInstanceOf(AddedNodeNotification.class);
    assertThat(page.getLeft().get(2)).isInstanceOf(NewShareNotification.class);
    assertThat(page.getRight()).isNull();
  }

  @Test
  @TestTransaction
  void getNotificationsShouldPaginateUsingPageToken() {
    String userId = "page-user-1";
    entityManager.persist(new UserNotificationsInfo(userId, 0L, 0));

    SnapshotNode snapshotNode = persistSnapshotNode("snap-node-page-1", "node-page-1", 1_000L);
    SnapshotUser snapshotUser = persistSnapshotUser("snap-user-page-1", "trigger-user-page-1", 1_000L);

    entityManager.persist(
        new NewShareNotification(
            "notif-page-1", 1_000L, snapshotNode.getSnapshotNodeId(), snapshotUser.getSnapshotUserId()));
    entityManager.persist(
        new NewShareNotification(
            "notif-page-2", 2_000L, snapshotNode.getSnapshotNodeId(), snapshotUser.getSnapshotUserId()));
    entityManager.persist(
        new NewShareNotification(
            "notif-page-3", 3_000L, snapshotNode.getSnapshotNodeId(), snapshotUser.getSnapshotUserId()));

    persistInterest(userId, "notif-page-1", 1_000L);
    persistInterest(userId, "notif-page-2", 2_000L);
    persistInterest(userId, "notif-page-3", 3_000L);

    ImmutablePair<List<BaseNotification>, String> firstPage =
        notificationRepository.getNotifications(userId, Optional.of(2), Optional.empty());

    assertThat(firstPage.getLeft())
        .extracting(BaseNotification::getNotificationId)
        .containsExactly("notif-page-3", "notif-page-2");
    assertThat(firstPage.getRight()).isEqualTo("notif-page-2");

    ImmutablePair<List<BaseNotification>, String> secondPage =
        notificationRepository.getNotifications(
            userId, Optional.of(2), Optional.of(firstPage.getRight()));

    assertThat(secondPage.getLeft())
        .extracting(BaseNotification::getNotificationId)
        .containsExactly("notif-page-1");
    assertThat(secondPage.getRight()).isNull();
  }

  @Test
  @TestTransaction
  void getNotificationsShouldReturnEmptyWhenPageTokenIsNotFound() {
    String userId = "page-user-2";

    ImmutablePair<List<BaseNotification>, String> page =
        notificationRepository.getNotifications(
            userId, Optional.of(2), Optional.of("unknown-notification-id"));

    assertThat(page.getLeft()).isEmpty();
    assertThat(page.getRight()).isNull();
  }
}
