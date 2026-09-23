// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.model;

import org.eclipse.microprofile.graphql.Id;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Type;

@Type("PublicFolder")
public class PublicFolderModel implements PublicNodeModel {

  private final String id;
  private final long createdAt;
  private final long updatedAt;
  private final String name;
  private final NodeType type;

  public PublicFolderModel(String id, long createdAt, long updatedAt, String name, NodeType type) {
    this.id = id;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
    this.name = name;
    this.type = type;
  }

  @Override
  @Id
  @NonNull
  public String getId() {
    return id;
  }

  @Override
  @Name("created_at")
  public long getCreatedAt() {
    return createdAt;
  }

  @Override
  @Name("updated_at")
  public long getUpdatedAt() {
    return updatedAt;
  }

  @Override
  @NonNull
  public String getName() {
    return name;
  }

  @Override
  @NonNull
  public NodeType getType() {
    return type;
  }
}
