// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.dao.ebean.notifications.utils;

/**
 * The on-disk {@code notification.notification_type} string codes. These are the exact former {@code
 * NotificationType} enum names, so replacing the closed enum with an open {@link
 * NotificationTypeDescriptor} registry leaves the persisted column value unchanged (no data
 * migration). The values are compile-time constants so they can be used as {@code switch} case
 * labels.
 */
public final class NotificationTypeCodes {

  public static final String NEW_SHARE = "NEW_SHARE";
  public static final String ADDED_NODE = "ADDED_NODE";
  public static final String REMOVED_NODE = "REMOVED_NODE";

  private NotificationTypeCodes() {}
}
