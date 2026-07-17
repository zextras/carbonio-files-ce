// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.dao.ebean.notifications.utils;

import com.zextras.carbonio.files.dal.dao.ebean.notifications.BaseNotification;

/**
 * CE extension seam replacing the former closed {@code NotificationType} enum. Each descriptor pairs
 * a persisted string code with the concrete {@link BaseNotification} entity subtype it maps to. CE
 * ships one {@code @ApplicationScoped} descriptor per built-in type (NEW_SHARE / ADDED_NODE /
 * REMOVED_NODE); the Advanced edition adds its own descriptors as further beans, which the {@link
 * NotificationTypeRegistry} fans in.
 */
public interface NotificationTypeDescriptor {

  /**
   * @return the persisted {@code notification.notification_type} code (e.g. {@code "NEW_SHARE"}).
   */
  String code();

  /**
   * @return the concrete notification entity subtype this code maps to.
   */
  Class<? extends BaseNotification> notificationClass();
}
