// SPDX-FileCopyrightText: 2025 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.dao.ebean.notifications;

import com.zextras.carbonio.files.Constants;

import jakarta.persistence.*;

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
@Entity
@Table(name = Constants.Db.Tables.NOTIFICATION)
public class Notification {

  @Id
  @Column(name = Constants.Db.Notification.NOTIFICATION_ID, length = 36, nullable = false)
  private String notificationId;

  @Column(name = Constants.Db.Notification.CREATED_AT, nullable = false)
  private Long createdAt;

  // Plain String code (former NotificationType enum name) so the set of notification types is open
  // for the Advanced edition. On-disk value is unchanged (enum name == descriptor code).
  @Column(name = Constants.Db.Notification.NOTIFICATION_TYPE, length = 36, nullable = false)
  private String notificationType;

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

  public String getNotificationType() {
    return notificationType;
  }

  public void setNotificationType(String notificationType) {
    this.notificationType = notificationType;
  }
}