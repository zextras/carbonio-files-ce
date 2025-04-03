package com.zextras.carbonio.files.dal.dao.ebean.notifications.utils;

import com.zextras.carbonio.files.Files;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.Notification;
import io.ebean.annotation.Cache;

import javax.persistence.*;

/*
* This essentially maps users with notifications they should see, it's an N:N relationship.
* */
@Cache
@Entity
@Table(name = Files.Db.Tables.USER_NOTIFICATION_INTEREST)
public class UserNotificationInterest {

  @Id
  @Column(name = Files.Db.UserNotificationInterest.INTEREST_ID, length = 255, nullable = false)
  private String interestId;

  @Column(name = Files.Db.UserNotificationInterest.USER_ID, length = 36, nullable = false)
  private String userId;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = Files.Db.UserNotificationInterest.USER_ID, insertable = false, updatable = false)
  private UserNotificationsInfo userInfo;

  @Column(name = Files.Db.UserNotificationInterest.NOTIFICATION_ID, length = 36, nullable = false)
  private String notificationId;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = Files.Db.UserNotificationInterest.NOTIFICATION_ID, insertable = false, updatable = false)
  private Notification notification;

  @Column(name = Files.Db.UserNotificationInterest.CREATED_AT, nullable = false)
  private Long createdAt;

  public UserNotificationInterest(String interestId, String userId, String notificationId, Long createdAt) {
    this.interestId = interestId;
    this.userId = userId;
    this.notificationId = notificationId;
    this.createdAt = createdAt;
  }

  public String getUserId() {
    return userId;
  }

  public void setUserId(String userId) {
    this.userId = userId;
  }

  public String getNotificationId() {
    return notificationId;
  }

  public Long getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Long createdAt) {
    this.createdAt = createdAt;
  }

  public void setNotificationId(String notificationId) {
    this.notificationId = notificationId;
  }

  public UserNotificationsInfo getUserInfo() {
    return userInfo;
  }

  public void setUserInfo(UserNotificationsInfo userInfo) {
    this.userInfo = userInfo;
  }

  public Notification getNotification() {
    return notification;
  }

  public void setNotification(Notification notification) {
    this.notification = notification;
  }
}