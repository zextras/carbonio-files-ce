// SPDX-FileCopyrightText: 2026 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.dao.ebean.notifications.utils;

import com.zextras.carbonio.files.dal.dao.ebean.notifications.AddedNodeNotification;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.BaseNotification;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.NewShareNotification;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.RemovedNodeNotification;

/*
 * Useful to avoid reflection, so we can just map the type to the class representing it
 */
public enum NotificationType {
  NEW_SHARE(NewShareNotification.class),
  ADDED_NODE(AddedNodeNotification.class),
  REMOVED_NODE(RemovedNodeNotification.class);

  private final Class<? extends BaseNotification> notificationClass;

  NotificationType(Class<? extends BaseNotification> notificationClass) {
    this.notificationClass = notificationClass;
  }

  public Class<? extends BaseNotification> getNotificationClass() {
    return notificationClass;
  }
}
