// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl;

import com.zextras.carbonio.files.dal.dao.ebean.VersionReservation;
import com.zextras.carbonio.files.dal.dao.ebean.VersionReservationPK;
import com.zextras.carbonio.files.dal.dao.ebean.VersionReservationState;
import com.zextras.carbonio.files.dal.repositories.interfaces.VersionReservationRepository;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import java.util.List;

@ApplicationScoped
public class VersionReservationRepositoryImpl
    implements VersionReservationRepository,
        PanacheRepositoryBase<VersionReservation, VersionReservationPK> {

  @Override
  @Transactional(TxType.MANDATORY)
  public void reserve(String nodeId, int version, String ownerId, long reservedAt) {
    persist(
        new VersionReservation(
            nodeId, version, ownerId, reservedAt, VersionReservationState.PENDING));
  }

  @Override
  @Transactional(TxType.MANDATORY)
  public boolean consume(String nodeId, int version) {
    return delete(
            "mComposedId.mNodeId = ?1 and mComposedId.mVersion = ?2 and mState = ?3",
            nodeId,
            version,
            VersionReservationState.PENDING)
        == 1;
  }

  @Override
  @Transactional(TxType.MANDATORY)
  public boolean markOverwrite(String nodeId, int version, String ownerId, long reservedAt) {
    if (findByIdOptional(new VersionReservationPK(nodeId, version)).isPresent()) {
      return false;
    }
    persist(
        new VersionReservation(
            nodeId, version, ownerId, reservedAt, VersionReservationState.OVERWRITING));
    flush();
    return true;
  }

  @Override
  @Transactional
  public boolean releaseOverwrite(String nodeId, int version) {
    return delete(
            "mComposedId.mNodeId = ?1 and mComposedId.mVersion = ?2 and mState = ?3",
            nodeId,
            version,
            VersionReservationState.OVERWRITING)
        == 1;
  }

  @Override
  @Transactional
  public int removeStaleOverwrites(long reservedBefore) {
    return (int)
        delete(
            "mState = ?1 and mReservedAt < ?2",
            VersionReservationState.OVERWRITING,
            reservedBefore);
  }

  @Override
  @Transactional
  public int claimExpired(long reservedBefore) {
    return update(
        "mState = ?1 where mState = ?2 and mReservedAt < ?3",
        VersionReservationState.PURGING,
        VersionReservationState.PENDING,
        reservedBefore);
  }

  @Override
  @Transactional
  public List<VersionReservation> getPurging() {
    return list("mState", VersionReservationState.PURGING);
  }

  @Override
  @Transactional
  public void remove(VersionReservation reservation) {
    deleteById(reservation.getId());
  }

  @Override
  @Transactional
  public void recordFailedAttempt(VersionReservation reservation) {
    update(
        "mAttempts = mAttempts + 1 where mComposedId.mNodeId = ?1 and mComposedId.mVersion = ?2"
            + " and mState = ?3",
        reservation.getNodeId(),
        reservation.getVersion(),
        VersionReservationState.PURGING);
  }
}
