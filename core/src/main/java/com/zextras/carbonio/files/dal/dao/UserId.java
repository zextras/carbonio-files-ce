// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.dao;

/**
 * Represents a user identifier. This is a local domain type that replaces the old
 * {@code com.zextras.carbonio.usermanagement.entities.UserId} from the HTTP SDK.
 */
public class UserId {

  private String userId;

  public UserId() {}

  public UserId(String userId) {
    this.userId = userId;
  }

  public String getUserId() {
    return userId;
  }

  public void setUserId(String userId) {
    this.userId = userId;
  }

  @Override
  public String toString() {
    return userId;
  }
}
