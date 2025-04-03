package com.zextras.carbonio.files.dal.dao.ebean.notifications.utils;

import com.zextras.carbonio.files.Files;
import io.ebean.annotation.Cache;

import javax.persistence.*;

/*
* This is a useful class mapping a table that contains information about the notifications of a user.
* Since we don't have a user table in File's database, we created one relative to the notifications.
* Since the users will be inserted on-demand, one assumption is that users with notifications will always be present
* in this table, so we can assume if a user is not present here there is no notification for him.
* */
@Cache
@Entity
@Table(name = Files.Db.Tables.USER_NOTIFICATIONS_INFO)
public class UserNotificationsInfo {

  @Id
  @Column(name = Files.Db.UserNotificationsInfo.USER_ID, length = 36, nullable = false)
  private String userId;

  @Column(name = Files.Db.UserNotificationsInfo.LAST_SEEN, nullable = false)
  private Long lastSeen;

  @Column(name = Files.Db.UserNotificationsInfo.UNREAD, nullable = false)
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