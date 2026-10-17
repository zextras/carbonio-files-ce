// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.model;

import org.eclipse.microprofile.graphql.Id;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Type;

@Type("NewShare")
public class NewShareModel implements Notification {

  private final String id;
  private final long createdAt;
  private final NotificationType notificationType;
  private final SnapshotNodeModel node;
  private final SnapshotUserModel triggeringUser;

  public NewShareModel(
      String id,
      long createdAt,
      NotificationType notificationType,
      SnapshotNodeModel node,
      SnapshotUserModel triggeringUser) {
    this.id = id;
    this.createdAt = createdAt;
    this.notificationType = notificationType;
    this.node = node;
    this.triggeringUser = triggeringUser;
  }

  @Id
  @NonNull
  public String getId() {
    return id;
  }

  @Name("created_at")
  public long getCreatedAt() {
    return createdAt;
  }

  @NonNull
  @Name("notification_type")
  public NotificationType getNotificationType() {
    return notificationType;
  }

  @NonNull
  public SnapshotNodeModel getNode() {
    return node;
  }

  @NonNull
  @Name("triggering_user")
  public SnapshotUserModel getTriggeringUser() {
    return triggeringUser;
  }
}
