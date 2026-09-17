// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.model;

import org.eclipse.microprofile.graphql.Id;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Type;

@Type("PublicFile")
public class PublicFileModel implements PublicNodeModel {

  private final String id;
  private final long createdAt;
  private final long updatedAt;
  private final String name;
  private final String extension;
  private final NodeType type;
  private final String mimeType;
  private final double size;

  public PublicFileModel(
      String id,
      long createdAt,
      long updatedAt,
      String name,
      String extension,
      NodeType type,
      String mimeType,
      double size) {
    this.id = id;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
    this.name = name;
    this.extension = extension;
    this.type = type;
    this.mimeType = mimeType;
    this.size = size;
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

  public String getExtension() {
    return extension;
  }

  @Override
  @NonNull
  public NodeType getType() {
    return type;
  }

  @NonNull
  @Name("mime_type")
  public String getMimeType() {
    return mimeType;
  }

  public double getSize() {
    return size;
  }
}
