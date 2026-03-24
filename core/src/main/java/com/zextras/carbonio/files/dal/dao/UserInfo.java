// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.dao;

/**
 * Represents basic user information returned by user lookup operations. This is a local domain type
 * that replaces the old {@code com.zextras.carbonio.usermanagement.entities.UserInfo} from the HTTP
 * SDK.
 */
public class UserInfo {

  private UserId id;
  private String email;
  private String fullName;
  private String domain;
  private UserStatus status;
  private UserType type;

  public UserInfo() {}

  public UserInfo(
      UserId id,
      String email,
      String fullName,
      String domain,
      UserStatus status,
      UserType type) {
    this.id = id;
    this.email = email;
    this.fullName = fullName;
    this.domain = domain;
    this.status = status;
    this.type = type;
  }

  public UserId getId() {
    return id;
  }

  public void setId(UserId id) {
    this.id = id;
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    this.email = email;
  }

  public String getFullName() {
    return fullName;
  }

  public void setFullName(String fullName) {
    this.fullName = fullName;
  }

  public String getDomain() {
    return domain;
  }

  public void setDomain(String domain) {
    this.domain = domain;
  }

  public UserStatus getStatus() {
    return status;
  }

  public void setStatus(UserStatus status) {
    this.status = status;
  }

  public UserType getType() {
    return type;
  }

  public void setType(UserType type) {
    this.type = type;
  }
}
