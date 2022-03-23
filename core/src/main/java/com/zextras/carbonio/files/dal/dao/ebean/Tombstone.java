// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.dao.ebean;

import com.zextras.carbonio.files.Files;
import javax.persistence.Column;
import javax.persistence.EmbeddedId;
import javax.persistence.Entity;
import javax.persistence.Table;

/**
 * <p>Represents an Ebean {@link Tombstone} entity that matches a record of the {@link
 * Files.Db.Tables#TOMBSTONE} table.</p>
 * <p>The implementation of constructors and setters should not care to check if the values in
 * input are valid or not because, when these methods are called, these controls
 * <strong>must</strong> be already done.</p>
 */
@Entity
@Table(name = Files.Db.Tables.TOMBSTONE)
public class Tombstone {

  @EmbeddedId
  private TombstonePK mComposedId;

  @Column(name = Files.Db.Tombstone.OWNER_ID, length = 256)
  private String mOwnerId;

  @Column(name = Files.Db.Tombstone.TIMESTAMP, nullable = false)
  private Long mTimestamp;

  @Column(name = Files.Db.Tombstone.VERSION, nullable = false)
  private Integer mVersion;

  public Tombstone(
    String nodeId,
    String ownerId,
    Long timestamp,
    Integer version
  ) {
    mComposedId = new TombstonePK(nodeId, version);
    mOwnerId = ownerId;
    mTimestamp = timestamp;
    mVersion = version;
  }

  public String getNodeId() {
    return mComposedId.getNodeId().trim();
  }

  public String getOwnerId() {
    return mOwnerId;
  }

  public Tombstone setOwnerId(String ownerId) {
    mOwnerId = ownerId;
    return this;
  }

  public Long getTimestamp() {
    return mTimestamp;
  }

  public Tombstone setTimestamp(Long timestamp) {
    mTimestamp = timestamp;
    return this;
  }

  public Integer getVersion() {
    return mVersion;
  }
}
