// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.model;

import org.eclipse.microprofile.graphql.Id;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Type;

@Type("RemovedNode")
public class RemovedNodeModel implements Notification {

  private final String id;
  private final long createdAt;
  private final NotificationType notificationType;
  private final SnapshotNodeModel removedNode;
  private final SnapshotNodeModel originFolder;
  private final SnapshotUserModel triggeringUser;
  private final RemovedNodeType removedNodeType;

  public RemovedNodeModel(
      String id,
      long createdAt,
      NotificationType notificationType,
      SnapshotNodeModel removedNode,
      SnapshotNodeModel originFolder,
      SnapshotUserModel triggeringUser,
      RemovedNodeType removedNodeType) {
    this.id = id;
    this.createdAt = createdAt;
    this.notificationType = notificationType;
    this.removedNode = removedNode;
    this.originFolder = originFolder;
    this.triggeringUser = triggeringUser;
    this.removedNodeType = removedNodeType;
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
  @Name("removed_node")
  public SnapshotNodeModel getRemovedNode() {
    return removedNode;
  }

  @NonNull
  @Name("origin_folder")
  public SnapshotNodeModel getOriginFolder() {
    return originFolder;
  }

  @NonNull
  @Name("triggering_user")
  public SnapshotUserModel getTriggeringUser() {
    return triggeringUser;
  }

  @NonNull
  @Name("removed_node_type")
  public RemovedNodeType getRemovedNodeType() {
    return removedNodeType;
  }
}
