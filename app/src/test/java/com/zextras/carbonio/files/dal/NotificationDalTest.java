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
import com.zextras.carbonio.files.dal.dao.ebean.notifications.NewShareNotification;
import com.zextras.carbonio.files.dal.repositories.interfaces.NotificationRepository;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Restores, from the deleted (Phase 7b) white-box {@code NotificationRepositoryIT}, the 3
 * snapshot dedup/reuse scenarios of {@code NotificationRepositoryImpl#createNewShareNotification}:
 * this optimisation (reuse the latest {@code SnapshotNode}/{@code SnapshotUser} row when it still
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

    assertThat(second.getTriggeringUserSnapshotId()).isNotEqualTo(first.getTriggeringUserSnapshotId());
  }
}
