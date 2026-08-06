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
 * This class represents the primary key of the {@link Constants.Db.Tables#FILE_VERSION}. It is
 * composed by two fields:
 *
 * <ul>
 *   <li>{@link Constants.Db.FileVersion#NODE_ID}: the foreign key of the node identifier
 *   <li>{@link Constants.Db.FileVersion#VERSION}: an integer representing the version of the file
 * </ul>
 *
 * <p>This class is necessary to specify the primary key for the {@link FileVersion} and it is
 * useful to performs queries containing joins between {@link Constants.Db.Tables#FILE_VERSION} and
 * {@link Constants.Db.Tables#NODE} tables.
 */
@Embeddable
public class FileVersionPK implements Serializable {

  /** Protected no-arg constructor required by Hibernate/JPA. */
  protected FileVersionPK() {}

  @Column(name = Constants.Db.FileVersion.NODE_ID, nullable = false)
  private String mNodeId;

  @Column(name = Constants.Db.FileVersion.VERSION, nullable = false)
  private Integer mVersion;

  public FileVersionPK(String nodeId, Integer mVersion) {
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
    FileVersionPK that = (FileVersionPK) o;
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
