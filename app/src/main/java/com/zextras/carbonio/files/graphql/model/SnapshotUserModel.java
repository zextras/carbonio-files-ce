// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.model;

import org.eclipse.microprofile.graphql.Id;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Type;

@Type("SnapshotUser")
public class SnapshotUserModel {

  private final String snapshotUserId;
  private final String userId;
  private final String fullName;
  private final String email;

  public SnapshotUserModel(String snapshotUserId, String userId, String fullName, String email) {
    this.snapshotUserId = snapshotUserId;
    this.userId = userId;
    this.fullName = fullName;
    this.email = email;
  }

  @Id
  @NonNull
  @Name("snapshot_user_id")
  public String getSnapshotUserId() {
    return snapshotUserId;
  }

  @Id
  @NonNull
  @Name("user_id")
  public String getUserId() {
    return userId;
  }

  @NonNull
  @Name("full_name")
  public String getFullName() {
    return fullName;
  }

  @NonNull
  public String getEmail() {
    return email;
  }
}
