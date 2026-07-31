// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.dao.ebean;

import com.zextras.carbonio.files.Constants;
import javax.persistence.Column;
import javax.persistence.EmbeddedId;
import javax.persistence.Entity;
import javax.persistence.Table;

/**
 * Represents an Ebean {@link Tombstone} entity that matches a record of the {@link
 * Constants.Db.Tables#TOMBSTONE} table.
 *
 * <p>The implementation of constructors and setters should not care to check if the values in input
 * are valid or not because, when these methods are called, these controls <strong>must</strong> be
 * already done.
 */
@Entity
@Table(name = Constants.Db.Tables.TOMBSTONE)
public class Tombstone {

  @EmbeddedId private TombstonePK mComposedId;

  @Column(name = Constants.Db.Tombstone.OWNER_ID, length = 256)
  private String mOwnerId;

  @Column(name = Constants.Db.Tombstone.TIMESTAMP, nullable = false)
  private Long mTimestamp;

  @Column(name = Constants.Db.Tombstone.VERSION, nullable = false)
  private Integer mVersion;

  @Column(name = Constants.Db.Tombstone.ATTEMPTS, nullable = false)
  private Integer mAttempts;

  public Tombstone(String nodeId, String ownerId, Long timestamp, Integer version) {
    mComposedId = new TombstonePK(nodeId, version);
    mOwnerId = ownerId;
    mTimestamp = timestamp;
    mVersion = version;
    mAttempts = 0;
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

  public Integer getAttempts() {
    return mAttempts == null ? 0 : mAttempts;
  }

  public Tombstone setAttempts(Integer attempts) {
    mAttempts = attempts;
    return this;
  }
}
