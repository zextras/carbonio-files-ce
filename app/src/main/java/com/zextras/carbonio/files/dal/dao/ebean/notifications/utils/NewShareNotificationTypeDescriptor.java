// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.dao.ebean.notifications.utils;

import com.zextras.carbonio.files.dal.dao.ebean.notifications.BaseNotification;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.NewShareNotification;
import jakarta.enterprise.context.ApplicationScoped;

/** CE built-in descriptor for the {@code NEW_SHARE} notification type. */
@ApplicationScoped
public class NewShareNotificationTypeDescriptor implements NotificationTypeDescriptor {

  @Override
  public String code() {
    return NotificationTypeCodes.NEW_SHARE;
  }

  @Override
  public Class<? extends BaseNotification> notificationClass() {
    return NewShareNotification.class;
  }
}
