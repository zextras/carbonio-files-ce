// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.model;

import org.eclipse.microprofile.graphql.Enum;

@Enum("NotificationType")
public enum NotificationType {
  NEW_SHARE,
  ADDED_NODE,
  REMOVED_NODE,
  // Inert in CE: carried here so Advanced's union types can share this enum across the jar boundary
  // (a Java enum cannot be extended by another module); produced only by carbonio-files (Advanced).
  TRANSFERRED_OWNERSHIP,
  SUCCEEDED_RECORDING
}
