// SPDX-FileCopyrightText: 2025 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.dao.ebean.notifications;

import com.zextras.carbonio.files.Constants;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.utils.NotificationTypeCodes;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.utils.snapshot.SnapshotNode;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.utils.snapshot.SnapshotUser;
import jakarta.persistence.*;

@Entity
@Table(name = Constants.Db.Tables.NEW_SHARE_NOTIFICATION)
public class NewShareNotification extends BaseNotification {

  /** Protected no-arg constructor required by Hibernate/JPA. */
  protected NewShareNotification() {}

  @Column(name = Constants.Db.NewShareNotification.NODE_SNAPSHOT_ID, length = 36, nullable = false)
  private String nodeSnapshotId;

  @ManyToOne(fetch = FetchType.EAGER)
  @JoinColumn(
      name = Constants.Db.NewShareNotification.NODE_SNAPSHOT_ID,
      insertable = false,
      updatable = false)
  private SnapshotNode snapshotNode;

  @Column(
      name = Constants.Db.NewShareNotification.TRIGGERING_USER_SNAPSHOT_ID,
      length = 36,
      nullable = false)
  private String triggeringUserSnapshotId;

  @ManyToOne(fetch = FetchType.EAGER)
  @JoinColumn(
      name = Constants.Db.NewShareNotification.TRIGGERING_USER_SNAPSHOT_ID,
      insertable = false,
      updatable = false)
  private SnapshotUser snapshotUser;

  public NewShareNotification(
      String notificationId,
      Long createdAt,
      String nodeSnapshotId,
      String triggeringUserSnapshotId) {
    super(notificationId, createdAt, NotificationTypeCodes.NEW_SHARE);
    this.nodeSnapshotId = nodeSnapshotId;
    this.triggeringUserSnapshotId = triggeringUserSnapshotId;
  }

  public String getNodeSnapshotId() {
    return nodeSnapshotId;
  }

  public void setNodeSnapshotId(String nodeSnapshotId) {
    this.nodeSnapshotId = nodeSnapshotId;
  }

  public String getTriggeringUserSnapshotId() {
    return triggeringUserSnapshotId;
  }

  public void setTriggeringUserSnapshotId(String triggeringUserSnapshotId) {
    this.triggeringUserSnapshotId = triggeringUserSnapshotId;
  }

  public SnapshotNode getSnapshotNode() {
    return snapshotNode;
  }

  public void setSnapshotNode(SnapshotNode snapshotNode) {
    this.snapshotNode = snapshotNode;
  }

  public SnapshotUser getSnapshotUser() {
    return snapshotUser;
  }

  public void setSnapshotUser(SnapshotUser snapshotUser) {
    this.snapshotUser = snapshotUser;
  }
}
