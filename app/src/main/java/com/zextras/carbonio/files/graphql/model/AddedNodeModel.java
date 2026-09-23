// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.model;

import org.eclipse.microprofile.graphql.Id;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Type;

@Type("AddedNode")
public class AddedNodeModel implements Notification {

  private final String id;
  private final long createdAt;
  private final NotificationType notificationType;
  private final SnapshotNodeModel addedNode;
  private final SnapshotNodeModel destinationFolder;
  private final SnapshotUserModel triggeringUser;
  private final AddedNodeType addedNodeType;

  public AddedNodeModel(
      String id,
      long createdAt,
      NotificationType notificationType,
      SnapshotNodeModel addedNode,
      SnapshotNodeModel destinationFolder,
      SnapshotUserModel triggeringUser,
      AddedNodeType addedNodeType) {
    this.id = id;
    this.createdAt = createdAt;
    this.notificationType = notificationType;
    this.addedNode = addedNode;
    this.destinationFolder = destinationFolder;
    this.triggeringUser = triggeringUser;
    this.addedNodeType = addedNodeType;
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
  @Name("added_node")
  public SnapshotNodeModel getAddedNode() {
    return addedNode;
  }

  @NonNull
  @Name("destination_folder")
  public SnapshotNodeModel getDestinationFolder() {
    return destinationFolder;
  }

  @NonNull
  @Name("triggering_user")
  public SnapshotUserModel getTriggeringUser() {
    return triggeringUser;
  }

  @NonNull
  @Name("added_node_type")
  public AddedNodeType getAddedNodeType() {
    return addedNodeType;
  }
}
