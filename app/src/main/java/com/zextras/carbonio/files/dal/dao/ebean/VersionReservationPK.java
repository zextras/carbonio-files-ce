// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.dao.ebean;

import com.zextras.carbonio.files.Constants;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

/** Primary key of the {@link Constants.Db.Tables#VERSION_RESERVATION} table: node id + version. */
@Embeddable
public class VersionReservationPK implements Serializable {

  /** Protected no-arg constructor required by Hibernate/JPA. */
  protected VersionReservationPK() {}

  @Column(name = Constants.Db.VersionReservation.NODE_ID, nullable = false)
  private String mNodeId;

  @Column(name = Constants.Db.VersionReservation.VERSION, nullable = false)
  private Integer mVersion;

  public VersionReservationPK(String nodeId, Integer version) {
    this.mNodeId = nodeId;
    this.mVersion = version;
  }

  public String getNodeId() {
    return mNodeId;
  }

  public Integer getVersion() {
    return mVersion;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof VersionReservationPK that)) {
      return false;
    }
    return Objects.equals(mNodeId, that.mNodeId) && Objects.equals(mVersion, that.mVersion);
  }

  @Override
  public int hashCode() {
    return Objects.hash(mNodeId, mVersion);
  }
}
