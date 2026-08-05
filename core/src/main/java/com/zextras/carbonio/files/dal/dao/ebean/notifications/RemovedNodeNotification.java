// SPDX-FileCopyrightText: 2025 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.dao.ebean.notifications;

import com.zextras.carbonio.files.Constants;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.utils.NotificationType;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.utils.snapshot.SnapshotNode;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.utils.snapshot.SnapshotUser;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.RemovedNodeType;

import javax.persistence.*;

@Entity
@Table(name = Constants.Db.Tables.REMOVED_NODE_NOTIFICATION)
public class RemovedNodeNotification extends BaseNotification {

  @Column(name = Constants.Db.RemovedNodeNotification.REMOVED_NODE_SNAPSHOT_ID, length = 36, nullable = false)
  private String removedNodeSnapshotId;

  @OneToOne(fetch = FetchType.EAGER)
  @JoinColumn(name = Constants.Db.RemovedNodeNotification.REMOVED_NODE_SNAPSHOT_ID, insertable = false, updatable = false)
  private SnapshotNode removedNodeSnapshot;

  @Column(name = Constants.Db.RemovedNodeNotification.ORIGIN_FOLDER_SNAPSHOT_ID, length = 36, nullable = false)
  private String originFolderSnapshotId;

  @OneToOne(fetch = FetchType.EAGER)
  @JoinColumn(name = Constants.Db.RemovedNodeNotification.ORIGIN_FOLDER_SNAPSHOT_ID, insertable = false, updatable = false)
  private SnapshotNode originFolderSnapshot;

  @Column(name = Constants.Db.RemovedNodeNotification.TRIGGERING_USER_SNAPSHOT_ID, length = 36, nullable = false)
  private String triggeringUserSnapshotId;

  @ManyToOne(fetch = FetchType.EAGER)
  @JoinColumn(name = Constants.Db.AddedNodeNotification.TRIGGERING_USER_SNAPSHOT_ID, insertable = false, updatable = false)
  private SnapshotUser triggeringUserSnapshot;

  @Column(name = Constants.Db.RemovedNodeNotification.REMOVED_NODE_TYPE, length = 50, nullable = false)
  @Enumerated(EnumType.STRING)
  private RemovedNodeType removedNodeType;

  public RemovedNodeNotification(String notificationId, Long createdAt, String removedNodeSnapshotId, String originFolderSnapshotId, String triggeringUserSnapshotId, RemovedNodeType removedNodeType) {
    super(notificationId, createdAt, NotificationType.REMOVED_NODE);
    this.removedNodeSnapshotId = removedNodeSnapshotId;
    this.originFolderSnapshotId = originFolderSnapshotId;
    this.triggeringUserSnapshotId = triggeringUserSnapshotId;
    this.removedNodeType = removedNodeType;
  }

  public String getRemovedNodeSnapshotId() {
    return removedNodeSnapshotId;
  }

  public void setRemovedNodeSnapshotId(String removedNodeSnapshotId) {
    this.removedNodeSnapshotId = removedNodeSnapshotId;
  }

  public String getOriginFolderSnapshotId() {
    return originFolderSnapshotId;
  }

  public void setOriginFolderSnapshotId(String originFolderSnapshotId) {
    this.originFolderSnapshotId = originFolderSnapshotId;
  }

  public String getTriggeringUserSnapshotId() {
    return triggeringUserSnapshotId;
  }

  public void setTriggeringUserSnapshotId(String triggeringUserSnapshotId) {
    this.triggeringUserSnapshotId = triggeringUserSnapshotId;
  }

  public RemovedNodeType getRemovedNodeType() {
    return removedNodeType;
  }

  public void setRemovedNodeType(RemovedNodeType removedNodeType) {
    this.removedNodeType = removedNodeType;
  }

  public SnapshotNode getRemovedNodeSnapshot() {
    return removedNodeSnapshot;
  }

  public void setRemovedNodeSnapshot(SnapshotNode removedNodeSnapshot) {
    this.removedNodeSnapshot = removedNodeSnapshot;
  }

  public SnapshotNode getOriginFolderSnapshot() {
    return originFolderSnapshot;
  }

  public void setOriginFolderSnapshot(SnapshotNode originFolderSnapshot) {
    this.originFolderSnapshot = originFolderSnapshot;
  }

  public SnapshotUser getTriggeringUserSnapshot() {
    return triggeringUserSnapshot;
  }

  public void setTriggeringUserSnapshot(SnapshotUser triggeringUserSnapshot) {
    this.triggeringUserSnapshot = triggeringUserSnapshot;
  }
}