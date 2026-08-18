// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.dao.ebean.notifications.utils;

import com.zextras.carbonio.files.dal.dao.ebean.notifications.AddedNodeNotification;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.BaseNotification;
import jakarta.enterprise.context.ApplicationScoped;

/** CE built-in descriptor for the {@code ADDED_NODE} notification type. */
@ApplicationScoped
public class AddedNodeNotificationTypeDescriptor implements NotificationTypeDescriptor {

  @Override
  public String code() {
    return NotificationTypeCodes.ADDED_NODE;
  }

  @Override
  public Class<? extends BaseNotification> notificationClass() {
    return AddedNodeNotification.class;
  }
}
