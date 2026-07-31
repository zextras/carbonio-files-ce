// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl.ebean;

import com.google.inject.Inject;
import com.zextras.carbonio.files.Constants;
import com.zextras.carbonio.files.dal.DatabaseManager;
import com.zextras.carbonio.files.dal.dao.ebean.FileVersion;
import com.zextras.carbonio.files.dal.dao.ebean.Tombstone;
import com.zextras.carbonio.files.dal.repositories.interfaces.TombstoneRepository;
import java.util.List;
import java.util.Optional;

public class TombstoneRepositoryEbean implements TombstoneRepository {

  private final DatabaseManager dbManager;

  @Inject
  public TombstoneRepositoryEbean(DatabaseManager DatabaseManagerFlyway) {
    dbManager = DatabaseManagerFlyway;
  }

  @Override
  public List<Tombstone> getTombstones() {
    return dbManager
        .getEbeanDatabase()
        .find(Tombstone.class)
        // .setMaxRows(50) TODO: consider pagination
        .findList();
  }

  @Override
  public Optional<Tombstone> createNewTombstone(String nodeId, String ownerId, Integer version) {

    // Check if the same Tombstone already exists
    if (dbManager
        .getEbeanDatabase()
        .find(Tombstone.class)
        .where()
        .eq(Constants.Db.Tombstone.NODE_ID, nodeId)
        .eq(Constants.Db.Tombstone.VERSION, version)
        .exists()) {
      return Optional.empty();
    }

    // The specified Tombstone does not exist, so let's create it
    Tombstone tombstone = new Tombstone(nodeId, ownerId, System.currentTimeMillis(), version);

    // Save the newly created Tombstone in the DB and return its Optional
    dbManager.getEbeanDatabase().save(tombstone);
    return Optional.of(tombstone);
  }

  /**
   * Creates tombstones for each FileVersion WITHOUT opening its own transaction. This method
   * participates in the caller's transaction.
   */
  @Override
  public void createTombstonesBulk(List<FileVersion> fileVersions, String ownerId) {
    fileVersions.forEach(
        fileVersion ->
            createNewTombstone(fileVersion.getNodeId(), ownerId, fileVersion.getVersion()));
  }

  @Override
  public void deleteTombstonesByNodeAndVersion(String nodeId, Integer version) {
    dbManager
        .getEbeanDatabase()
        .find(Tombstone.class)
        .where()
        .eq(Constants.Db.Tombstone.NODE_ID, nodeId)
        .eq(Constants.Db.Tombstone.VERSION, version)
        .delete();
  }

  @Override
  public void updateTombstone(Tombstone tombstone) {
    dbManager.getEbeanDatabase().update(tombstone);
  }
}
