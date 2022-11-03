// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.dao;

/**
 * <p>Represents a {@link User} entity that matches a Files user stored in Carbonio LDAP.</p>
 * <p>The implementation of constructors and setters should not care to check if the values in
 * input are valid or not because, when these methods are called, these controls
 * <strong>must</strong> be already done.</p>
 */
public class User {

  private final String uuid;
  private final String fullName;
  private final String email;
  private final String domain;

  public User(
    String uuid,
    String fullName,
    String email,
    String domain
  ) {
    this.uuid = uuid;
    this.fullName = fullName;
    this.email = email;
    this.domain = domain;
  }

  public String getUuid() {
    return uuid;
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
}
