// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.dao;

import com.zextras.carbonio.usermanagement.enumerations.UserStatus;
import com.zextras.carbonio.usermanagement.enumerations.UserType;

/**
 * Represents a {@link User} entity that matches a Files user stored in Carbonio LDAP.
 *
 * <p>The implementation of constructors and setters should not care to check if the values in input
 * are valid or not because, when these methods are called, these controls <strong>must</strong> be
 * already done.
 */
public class User {

  private final String id;
  private final String fullName;
  private final String email;
  private final String domain;
  private final UserStatus status;
  private final UserType type;

  public User(String id, String fullName, String email, String domain, UserStatus status, UserType type) {
    this.id = id;
    this.fullName = fullName;
    this.email = email;
    this.domain = domain;
    this.status = status;
    this.type = type;
  }

  public String getId() {
    return id;
  }

  public String getFullName() {
    return fullName;
  }

  public String getEmail() {
    return email;
  }

  public String getDomain() {
    return domain;
  }

  public UserStatus getStatus() {
    return status;
  }

  public UserType getType() { return type; }
}
