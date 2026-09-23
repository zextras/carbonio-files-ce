// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.model;

import org.eclipse.microprofile.graphql.Id;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Type;

@Type("SnapshotNode")
public class SnapshotNodeModel {

  private final String snapshotNodeId;
  private final String nodeId;
  private final String ownerId;
  private final String name;
  private final NodeType type;
  private final long createdAt;

  public SnapshotNodeModel(
      String snapshotNodeId,
      String nodeId,
      String ownerId,
      String name,
      NodeType type,
      long createdAt) {
    this.snapshotNodeId = snapshotNodeId;
    this.nodeId = nodeId;
    this.ownerId = ownerId;
    this.name = name;
    this.type = type;
    this.createdAt = createdAt;
  }

  @Id
  @NonNull
  @Name("snapshot_node_id")
  public String getSnapshotNodeId() {
    return snapshotNodeId;
  }

  @Id
  @NonNull
  @Name("node_id")
  public String getNodeId() {
    return nodeId;
  }

  @Id
  @Name("owner_id")
  public String getOwnerId() {
    return ownerId;
  }

  @NonNull
  public String getName() {
    return name;
  }

  @NonNull
  public NodeType getType() {
    return type;
  }

  @Name("created_at")
  public long getCreatedAt() {
    return createdAt;
  }
}
