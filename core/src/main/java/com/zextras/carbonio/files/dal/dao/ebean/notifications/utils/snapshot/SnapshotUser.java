// SPDX-FileCopyrightText: 2025 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.dao.ebean.notifications.utils.snapshot;

import com.zextras.carbonio.files.Constants;
import com.zextras.carbonio.files.dal.dao.UserMyself;
import io.ebean.annotation.Cache;
import java.util.Objects;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

@Cache
@Entity
@Table(name = Constants.Db.Tables.SNAPSHOT_USER)
public class SnapshotUser {

  @Id
  @Column(name = Constants.Db.SnapshotUser.SNAPSHOT_USER_ID, length = 256, nullable = false)
  private String snapshotUserId;

  @Column(name = Constants.Db.SnapshotUser.SNAPSHOT_TIMESTAMP, nullable = false)
  private Long snapshotTimestamp;

  @Column(name = Constants.Db.SnapshotUser.USER_ID, length = 256, nullable = false)
  private String userId;

  @Column(name = Constants.Db.SnapshotUser.FULL_NAME, length = 1024, nullable = false)
  private String fullName;

  @Column(name = Constants.Db.SnapshotUser.EMAIL, length = 1024, nullable = false)
  private String email;

  public SnapshotUser(
      String snapshotUserId, Long snapshotTimestamp, String userId, String fullName, String email) {
    this.snapshotUserId = snapshotUserId;
    this.snapshotTimestamp = snapshotTimestamp;
    this.userId = userId;
    this.fullName = fullName;
    this.email = email;
  }

  public boolean representUser(UserMyself user) {
    try {
      return Objects.equals(this.userId, user.getId().getUserId())
          && Objects.equals(this.fullName, user.getFullName())
          && Objects.equals(this.email, user.getEmail());
    } catch (Exception e) {
      // Fallback, YNK
      return false;
    }
  }

  public String getSnapshotUserId() {
    return snapshotUserId;
  }

  public void setSnapshotUserId(String snapshotUserId) {
    this.snapshotUserId = snapshotUserId;
  }

  public String getUserId() {
    return userId;
  }

  public void setUserId(String userId) {
    this.userId = userId;
  }

  public String getFullName() {
    return fullName;
  }

  public void setFullName(String fullName) {
    this.fullName = fullName;
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    this.email = email;
  }

  public Long getSnapshotTimestamp() {
    return snapshotTimestamp;
  }

  public void setSnapshotTimestamp(Long snapshotTimestamp) {
    this.snapshotTimestamp = snapshotTimestamp;
  }
}
