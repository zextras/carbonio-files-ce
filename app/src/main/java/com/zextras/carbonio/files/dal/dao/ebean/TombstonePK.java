// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.dao.ebean;

import com.zextras.carbonio.files.Constants;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

/**
 * This class represents the primary key of the {@link Constants.Db.Tables#TOMBSTONE}. It is
 * composed by two fields:
 *
 * <ul>
 *   <li>{@link Constants.Db.Tombstone#NODE_ID}: the foreign key of the node identifier;
 *   <li>{@link Constants.Db.Tombstone#VERSION}: an integer representing the version of the file.
 * </ul>
 *
 * <p>This class is necessary to specify the primary key for the {@link Tombstone}.
 */
@Embeddable
public class TombstonePK implements Serializable {

  /** Protected no-arg constructor required by Hibernate/JPA. */
  protected TombstonePK() {}

  @Column(name = Constants.Db.Tombstone.NODE_ID, nullable = false)
  private String mNodeId;

  @Column(name = Constants.Db.Tombstone.VERSION, nullable = false)
  private Integer mVersion;

  public TombstonePK(String nodeId, Integer mVersion) {
    this.mNodeId = nodeId;
    this.mVersion = mVersion;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    TombstonePK that = (TombstonePK) o;
    return Objects.equals(mNodeId, that.mNodeId) && Objects.equals(mVersion, that.mVersion);
  }

  @Override
  public int hashCode() {
    return Objects.hash(mNodeId, mVersion);
  }

  String getNodeId() {
    return mNodeId;
  }

  Integer getVersion() {
    return mVersion;
  }
}
