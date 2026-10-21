// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.dao.ebean;

import com.zextras.carbonio.files.Constants;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * A version number handed out to an upload-version or a cloneVersion and not confirmed yet: the
 * blob it names is not referenced by any revision, and a reservation never confirmed is purged
 * together with that blob.
 */
@Entity
@Table(name = Constants.Db.Tables.VERSION_RESERVATION)
public class VersionReservation {

  /** Protected no-arg constructor required by Hibernate/JPA. */
  protected VersionReservation() {}

  @EmbeddedId private VersionReservationPK mComposedId;

  @Column(name = Constants.Db.VersionReservation.OWNER_ID, length = 256, nullable = false)
  private String mOwnerId;

  @Column(name = Constants.Db.VersionReservation.RESERVED_AT, nullable = false)
  private Long mReservedAt;

  @Column(name = Constants.Db.VersionReservation.STATE, length = 16, nullable = false)
  @Enumerated(EnumType.STRING)
  private VersionReservationState mState;

  @Column(name = Constants.Db.VersionReservation.ATTEMPTS, nullable = false)
  private Integer mAttempts;

  public VersionReservation(
      String nodeId, int version, String ownerId, long reservedAt, VersionReservationState state) {
    mComposedId = new VersionReservationPK(nodeId, version);
    mOwnerId = ownerId;
    mReservedAt = reservedAt;
    mState = state;
    mAttempts = 0;
  }

  public VersionReservationPK getId() {
    return mComposedId;
  }

  public String getNodeId() {
    return mComposedId.getNodeId().trim();
  }

  public int getVersion() {
    return mComposedId.getVersion();
  }

  public String getOwnerId() {
    return mOwnerId;
  }

  public long getReservedAt() {
    return mReservedAt;
  }

  public VersionReservationState getState() {
    return mState;
  }

  public int getAttempts() {
    return mAttempts;
  }
}
