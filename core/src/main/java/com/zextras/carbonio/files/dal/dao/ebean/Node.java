// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.dao.ebean;

import static com.zextras.carbonio.files.dal.dao.ebean.NodeType.FOLDER;
import static com.zextras.carbonio.files.dal.dao.ebean.NodeType.ROOT;
import com.zextras.carbonio.files.Files;
import io.ebean.annotation.Cache;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.FetchType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.OneToMany;
import javax.persistence.Table;

/**
 * <p>Represents an Ebean {@link Node} entity that matches a record of the {@link
 * Files.Db.Tables#NODE} table.</p>
 * <p>The implementation of the constructor and setters should not care to check if the values in
 * input are valid or not because, when these methods are called, these controls
 * <strong>must</strong> be already done.</p>
 */
@Cache
@Entity
@Table(name = Files.Db.Tables.NODE)
public
class Node {

  public static final String ANCESTORS_SEPARATOR = ",";

  @Id
  @Column(name = Files.Db.Node.ID, length = 36, nullable = false)
  private String mId;

  @Column(name = Files.Db.Node.OWNER_ID, length = 36)
  private String mOwnerId;

  @Column(name = Files.Db.Node.CREATOR_ID, length = 36)
  private String mCreatorId;

  @Column(name = Files.Db.Node.EDITOR_ID, length = 256)
  private String mLastEditorId;

  @Column(name = Files.Db.Node.PARENT_ID, length = 36)
  private String mParentId;

  @Column(name = Files.Db.Node.ANCESTOR_IDS, length = 4096)
  private String mAncestorIds;

  @Column(name = Files.Db.Node.CREATED_AT, nullable = false)
  private Long mCreatedAt;

  @Column(name = Files.Db.Node.UPDATED_AT, nullable = false)
  private Long mUpdatedAt;

  @Column(name = Files.Db.Node.CATEGORY, nullable = false)
  private Short mNodeCategory;

  @Column(name = Files.Db.Node.TYPE, length = 50, nullable = false)
  @Enumerated(EnumType.STRING)
  private NodeType mNodeType;

  @Column(name = Files.Db.Node.NAME, length = 1024, nullable = false)
  private String mName;

  @Column(name = Files.Db.Node.DESCRIPTION, nullable = false)
  private String mDescription;

  @Column(name = Files.Db.Node.CURRENT_VERSION, nullable = true)
  private Integer mCurrentVersion;

  @Column(name = Files.Db.Node.INDEX_STATUS, nullable = false)
  private Integer mIndexStatus;

  @Column(name = Files.Db.Node.SIZE, nullable = false)
  private Long mSize;

  @OneToMany(fetch = FetchType.EAGER)
  @JoinColumn(name = Files.Db.Node.ID, referencedColumnName = Files.Db.NodeCustomAttributes.NODE_ID, insertable = false, updatable = false)
  private List<NodeCustomAttributes> mCustomAttributes;

  @OneToMany(fetch = FetchType.LAZY)
  @JoinColumn(name = Files.Db.Node.ID, referencedColumnName = Files.Db.Share.NODE_ID, insertable = false, updatable = false)
  private List<Share> mShares;

  @OneToMany(fetch = FetchType.LAZY)
  @JoinColumn(name = Files.Db.Node.ID, referencedColumnName = Files.Db.FileVersion.NODE_ID, insertable = false, updatable = false)
  private List<FileVersion> fileVersions;

  public Node(
    String nodeId,
    String creatorId,
    String ownerId,
    String parentId,
    Long createdAt,
    Long updatedAt,
    String name,
    String description,
    NodeType type,
    String ancestorIds,
    Long size
  ) {
    mId = nodeId;
    mCreatorId = creatorId;
    mOwnerId = ownerId;
    mParentId = parentId;
    mCreatedAt = createdAt;
    mUpdatedAt = updatedAt;
    mName = name;
    mDescription = description;
    mNodeType = type;
    mIndexStatus = 1;
    mAncestorIds = ancestorIds;
    switch (mNodeType) {
      case ROOT:
        mNodeCategory = NodeCategory.ROOT.getValue();
        break;
      case FOLDER:
        mNodeCategory = NodeCategory.FOLDER.getValue();
        break;
      default:
        mNodeCategory = NodeCategory.FILE.getValue();
        mCurrentVersion = 1;
    }
    mSize = size;
  }

  public String getOwnerId() {
    return mOwnerId;
  }

  public Node setOwnerId(String ownerId) {
    mOwnerId = ownerId;
    return this;
  }

  public String getId() {
    return mId.trim();
  }

  public Optional<String> getParentId() {
    return Optional.ofNullable(mParentId)
      .map(String::trim);
  }

  public Node setParentId(String parentId) {
    mParentId = parentId;
    return this;
  }

  public String getAncestorIds() {
    return mAncestorIds;
  }

  public Node setAncestorIds(String ancestorIds) {
    this.mAncestorIds = ancestorIds;
    return this;
  }

  public String getCreatorId() {
    return mCreatorId;
  }

  public Optional<String> getLastEditorId() {
    return Optional.ofNullable(mLastEditorId);
  }

  public Node setLastEditorId(String lastEditorId) {
    mLastEditorId = lastEditorId;
    return this;
  }

  public long getCreatedAt() {
    return mCreatedAt;
  }

  public long getUpdatedAt() {
    return mUpdatedAt;
  }

  public Node setUpdatedAt(long updateTimestamp) {
    mUpdatedAt = updateTimestamp;
    return this;
  }

  public NodeType getNodeType() {
    return mNodeType;
  }

  public Node setNodeType(NodeType nodeType) {
    mNodeType = nodeType;
    return this;
  }

  public NodeCategory getNodeCategory() {
    return NodeCategory.decode(mNodeCategory);
  }

  public String getFullName() {
    return mName;
  }

  public String getName() {
    if (!this.getNodeType().equals(FOLDER)
      && !this.getNodeType().equals(ROOT)
      && mName.lastIndexOf(".") != -1
    ) {
      return mName.substring(0, mName.lastIndexOf('.'));
    } else {
      return mName;
    }
  }

  public Node setName(String name) {
    mName = getExtension().map(extension -> name + "." + extension).orElse(name);
    return this;
  }

  public Optional<String> getExtension() {
    if (!this.getNodeType().equals(FOLDER)
      && !this.getNodeType().equals(ROOT)
      && mName.lastIndexOf(".") != -1
    ) {
      return Optional.of(mName.substring(mName.lastIndexOf('.') + 1));
    } else {
      return Optional.empty();
    }
  }

  public Optional<String> getDescription() {
    return Optional.ofNullable(mDescription);
  }

  public Node setDescription(String description) {
    mDescription = description;
    return this;
  }

  /**
   * @return an {@link Integer} of the node current version. If the node is a {@link
   * NodeType#FOLDER} then it returns 1.
   */
  public Integer getCurrentVersion() {
    return Optional.ofNullable(mCurrentVersion).orElse(1);
  }

  public Node setCurrentVersion(Integer version) {
    mCurrentVersion = version;
    return this;
  }

  public List<String> getAncestorsList() {
    return (mAncestorIds.isEmpty())
      ? new ArrayList<>()
      : Arrays.stream(mAncestorIds.split(ANCESTORS_SEPARATOR)).collect(Collectors.toList());
  }

  public Long getSize() {
    return mSize;
  }

  public Node setSize(long size) {
    mSize = size;
    return this;
  }

  public List<NodeCustomAttributes> getCustomAttributes() {
    return mCustomAttributes;
  }

  public List<FileVersion> getFileVersions() {
    return fileVersions;
  }
}
