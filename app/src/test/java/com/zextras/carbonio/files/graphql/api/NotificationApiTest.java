// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zextras.carbonio.files.config.FilesConfig;
import com.zextras.carbonio.files.dal.dao.UserId;
import com.zextras.carbonio.files.dal.dao.UserMyself;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.AddedNodeNotification;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.NewShareNotification;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.RemovedNodeNotification;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.utils.UserNotificationsInfo;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.utils.snapshot.SnapshotNode;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.utils.snapshot.SnapshotUser;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.AddedNodeType;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.RemovedNodeType;
import com.zextras.carbonio.files.dal.repositories.interfaces.NotificationRepository;
import com.zextras.carbonio.files.graphql.model.AddedNodeModel;
import com.zextras.carbonio.files.graphql.model.NewShareModel;
import com.zextras.carbonio.files.graphql.model.NotificationPageModel;
import com.zextras.carbonio.files.graphql.model.RemovedNodeModel;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NotificationApiTest {

  private static final String REQUESTER_ID = "requester-id";

  private FilesConfig filesConfig;
  private NotificationRepository notificationRepository;
  private NotificationApi notificationApi;

  @BeforeEach
  void setUp() {
    filesConfig = mock(FilesConfig.class);
    notificationRepository = mock(NotificationRepository.class);

    UserMyself requester = mock(UserMyself.class);
    when(requester.getId()).thenReturn(new UserId(REQUESTER_ID));

    notificationApi = new NotificationApi();
    notificationApi.filesConfig = filesConfig;
    notificationApi.notificationRepository = notificationRepository;
    notificationApi.requester = requester;
  }

  private SnapshotNode makeSnapshotNode(String snapshotId, String nodeId) {
    return new SnapshotNode(snapshotId, 1000L, nodeId, null, 500L, NodeType.FOLDER, "node-name");
  }

  private SnapshotUser makeSnapshotUser(String snapshotId, String userId) {
    return new SnapshotUser(snapshotId, 1000L, userId, "Full Name", "user@example.com");
  }

  // ─── notifications disabled ───────────────────────────────────────────────

  @Test
  void getNotifications_disabled_returnsEmptyPage() {
    when(filesConfig.areNotificationsEnabled()).thenReturn(false);

    NotificationPageModel result = notificationApi.getNotifications(false, null, null);

    assertThat(result.getNotifications()).isEmpty();
    assertThat(result.getUnread()).isZero();
    assertThat(result.getPageToken()).isNull();
  }

  @Test
  void getNotifications_disabled_updateLastSeenTrue_doesNotUpsert() {
    when(filesConfig.areNotificationsEnabled()).thenReturn(false);

    notificationApi.getNotifications(true, null, null);

    verify(notificationRepository, never())
        .upsertUserNotificationsInfo(eq(REQUESTER_ID), anyLong(), anyInt());
  }

  // ─── NewShare mapping ─────────────────────────────────────────────────────

  @Test
  void getNotifications_enabled_mapsNewShareNotification() {
    when(filesConfig.areNotificationsEnabled()).thenReturn(true);

    NewShareNotification newShare =
        new NewShareNotification("notif-1", 1000L, "snap-node-1", "snap-user-1");
    newShare.setSnapshotNode(makeSnapshotNode("snap-node-1", "node-id-1"));
    newShare.setSnapshotUser(makeSnapshotUser("snap-user-1", "user-id-1"));

    when(notificationRepository.getNotifications(
            eq(REQUESTER_ID), eq(Optional.empty()), eq(Optional.empty())))
        .thenReturn(new ImmutablePair<>(List.of(newShare), null));
    when(notificationRepository.getUserNotificationsInfo(REQUESTER_ID))
        .thenReturn(Optional.of(new UserNotificationsInfo(REQUESTER_ID, 100L, 1)));

    NotificationPageModel result = notificationApi.getNotifications(false, null, null);

    assertThat(result.getNotifications()).hasSize(1);
    assertThat(result.getNotifications().get(0)).isInstanceOf(NewShareModel.class);
    NewShareModel model = (NewShareModel) result.getNotifications().get(0);
    assertThat(model.getId()).isEqualTo("notif-1");
    assertThat(model.getNode()).isNotNull();
    assertThat(model.getTriggeringUser()).isNotNull();
    assertThat(result.getUnread()).isEqualTo(1);
  }

  // ─── AddedNode mapping ────────────────────────────────────────────────────

  @Test
  void getNotifications_enabled_mapsAddedNodeNotification() {
    when(filesConfig.areNotificationsEnabled()).thenReturn(true);

    AddedNodeNotification addedNode =
        new AddedNodeNotification(
            "notif-2", 2000L, "snap-added-1", "snap-dest-1", "snap-user-1", AddedNodeType.UPLOAD);
    addedNode.setAddedNodeSnapshot(makeSnapshotNode("snap-added-1", "node-id-2"));
    addedNode.setDestinationFolderSnapshot(makeSnapshotNode("snap-dest-1", "folder-id-1"));
    addedNode.setTriggeringUserSnapshot(makeSnapshotUser("snap-user-1", "user-id-1"));

    when(notificationRepository.getNotifications(
            eq(REQUESTER_ID), eq(Optional.empty()), eq(Optional.empty())))
        .thenReturn(new ImmutablePair<>(List.of(addedNode), "next-token"));
    when(notificationRepository.getUserNotificationsInfo(REQUESTER_ID))
        .thenReturn(Optional.empty());

    NotificationPageModel result = notificationApi.getNotifications(false, null, null);

    assertThat(result.getNotifications()).hasSize(1);
    assertThat(result.getNotifications().get(0)).isInstanceOf(AddedNodeModel.class);
    AddedNodeModel model = (AddedNodeModel) result.getNotifications().get(0);
    assertThat(model.getId()).isEqualTo("notif-2");
    assertThat(model.getAddedNodeType().name()).isEqualTo("UPLOAD");
    assertThat(result.getPageToken()).isEqualTo("next-token");
    assertThat(result.getUnread()).isZero();
  }

  // ─── RemovedNode mapping ──────────────────────────────────────────────────

  @Test
  void getNotifications_enabled_mapsRemovedNodeNotification() {
    when(filesConfig.areNotificationsEnabled()).thenReturn(true);

    RemovedNodeNotification removedNode =
        new RemovedNodeNotification(
            "notif-3",
            3000L,
            "snap-removed-1",
            "snap-origin-1",
            "snap-user-1",
            RemovedNodeType.DELETE);
    removedNode.setRemovedNodeSnapshot(makeSnapshotNode("snap-removed-1", "node-id-3"));
    removedNode.setOriginFolderSnapshot(makeSnapshotNode("snap-origin-1", "folder-id-2"));
    removedNode.setTriggeringUserSnapshot(makeSnapshotUser("snap-user-1", "user-id-1"));

    when(notificationRepository.getNotifications(
            eq(REQUESTER_ID), eq(Optional.empty()), eq(Optional.empty())))
        .thenReturn(new ImmutablePair<>(List.of(removedNode), null));
    when(notificationRepository.getUserNotificationsInfo(REQUESTER_ID))
        .thenReturn(Optional.empty());

    NotificationPageModel result = notificationApi.getNotifications(false, null, null);

    assertThat(result.getNotifications()).hasSize(1);
    assertThat(result.getNotifications().get(0)).isInstanceOf(RemovedNodeModel.class);
    RemovedNodeModel model = (RemovedNodeModel) result.getNotifications().get(0);
    assertThat(model.getId()).isEqualTo("notif-3");
    assertThat(model.getRemovedNodeType().name()).isEqualTo("DELETE");
  }

  // ─── update_last_seen ─────────────────────────────────────────────────────

  @Test
  void getNotifications_updateLastSeenTrue_callsUpsert() {
    when(filesConfig.areNotificationsEnabled()).thenReturn(true);
    when(notificationRepository.getNotifications(
            eq(REQUESTER_ID), eq(Optional.empty()), eq(Optional.empty())))
        .thenReturn(new ImmutablePair<>(Collections.emptyList(), null));
    when(notificationRepository.getUserNotificationsInfo(REQUESTER_ID))
        .thenReturn(Optional.empty());

    notificationApi.getNotifications(true, null, null);

    verify(notificationRepository).upsertUserNotificationsInfo(eq(REQUESTER_ID), anyLong(), eq(0));
  }

  @Test
  void getNotifications_updateLastSeenFalse_doesNotCallUpsert() {
    when(filesConfig.areNotificationsEnabled()).thenReturn(true);
    when(notificationRepository.getNotifications(
            eq(REQUESTER_ID), eq(Optional.empty()), eq(Optional.empty())))
        .thenReturn(new ImmutablePair<>(Collections.emptyList(), null));
    when(notificationRepository.getUserNotificationsInfo(REQUESTER_ID))
        .thenReturn(Optional.empty());

    notificationApi.getNotifications(false, null, null);

    verify(notificationRepository, never())
        .upsertUserNotificationsInfo(eq(REQUESTER_ID), anyLong(), anyInt());
  }
}
