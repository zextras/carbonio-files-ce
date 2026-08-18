// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.dao.ebean;

import com.zextras.carbonio.files.Constants;
import com.zextras.carbonio.files.Constants.Db;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.Optional;

/**
 * Represents an Ebean {@link FileVersion} entity that matches a record of the {@link
 * Constants.Db.Tables#FILE_VERSION} table.
 *
 * <p>The implementation of constructors and setters should not care to check if the values in input
 * are valid or not because, when these methods are called, these controls <strong>must</strong> be
 * already done.
 */
@Entity
@Table(name = Constants.Db.Tables.FILE_VERSION)
public class FileVersion {

  /** Protected no-arg constructor required by Hibernate/JPA. */
  protected FileVersion() {}

  @EmbeddedId private FileVersionPK mComposedId;

  @Column(name = Constants.Db.FileVersion.LAST_EDITOR_ID, length = 256, nullable = false)
  private String mLastEditorId;

  @Column(name = Constants.Db.FileVersion.UPDATED_AT, nullable = false)
  private Long mUpdatedAt;

  @Column(name = Constants.Db.FileVersion.MIME_TYPE, length = 256, nullable = false)
  private String mMimeType;

  @Column(name = Constants.Db.FileVersion.SIZE, nullable = false)
  private Long mSize;

  @Column(name = Constants.Db.FileVersion.DIGEST, length = 128, nullable = false)
  private String mDigest;

  @Column(name = Constants.Db.FileVersion.AUTOSAVE, nullable = false)
  private Boolean mIsAutosave;

  @Column(
      name = Constants.Db.FileVersion.VERSION,
      nullable = false,
      insertable = false,
      updatable = false)
  private Integer mVersion;

  @Column(name = Db.FileVersion.IS_KEPT_FOREVER, nullable = false)
  private Boolean isKeptForever;

  @Column(name = Db.FileVersion.CLONED_FROM_VERSION, nullable = true)
  private Integer clonedFromVersion;

  // P5a fix: this entity used to also carry a read-only, insertable=false/updatable=false
  // @ManyToOne "node" shadow association onto the SAME physical column that is ALSO part of
  // mComposedId's embedded id - purely so getFileVersionsRelatedToNodesHavingVersionsGreaterThan
  // could write "join fv.node n" in HQL (no getter was ever exposed for it; nothing else read it).
  // That shape is a known Hibernate ORM trap: whenever a FileVersion's parent Node was ALSO loaded
  // independently in the same persistence context and then removed (e.g.
  // PurgeService.purgeTrashedNodes(), which loads a trashed node's FileVersions and then
  // re-queries+removes the Node in the very same transaction), Hibernate's flush-time
  // transient-reference check flagged the association as "an unsaved transient instance" even
  // though the row is genuinely persistent (neither EAGER nor LAZY fetch avoided it). Removed the
  // association entirely and rewrote that one query as a subquery instead (see
  // FileVersionRepositoryImpl) - same result set, no ORM association needed.

  public FileVersion(
      String nodeId,
      String lastEditorId,
      long updatedAt,
      int version,
      String mimeType,
      long size,
      String digest,
      boolean autosave) {
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
    return mComposedId.getNodeId().trim();
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
