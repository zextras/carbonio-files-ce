// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.model;

import org.eclipse.microprofile.graphql.Id;
import org.eclipse.microprofile.graphql.Ignore;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Type;

@Type("Link")
public class LinkModel {

  private final String id;
  private final String url;
  private final long createdAt;
  private final Long expiresAt;
  private final String description;
  private final String accessCode;
  private final String nodeId;

  public LinkModel(
      String id,
      String url,
      long createdAt,
      Long expiresAt,
      String description,
      String accessCode,
      String nodeId) {
    this.id = id;
    this.url = url;
    this.createdAt = createdAt;
    this.expiresAt = expiresAt;
    this.description = description;
    this.accessCode = accessCode;
    this.nodeId = nodeId;
  }

  @Id
  @NonNull
  public String getId() {
    return id;
  }

  public String getUrl() {
    return url;
  }

  @Name("created_at")
  public long getCreatedAt() {
    return createdAt;
  }

  @Name("expires_at")
  public Long getExpiresAt() {
    return expiresAt;
  }

  public String getDescription() {
    return description;
  }

  @Name("access_code")
  public String getAccessCode() {
    return accessCode;
  }

  @Ignore
  public String getNodeId() {
    return nodeId;
  }
}
