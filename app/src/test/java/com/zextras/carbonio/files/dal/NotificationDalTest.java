// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zextras.carbonio.files.FilesStackTestResource;
import com.zextras.carbonio.files.dal.dao.UserId;
import com.zextras.carbonio.files.dal.dao.UserMyself;
import com.zextras.carbonio.files.dal.dao.UserStatus;
import com.zextras.carbonio.files.dal.dao.UserType;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.NewShareNotification;
import com.zextras.carbonio.files.dal.repositories.interfaces.NotificationRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.narayana.jta.QuarkusTransactionException;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Restores, from the deleted (Phase 7b) white-box {@code NotificationRepositoryIT}, the 3 snapshot
 * dedup/reuse scenarios of {@code NotificationRepositoryImpl#createNewShareNotification}: this
 * optimisation (reuse the latest {@code SnapshotNode}/{@code SnapshotUser} row when it still
 * represents the current node/user, otherwise persist a fresh one) has NO API-observable signature
 * — every code path returns a structurally identical {@code NewShareNotification} — which is
 * exactly why it needs a direct test: nothing black-box would ever catch a regression here.
 *
 * <p><b>Why {@code @QuarkusTest}, and why the {@code *DalTest} suffix (not {@code *IT}):</b> this
 * class needs a real database and CDI-injected repositories, so it must be in-process (JVM,
 * {@code @QuarkusTest} + {@link FilesStackTestResource}) — no hybrid, out-of-process alternative
 * exists. Naming it {@code *DalTest} instead of {@code *IT} routes it to Maven's default
 * <b>surefire</b> {@code *Test} inclusion pattern rather than <b>failsafe</b>'s {@code *IT}
 * pattern, so it runs during {@code test}, never during {@code integration-test}. That keeps the
 * failsafe IT suite 100% out-of-process ({@code @QuarkusIntegrationTest}), so {@code verify
 * -Dnative} still exercises the whole IT suite against the packaged NATIVE binary. This class
 * exercises SQL, not the packaged binary, so it never needed native coverage in the first place —
 * excluding it from the native run costs nothing.
 */
@QuarkusTest
@QuarkusTestResource(FilesStackTestResource.class)
class NotificationDalTest {

  @Inject NotificationRepository notificationRepository;
  @Inject EntityManager entityManager;

  @AfterEach
  void deleteCommittedRows() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              entityManager
                  .createNativeQuery("DELETE FROM node WHERE creator_id = 'creator-id'")
                  .executeUpdate();
              entityManager
                  .createNativeQuery(
                      "TRUNCATE user_notification_interest, notification, snapshot_node,"
                          + " snapshot_user, user_notifications_info CASCADE")
                  .executeUpdate();
            });
  }

  private static String id() {
    return UUID.randomUUID().toString();
  }

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

  @Test
  @TestTransaction
  void createNewShareNotificationShouldReuseNodeAndUserSnapshotsWhenUnchanged() {
    String nodeId = id();
    String ownerId = id();
    Node node = persistNode(nodeId, ownerId, "shared-node");
    UserMyself triggeringUser = buildUser(ownerId, "Owner Reused");

    NewShareNotification first =
        notificationRepository.createNewShareNotification(node, triggeringUser, List.of(id()));
    NewShareNotification second =
        notificationRepository.createNewShareNotification(node, triggeringUser, List.of(id()));

    assertThat(second.getNodeSnapshotId()).isEqualTo(first.getNodeSnapshotId());
    assertThat(second.getTriggeringUserSnapshotId()).isEqualTo(first.getTriggeringUserSnapshotId());
  }

  @Test
  @TestTransaction
  void createNewShareNotificationShouldCreateNewNodeSnapshotWhenNodeChanged() {
    String nodeId = id();
    String ownerId = id();
    Node node = persistNode(nodeId, ownerId, "original-name");
    UserMyself triggeringUser = buildUser(ownerId, "Owner Stable");

    NewShareNotification first =
        notificationRepository.createNewShareNotification(node, triggeringUser, List.of(id()));

    node.setName("renamed");
    NewShareNotification second =
        notificationRepository.createNewShareNotification(node, triggeringUser, List.of(id()));

    assertThat(second.getNodeSnapshotId()).isNotEqualTo(first.getNodeSnapshotId());
  }

  @Test
  @TestTransaction
  void createNewShareNotificationShouldCreateNewUserSnapshotWhenTriggeringUserChanged() {
    String nodeId = id();
    String ownerId = id();
    Node node = persistNode(nodeId, ownerId, "shared-node");
    UserMyself triggeringUser = buildUser(ownerId, "Original Name");

    NewShareNotification first =
        notificationRepository.createNewShareNotification(node, triggeringUser, List.of(id()));

    UserMyself changedUser = buildUser(ownerId, "Changed Name");
    NewShareNotification second =
        notificationRepository.createNewShareNotification(node, changedUser, List.of(id()));

    assertThat(second.getTriggeringUserSnapshotId())
        .isNotEqualTo(first.getTriggeringUserSnapshotId());
  }

  @Test
  void createNotificationsForTheSameUserInOneTransactionShouldIncrementUnreadEachTime() {
    String ownerId = id();
    String receiverId = id();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Node node = persistNode(id(), ownerId, "shared-node");
              UserMyself triggeringUser = buildUser(ownerId, "Owner");
              for (int i = 0; i < 3; i++) {
                notificationRepository.createNewShareNotification(
                    node, triggeringUser, List.of(receiverId));
              }
            });

    Number unread =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    (Number)
                        entityManager
                            .createNativeQuery(
                                "select unread from user_notifications_info where user_id = :u")
                            .setParameter("u", receiverId)
                            .getSingleResult());
    assertThat(unread.intValue()).isEqualTo(3);
  }

  @Test
  void notificationsToSeveralUsersInOneTransactionShouldBumpEachUserOncePerNotification() {
    String ownerId = id();
    String firstReceiverId = id();
    String secondReceiverId = id();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Node node = persistNode(id(), ownerId, "shared-node");
              UserMyself triggeringUser = buildUser(ownerId, "Owner");
              notificationRepository.createNewShareNotification(
                  node, triggeringUser, List.of(firstReceiverId, secondReceiverId));
              notificationRepository.createNewShareNotification(
                  node, triggeringUser, List.of(secondReceiverId));
            });

    assertThat(unreadOf(firstReceiverId)).isEqualTo(1);
    assertThat(unreadOf(secondReceiverId)).isEqualTo(2);
  }

  @Test
  void notificationsInARollbackOnlyTransactionShouldNotBumpUnread() {
    String ownerId = id();
    String receiverId = id();
    assertThatThrownBy(
            () ->
                QuarkusTransaction.requiringNew()
                    .run(
                        () -> {
                          Node node = persistNode(id(), ownerId, "shared-node");
                          entityManager.flush();
                          QuarkusTransaction.setRollbackOnly();
                          notificationRepository.createNewShareNotification(
                              node, buildUser(ownerId, "Owner"), List.of(receiverId));
                        }))
        .isInstanceOf(QuarkusTransactionException.class);

    assertThat(unreadOf(receiverId)).isZero();
  }

  private int unreadOf(String userId) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                entityManager
                    .createNativeQuery(
                        "select unread from user_notifications_info where user_id = :u")
                    .setParameter("u", userId)
                    .getResultList()
                    .stream()
                    .mapToInt(unread -> ((Number) unread).intValue())
                    .sum());
  }
}
