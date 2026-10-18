// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.model;

import org.eclipse.microprofile.graphql.Ignore;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Type;

@Type("Share")
public class ShareModel {

  private final long createdAt;
  private final SharePermission permission;
  private final Long expiresAt;
  private final String nodeId;
  private final String shareTargetId;

  public ShareModel(
      long createdAt,
      SharePermission permission,
      Long expiresAt,
      String nodeId,
      String shareTargetId) {
    this.createdAt = createdAt;
    this.permission = permission;
    this.expiresAt = expiresAt;
    this.nodeId = nodeId;
    this.shareTargetId = shareTargetId;
  }

  @Name("created_at")
  public long getCreatedAt() {
    return createdAt;
  }

  @NonNull
  public SharePermission getPermission() {
    return permission;
  }

  @Name("expires_at")
  public Long getExpiresAt() {
    return expiresAt;
  }

  @Ignore
  public String getNodeId() {
    return nodeId;
  }

  @Ignore
  @Name("share_target_id")
  public String getShareTargetId() {
    return shareTargetId;
  }
}
