// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.model;

import org.eclipse.microprofile.graphql.Enum;

@Enum("NotificationType")
public enum NotificationType {
  NEW_SHARE,
  ADDED_NODE,
  REMOVED_NODE
}
