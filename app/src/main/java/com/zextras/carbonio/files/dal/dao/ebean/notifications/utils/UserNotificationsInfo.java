// SPDX-FileCopyrightText: 2026 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.dao.ebean.notifications.utils;

import com.zextras.carbonio.files.Constants;
import jakarta.persistence.*;

/*
 * This is a useful class mapping a table that contains information about the notifications of a user.
 * Since we don't have a user table in File's database, we created one relative to the notifications.
 * Since the users will be inserted on-demand, one assumption is that users with notifications will always be present
 * in this table, so we can assume if a user is not present here there is no notification for him.
 * */
@Entity
@Table(name = Constants.Db.Tables.USER_NOTIFICATIONS_INFO)
public class UserNotificationsInfo {

  /** Protected no-arg constructor required by Hibernate/JPA. */
  protected UserNotificationsInfo() {}

  @Id
  @Column(name = Constants.Db.UserNotificationsInfo.USER_ID, length = 36, nullable = false)
  private String userId;

  @Column(name = Constants.Db.UserNotificationsInfo.LAST_SEEN, nullable = false)
  private Long lastSeen;

  @Column(name = Constants.Db.UserNotificationsInfo.UNREAD, nullable = false)
  private Integer unread;

  public UserNotificationsInfo(String userId, Long lastSeen, Integer unread) {
    this.userId = userId;
    this.lastSeen = lastSeen;
    this.unread = unread;
  }

  public String getUserId() {
    return userId;
  }

  public void setUserId(String userId) {
    this.userId = userId;
  }

  public Long getLastSeen() {
    return lastSeen;
  }

  public void setLastSeen(Long lastSeen) {
    this.lastSeen = lastSeen;
  }

  public Integer getUnread() {
    return unread;
  }

  public void setUnread(Integer unread) {
    this.unread = unread;
  }
}
