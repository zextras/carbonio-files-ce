// SPDX-FileCopyrightText: 2025 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.dao.ebean.notifications;

import com.zextras.carbonio.files.Constants;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.utils.NotificationType;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.utils.snapshot.SnapshotNode;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.utils.snapshot.SnapshotUser;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.AddedNodeType;

import jakarta.persistence.*;

@Entity
@Table(name = Constants.Db.Tables.ADDED_NODE_NOTIFICATION)
public class AddedNodeNotification extends BaseNotification{

  /** Protected no-arg constructor required by Hibernate/JPA. */
  protected AddedNodeNotification() {}

  @Column(name = Constants.Db.AddedNodeNotification.ADDED_NODE_SNAPSHOT_ID, length = 36, nullable = false)
  private String addedNodeSnapshotId;

  @OneToOne(fetch = FetchType.EAGER)
  @JoinColumn(name = Constants.Db.AddedNodeNotification.ADDED_NODE_SNAPSHOT_ID, insertable = false, updatable = false)
  private SnapshotNode addedNodeSnapshot;

  @Column(name = Constants.Db.AddedNodeNotification.DESTINATION_FOLDER_SNAPSHOT_ID, length = 36, nullable = false)
  private String destinationFolderSnapshotId;

  @OneToOne(fetch = FetchType.EAGER)
  @JoinColumn(name = Constants.Db.AddedNodeNotification.DESTINATION_FOLDER_SNAPSHOT_ID, insertable = false, updatable = false)
  private SnapshotNode destinationFolderSnapshot;

  @Column(name = Constants.Db.AddedNodeNotification.TRIGGERING_USER_SNAPSHOT_ID, length = 36, nullable = false)
  private String triggeringUserSnapshotId;

  @ManyToOne(fetch = FetchType.EAGER)
  @JoinColumn(name = Constants.Db.AddedNodeNotification.TRIGGERING_USER_SNAPSHOT_ID, insertable = false, updatable = false)
  private SnapshotUser triggeringUserSnapshot;

  @Column(name = Constants.Db.AddedNodeNotification.ADDED_NODE_TYPE, length = 50, nullable = false)
  @Enumerated(EnumType.STRING)
  private AddedNodeType addedNodeType;

  public AddedNodeNotification(String notificationId, Long createdAt, String addedNodeSnapshotId, String destinationFolderSnapshotId, String triggeringUserSnapshotId, AddedNodeType addedNodeType) {
    super(notificationId, createdAt, NotificationType.ADDED_NODE);
    this.addedNodeSnapshotId = addedNodeSnapshotId;
    this.destinationFolderSnapshotId = destinationFolderSnapshotId;
    this.triggeringUserSnapshotId = triggeringUserSnapshotId;
    this.addedNodeType = addedNodeType;
  }

  public String getAddedNodeSnapshotId() {
    return addedNodeSnapshotId;
  }

  public void setAddedNodeSnapshotId(String addedNodeSnapshotId) {
    this.addedNodeSnapshotId = addedNodeSnapshotId;
  }

  public String getDestinationFolderSnapshotId() {
    return destinationFolderSnapshotId;
  }

  public void setDestinationFolderSnapshotId(String destinationFolderSnapshotId) {
    this.destinationFolderSnapshotId = destinationFolderSnapshotId;
  }

  public String getTriggeringUserSnapshotId() {
    return triggeringUserSnapshotId;
  }

  public void setTriggeringUserSnapshotId(String triggeringUserSnapshotId) {
    this.triggeringUserSnapshotId = triggeringUserSnapshotId;
  }

  public AddedNodeType getAddedNodeType() {
    return addedNodeType;
  }

  public void setAddedNodeType(AddedNodeType addedNodeType) {
    this.addedNodeType = addedNodeType;
  }

  public SnapshotNode getAddedNodeSnapshot() {
    return addedNodeSnapshot;
  }

  public void setAddedNodeSnapshot(SnapshotNode addedNodeSnapshot) {
    this.addedNodeSnapshot = addedNodeSnapshot;
  }

  public SnapshotNode getDestinationFolderSnapshot() {
    return destinationFolderSnapshot;
  }

  public void setDestinationFolderSnapshot(SnapshotNode destinationFolderSnapshot) {
    this.destinationFolderSnapshot = destinationFolderSnapshot;
  }

  public SnapshotUser getTriggeringUserSnapshot() {
    return triggeringUserSnapshot;
  }

  public void setTriggeringUserSnapshot(SnapshotUser triggeringUserSnapshot) {
    this.triggeringUserSnapshot = triggeringUserSnapshot;
  }
}
