// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.dao.ebean;

import com.zextras.carbonio.files.Constants;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The overwrite of a node in progress: only one may start at a time, until it ends or its lease
 * expires. Unrelated to the version reservations.
 */
@Entity
@Table(name = Constants.Db.Tables.OVERWRITE_LEASE)
public class OverwriteLease {

  /** Protected no-arg constructor required by Hibernate/JPA. */
  protected OverwriteLease() {}

  @Id
  @Column(name = Constants.Db.OverwriteLease.NODE_ID, length = 36)
  private String mNodeId;

  @Column(name = Constants.Db.OverwriteLease.VERSION, nullable = false)
  private Integer mVersion;

  @Column(name = Constants.Db.OverwriteLease.TOKEN, length = 36, nullable = false)
  private String mToken;

  @Column(name = Constants.Db.OverwriteLease.REGISTERED_AT, nullable = false)
  private Long mRegisteredAt;

  @Column(name = Constants.Db.OverwriteLease.EXPIRES_AT, nullable = false)
  private Long mExpiresAt;

  public String getNodeId() {
    return mNodeId;
  }

  public int getVersion() {
    return mVersion;
  }

  public String getToken() {
    return mToken;
  }

  public long getRegisteredAt() {
    return mRegisteredAt;
  }

  public long getExpiresAt() {
    return mExpiresAt;
  }
}
