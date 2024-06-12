// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.messageBroker.events;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.zextras.carbonio.usermanagement.enumerations.UserStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UserStatusChangedEvent {

  private static final Logger logger = LoggerFactory.getLogger(UserStatusChangedEvent.class);

  private String userId;
  private UserStatus userStatus;

  public UserStatusChangedEvent(@JsonProperty("userId") String userId, @JsonProperty("userStatus") String userStatus) {
    this.userId = userId;
    this.userStatus = UserStatus.valueOf(userStatus);
  }

  public UserStatusChangedEvent(String userId, UserStatus userStatus) {
    this.userId = userId;
    this.userStatus = userStatus;
  }

  public String getUserId() {
    return userId;
  }

  public void setUserId(String userId) {
    this.userId = userId;
  }

  public UserStatus getUserStatus() {
    return userStatus;
  }

  public void setUserStatus(UserStatus userStatus) {
    this.userStatus = userStatus;
  }
}
