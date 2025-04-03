// SPDX-FileCopyrightText: 2025 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.dao.ebean.notifications;

import com.zextras.carbonio.files.Files;
import com.zextras.carbonio.files.dal.dao.ebean.notifications.utils.NotificationType;
import io.ebean.annotation.Cache;

import javax.persistence.*;

/*
* Why didn't I use inheritance? It wouldn't work with Ebean, trying to insert everything in base table.
* No idea why. Wrapped my way around it.
* We have a "notification" table with IDs and shared information (createdAt, type).
* The entity for this table though is not extended by the other entities, even if it logically
* should be. Instead, since Ebean complains, I created a BaseNotification abstract class
* that contains a Notification object and getters/setters for it.
* This does not represent a table, but is used to extend the other entities so the
* "feeling" of using inheritance is there, instead of manipulating objects and making manual joins.
* */
@Cache
@Entity
@Table(name = Files.Db.Tables.NOTIFICATION)
public class Notification {

  @Id
  @Column(name = Files.Db.Notification.NOTIFICATION_ID, length = 36, nullable = false)
  private String notificationId;

  @Column(name = Files.Db.Notification.CREATED_AT, nullable = false)
  private Long createdAt;

  @Column(name = Files.Db.Notification.NOTIFICATION_TYPE, length = 36, nullable = false)
  @Enumerated(EnumType.STRING)
  private NotificationType notificationType;

  protected Notification() {
    // Default constructor for Ebean
  }

  public String getNotificationId() {
    return notificationId;
  }

  public void setNotificationId(String notificationId) {
    this.notificationId = notificationId;
  }

  public Long getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Long createdAt) {
    this.createdAt = createdAt;
  }

  public NotificationType getNotificationType() {
    return notificationType;
  }

  public void setNotificationType(NotificationType notificationType) {
    this.notificationType = notificationType;
  }
}