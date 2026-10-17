// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.model;

import java.util.List;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Type;

@Type("NotificationPage")
public class NotificationPageModel {

  private final List<Notification> notifications;
  private final int unread;
  private final long lastSeen;
  private final String pageToken;

  public NotificationPageModel(
      List<Notification> notifications, int unread, long lastSeen, String pageToken) {
    this.notifications = notifications;
    this.unread = unread;
    this.lastSeen = lastSeen;
    this.pageToken = pageToken;
  }

  @NonNull
  public List<Notification> getNotifications() {
    return notifications;
  }

  public int getUnread() {
    return unread;
  }

  @Name("last_seen")
  public long getLastSeen() {
    return lastSeen;
  }

  @Name("page_token")
  public String getPageToken() {
    return pageToken;
  }
}
