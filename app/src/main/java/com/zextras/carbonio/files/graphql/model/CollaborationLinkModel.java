// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.model;

import org.eclipse.microprofile.graphql.Id;
import org.eclipse.microprofile.graphql.Ignore;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Type;

@Type("CollaborationLink")
public class CollaborationLinkModel {

  private final String id;
  private final String url;
  private final long createdAt;
  private final SharePermission permission;
  private final String nodeId;

  public CollaborationLinkModel(
      String id, String url, long createdAt, SharePermission permission, String nodeId) {
    this.id = id;
    this.url = url;
    this.createdAt = createdAt;
    this.permission = permission;
    this.nodeId = nodeId;
  }

  @Id
  @NonNull
  public String getId() {
    return id;
  }

  @NonNull
  public String getUrl() {
    return url;
  }

  @Name("created_at")
  public long getCreatedAt() {
    return createdAt;
  }

  @NonNull
  public SharePermission getPermission() {
    return permission;
  }

  @Ignore
  public String getNodeId() {
    return nodeId;
  }
}
