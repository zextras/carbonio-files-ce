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
import com.zextras.carbonio.files.dal.repositories.interfaces.NotificationRepository;
import com.zextras.carbonio.files.graphql.model.NotificationPageModel;
import com.zextras.carbonio.files.graphql.validation.GraphQLInputValidator;
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
    notificationApi.validator = new GraphQLInputValidator();
    notificationApi.notificationRepository = notificationRepository;
    notificationApi.requester = requester;
  }

  // ─── notifications disabled ───────────────────────────────────────────────

  @Test
  void getNotifications_disabled_returnsEmptyPage() throws Exception {
    when(filesConfig.areNotificationsEnabled()).thenReturn(false);

    NotificationPageModel result = notificationApi.getNotifications(false, null, null);

    assertThat(result.getNotifications()).isEmpty();
    assertThat(result.getUnread()).isZero();
    assertThat(result.getPageToken()).isNull();
  }

  @Test
  void getNotifications_disabled_updateLastSeenTrue_doesNotUpsert() throws Exception {
    when(filesConfig.areNotificationsEnabled()).thenReturn(false);

    notificationApi.getNotifications(true, null, null);

    verify(notificationRepository, never())
        .upsertUserNotificationsInfo(eq(REQUESTER_ID), anyLong(), anyInt());
  }
}
