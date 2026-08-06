// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl;

import com.zextras.carbonio.files.dal.dao.ebean.FileVersion;
import com.zextras.carbonio.files.dal.dao.ebean.Tombstone;
import com.zextras.carbonio.files.dal.dao.ebean.TombstonePK;
import com.zextras.carbonio.files.dal.repositories.interfaces.TombstoneRepository;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.Optional;

/**
 * Panache implementation of {@link TombstoneRepository}. Replaces the old Ebean-based {@code
 * TombstoneRepositoryEbean}.
 */
@ApplicationScoped
public class TombstoneRepositoryImpl
    implements TombstoneRepository, PanacheRepositoryBase<Tombstone, TombstonePK> {

  @Override
  public List<Tombstone> getTombstones() {
    return listAll();
  }

  @Override
  @Transactional
  public Optional<Tombstone> createNewTombstone(String nodeId, String ownerId, Integer version) {
    TombstonePK id = new TombstonePK(nodeId, version);
    if (findByIdOptional(id).isPresent()) {
      return Optional.empty();
    }

    Tombstone tombstone = new Tombstone(nodeId, ownerId, System.currentTimeMillis(), version);
    persist(tombstone);
    return Optional.of(tombstone);
  }

  /**
   * Creates tombstones for each FileVersion WITHOUT opening its own transaction; it participates in
   * the caller's transaction.
   */
  @Override
  public void createTombstonesBulk(List<FileVersion> fileVersions, String ownerId) {
    fileVersions.forEach(
        fileVersion ->
            createNewTombstone(fileVersion.getNodeId(), ownerId, fileVersion.getVersion()));
  }

  @Override
  @Transactional
  public void deleteTombstonesByNodeAndVersion(String nodeId, Integer version) {
    deleteById(new TombstonePK(nodeId, version));
  }

  @Override
  @Transactional
  public void updateTombstone(Tombstone tombstone) {
    getEntityManager().merge(tombstone);
  }
}
