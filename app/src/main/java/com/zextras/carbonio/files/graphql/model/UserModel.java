// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.model;

import org.eclipse.microprofile.graphql.Id;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Type;

@Type("User")
public class UserModel implements SharedTarget, Account {

  private final String id;
  private final String email;
  private final String fullName;

  public UserModel(String id, String email, String fullName) {
    this.id = id;
    this.email = email;
    this.fullName = fullName;
  }

  @Id
  @NonNull
  public String getId() {
    return id;
  }

  @NonNull
  public String getEmail() {
    return email;
  }

  @NonNull
  @Name("full_name")
  public String getFullName() {
    return fullName;
  }
}
