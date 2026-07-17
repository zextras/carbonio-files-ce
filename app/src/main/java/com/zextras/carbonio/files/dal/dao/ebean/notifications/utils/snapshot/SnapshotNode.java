// SPDX-FileCopyrightText: 2025 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.dao.ebean.notifications.utils.snapshot;

import com.zextras.carbonio.files.Constants;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;

import jakarta.persistence.*;
import java.util.Objects;

@Entity
@Table(name = Constants.Db.Tables.SNAPSHOT_NODE)
public class SnapshotNode {

  /** Protected no-arg constructor required by Hibernate/JPA. */
  protected SnapshotNode() {}

  @Id
  @Column(name = Constants.Db.SnapshotNode.SNAPSHOT_NODE_ID, length = 36, nullable = false)
  private String snapshotNodeId;

  @Column(name = Constants.Db.SnapshotNode.SNAPSHOT_TIMESTAMP, nullable = false)
  private Long snapshotTimestamp;

  @Column(name = Constants.Db.SnapshotNode.NODE_ID, length = 36, nullable = false)
  private String nodeId;

  @Column(name = Constants.Db.SnapshotNode.OWNER_ID, length = 36)
  private String ownerId;

  @Column(name = Constants.Db.SnapshotNode.CREATION_TIMESTAMP, nullable = false)
  private Long createdAt;

  @Column(name = Constants.Db.SnapshotNode.NODE_TYPE, length = 50, nullable = false)
  @Enumerated(EnumType.STRING)
  private NodeType nodeType;

  @Column(name = Constants.Db.SnapshotNode.NAME, length = 1024, nullable = false)
  private String name;

  public SnapshotNode(String snapshotNodeId, Long snapshotTimestamp, String nodeId, String ownerId, Long createdAt, NodeType nodeType, String name) {
    this.snapshotNodeId = snapshotNodeId;
    this.snapshotTimestamp = snapshotTimestamp;
    this.nodeId = nodeId;
    this.ownerId = ownerId;
    this.createdAt = createdAt;
    this.nodeType = nodeType;
    this.name = name;
  }

  public boolean representNode(Node node) {
    try {
      return Objects.equals(this.nodeId, node.getId())
          && Objects.equals(this.ownerId, node.getOwnerId())
          && Objects.equals(this.createdAt, node.getCreatedAt())
          && this.nodeType == node.getNodeType()
          && Objects.equals(this.name, node.getName());
    } catch (Exception e) {
      // Fallback, YNK
      return false;
    }
  }

  public String getSnapshotNodeId() {
    return snapshotNodeId;
  }

  public void setSnapshotNodeId(String snapshotNodeId) {
    this.snapshotNodeId = snapshotNodeId;
  }

  public String getNodeId() {
    return nodeId;
  }

  public void setNodeId(String nodeId) {
    this.nodeId = nodeId;
  }

  public String getOwnerId() {
    return ownerId;
  }

  public void setOwnerId(String ownerId) {
    this.ownerId = ownerId;
  }

  public Long getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Long createdAt) {
    this.createdAt = createdAt;
  }

  public NodeType getNodeType() {
    return nodeType;
  }

  public void setNodeType(NodeType nodeType) {
    this.nodeType = nodeType;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public Long getSnapshotTimestamp() {
    return snapshotTimestamp;
  }

  public void setSnapshotTimestamp(Long snapshotTimestamp) {
    this.snapshotTimestamp = snapshotTimestamp;
  }
}
