// SPDX-FileCopyrightText: 2025 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.dao.ebean.notifications;

import com.zextras.carbonio.files.dal.dao.ebean.notifications.utils.NotificationType;

import javax.persistence.*;

@MappedSuperclass
public abstract class BaseNotification {

  @Id
  @Column(name = "notification_id")
  private String notificationId;

  @OneToOne(cascade = CascadeType.ALL)
  @MapsId
  @JoinColumn(name = "notification_id")
  private Notification notification;

  protected BaseNotification(String notificationId, Long createdAt, NotificationType type) {
    this.notification = new Notification();
    this.notification.setNotificationId(notificationId);
    this.notification.setCreatedAt(createdAt);
    this.notification.setNotificationType(type);
  }

  public String getNotificationId() {
    return notification.getNotificationId();
  }

  public void setNotificationId(String notificationId) {
    this.notification.setNotificationId(notificationId);
  }

  public Long getCreatedAt() {
    return this.notification.getCreatedAt();
  }

  public void setCreatedAt(Long createdAt) {
    this.notification.setCreatedAt(createdAt);
  }

  public NotificationType getNotificationType() {
    return this.notification.getNotificationType();
  }

  public void setNotificationType(NotificationType notificationType) {
    this.notification.setNotificationType(notificationType);
  }
}