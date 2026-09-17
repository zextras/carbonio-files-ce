// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.model;

import org.eclipse.microprofile.graphql.Id;
import org.eclipse.microprofile.graphql.Ignore;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Type;

@Type("File")
public class FileModel implements NodeModel {

  private final String id;
  private final String parentId;
  private final String ownerId;
  private final String creatorId;
  private final String lastEditorId;
  private final long createdAt;
  private final long updatedAt;
  private final String name;
  private final String extension;
  private final String description;
  private final NodeType type;
  private final String mimeType;
  private final double size;
  private final String digest;
  private final int version;
  private final boolean keepForever;
  private final Integer clonedFromVersion;
  private final boolean flagged;
  private final String rootId;

  public FileModel(
      String id,
      String parentId,
      String ownerId,
      String creatorId,
      String lastEditorId,
      long createdAt,
      long updatedAt,
      String name,
      String extension,
      String description,
      NodeType type,
      String mimeType,
      double size,
      String digest,
      int version,
      boolean keepForever,
      Integer clonedFromVersion,
      boolean flagged,
      String rootId) {
    this.id = id;
    this.parentId = parentId;
    this.ownerId = ownerId;
    this.creatorId = creatorId;
    this.lastEditorId = lastEditorId;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
    this.name = name;
    this.extension = extension;
    this.description = description;
    this.type = type;
    this.mimeType = mimeType;
    this.size = size;
    this.digest = digest;
    this.version = version;
    this.keepForever = keepForever;
    this.clonedFromVersion = clonedFromVersion;
    this.flagged = flagged;
    this.rootId = rootId;
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
  public String getDescription() {
    return description;
  }

  @Override
  @NonNull
  public NodeType getType() {
    return type;
  }

  @Override
  public boolean isFlagged() {
    return flagged;
  }

  @Override
  @Id
  @Name("rootId")
  public String getRootId() {
    return rootId;
  }

  @Override
  @Ignore
  public String getParentId() {
    return parentId;
  }

  @Override
  @Ignore
  public String getOwnerId() {
    return ownerId;
  }

  @Override
  @Ignore
  public String getCreatorId() {
    return creatorId;
  }

  @Override
  @Ignore
  public String getLastEditorId() {
    return lastEditorId;
  }

  public String getExtension() {
    return extension;
  }

  @NonNull
  @Name("mime_type")
  public String getMimeType() {
    return mimeType;
  }

  public double getSize() {
    return size;
  }

  @NonNull
  public String getDigest() {
    return digest;
  }

  public int getVersion() {
    return version;
  }

  @Name("keep_forever")
  public boolean isKeepForever() {
    return keepForever;
  }

  @Name("cloned_from_version")
  public Integer getClonedFromVersion() {
    return clonedFromVersion;
  }
}
