// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl;

import com.zextras.carbonio.files.dal.dao.ebean.OverwriteLease;
import com.zextras.carbonio.files.dal.repositories.interfaces.OverwriteLeaseRepository;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import java.time.Duration;
import java.util.Optional;

@ApplicationScoped
public class OverwriteLeaseRepositoryImpl
    implements OverwriteLeaseRepository, PanacheRepositoryBase<OverwriteLease, String> {

  @Override
  @Transactional(TxType.MANDATORY)
  public boolean take(
      String nodeId, int version, String token, long registeredAt, Duration duration) {
    int takenOver =
        update(
            "mVersion = ?1, mToken = ?2, mRegisteredAt = ?3, mExpiresAt = "
                + DatabaseClock.NOW_MILLIS
                + " + ?4 where mNodeId = ?5 and mExpiresAt < "
                + DatabaseClock.NOW_MILLIS,
            version,
            token,
            registeredAt,
            duration.toMillis(),
            nodeId);
    if (takenOver == 1) {
      return true;
    }
    if (findByIdOptional(nodeId).isPresent()) {
      return false;
    }
    getEntityManager()
        .createQuery(
            "insert into OverwriteLease (mNodeId, mVersion, mToken, mRegisteredAt, mExpiresAt)"
                + " values (:nodeId, :version, :token, :registeredAt, "
                + DatabaseClock.NOW_MILLIS
                + " + :duration)")
        .setParameter("nodeId", nodeId)
        .setParameter("version", version)
        .setParameter("token", token)
        .setParameter("registeredAt", registeredAt)
        .setParameter("duration", duration.toMillis())
        .executeUpdate();
    return true;
  }

  @Override
  @Transactional
  public boolean release(String nodeId, String token) {
    return delete("mNodeId = ?1 and mToken = ?2", nodeId, token) == 1;
  }

  @Override
  @Transactional
  public int removeExpired(Duration expiredFor) {
    return (int)
        delete("mExpiresAt < " + DatabaseClock.NOW_MILLIS + " - ?1", expiredFor.toMillis());
  }

  @Override
  @Transactional
  public Optional<OverwriteLease> find(String nodeId) {
    return findByIdOptional(nodeId);
  }
}
