// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.dao;

import java.util.Locale;

/**
 * Represents a {@link UserMyself} entity that matches a Files user stored in Carbonio LDAP.
 *
 * <p>The implementation of constructors and setters should not care to check if the values in input
 * are valid or not because, when these methods are called, these controls <strong>must</strong> be
 * already done.
 */
public class UserMyself extends User {

  Locale locale;

  public UserMyself(String id, String fullName, String email, String domain, Locale locale) {
    super(id, fullName, email, domain);
    this.locale = locale;
  }

  public Locale getLocale() {
    return locale;
  }

  public void setLocale(Locale locale) {
    this.locale = locale;
  }
}
