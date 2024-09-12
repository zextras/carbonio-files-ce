// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.dao.ebean;

import com.zextras.carbonio.files.Files;
import com.zextras.carbonio.files.Files.Db;
import java.util.Optional;
import javax.persistence.Column;
import javax.persistence.EmbeddedId;
import javax.persistence.Entity;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;

/**
 * <p>Represents an Ebean {@link FileVersion} entity that matches a record of the {@link
 * Files.Db.Tables#FILE_VERSION} table.</p>
 * <p>The implementation of constructors and setters should not care to check if the values in
 * input are valid or not because, when these methods are called, these controls
 * <strong>must</strong> be already done.</p>
 */
@Entity
@Table(name = Files.Db.Tables.FILE_VERSION)
public class FileVersion {

  @EmbeddedId
  private FileVersionPK mComposedId;

  @Column(name = Files.Db.FileVersion.LAST_EDITOR_ID, length = 256, nullable = false)
  private String mLastEditorId;

  @Column(name = Files.Db.FileVersion.UPDATED_AT, nullable = false)
  private Long mUpdatedAt;

  @Column(name = Files.Db.FileVersion.MIME_TYPE, length = 256, nullable = false)
  private String mMimeType;

  @Column(name = Files.Db.FileVersion.SIZE, nullable = false)
  private Long mSize;

  @Column(name = Files.Db.FileVersion.DIGEST, length = 128, nullable = false)
  private String mDigest;

  @Column(name = Files.Db.FileVersion.AUTOSAVE, nullable = false)
  private Boolean mIsAutosave;

  @Column(name = Files.Db.FileVersion.VERSION, nullable = false)
  private Integer mVersion;

  @Column(name = Db.FileVersion.IS_KEPT_FOREVER, nullable = false)
  private Boolean isKeptForever;

  @Column(name = Db.FileVersion.CLONED_FROM_VERSION, nullable = true)
  private Integer clonedFromVersion;

  @ManyToOne
  @JoinColumn(name = Files.Db.NodeCustomAttributes.NODE_ID, referencedColumnName = Files.Db.Node.ID, insertable = false, updatable = false)
  private Node node;

  public FileVersion(
    String nodeId,
    String lastEditorId,
    long updatedAt,
    int version,
    String mimeType,
    long size,
    String digest,
    boolean autosave
  ) {
    mComposedId = new FileVersionPK(nodeId, version);
    mLastEditorId = lastEditorId;
    mUpdatedAt = updatedAt;
    mMimeType = mimeType;
    mSize = size;
    mDigest = digest;
    mIsAutosave = autosave;
    isKeptForever = false;
    mVersion = version;
  }

  public String getNodeId() {
    return mComposedId.getNodeId()
      .trim();
  }

  public String getLastEditorId() {
    return mLastEditorId;
  }

  public long getUpdatedAt() {
    return mUpdatedAt;
  }

  public int getVersion() {
    return mVersion;
  }

  public String getMimeType() {
    return mMimeType;
  }

  public long getSize() {
    return mSize;
  }

  public String getDigest() {
    return mDigest;
  }

  public boolean isAutosave() {
    return mIsAutosave;
  }

  public Optional<Integer> getClonedFromVersion() {
    return Optional.ofNullable(clonedFromVersion);
  }

  public FileVersion setClonedFromVersion(Integer version) {
    clonedFromVersion = version;
    return this;
  }

  public FileVersion keepForever(boolean keep) {
    this.isKeptForever = keep;
    return this;
  }

  public boolean isKeptForever() {
    return isKeptForever;
  }
}
