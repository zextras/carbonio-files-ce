// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl.ebean;

import com.google.inject.Inject;
import com.zextras.carbonio.files.Files;
import com.zextras.carbonio.files.dal.EbeanDatabaseManager;
import com.zextras.carbonio.files.dal.dao.ebean.FileVersion;
import com.zextras.carbonio.files.dal.dao.ebean.Tombstone;
import com.zextras.carbonio.files.dal.repositories.interfaces.TombstoneRepository;
import io.ebean.Transaction;
import java.util.List;
import java.util.Optional;

public class TombstoneRepositoryEbean implements TombstoneRepository {

  private EbeanDatabaseManager mDB;

  @Inject
  public TombstoneRepositoryEbean(EbeanDatabaseManager EbeanDatabaseManager) {
    mDB = EbeanDatabaseManager;
  }

  @Override
  public void deleteTombstones() {
    mDB.getEbeanDatabase()
      .find(Tombstone.class)
      .delete();
  }

  @Override
  public List<Tombstone> getTombstones() {
    return mDB.getEbeanDatabase()
      .find(Tombstone.class)
      //.setMaxRows(50) TODO: consider pagination
      .findList();
  }

  @Override
  public Optional<Tombstone> createNewTombstone(
    String nodeId,
    String ownerId,
    Integer version
  ) {

    // Check if the same Tombstone already exists
    if (
      mDB
        .getEbeanDatabase()
        .find(Tombstone.class)
        .where()
        .eq(Files.Db.Tombstone.NODE_ID, nodeId)
        .eq(Files.Db.Tombstone.VERSION, version)
        .exists()
    ) {
      return Optional.empty();
    }

    // The specified Tombstone does not exist, so let's create it
    Tombstone tombstone = new Tombstone(
      nodeId,
      ownerId,
      System.currentTimeMillis(),
      version
    );

    // Save the newly created Tombstone in the DB and return its Optional
    mDB.getEbeanDatabase().save(tombstone);
    return Optional.of(tombstone);
  }

  @Override
  public void createTombstonesBulk(
    List<FileVersion> fileVersions,
    String ownerId
  ) {
    try (Transaction transaction = mDB.getEbeanDatabase().beginTransaction()) {
      transaction.setBatchMode(true);
      transaction.setBatchSize(50);

      fileVersions.forEach(fileVersion -> createNewTombstone(
        fileVersion.getNodeId(),
        ownerId,
        fileVersion.getVersion()
      ));

      transaction.commit();
    }

  }
}
