// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl.ebean;

import com.google.inject.Inject;
import com.zextras.carbonio.files.Constants;
import com.zextras.carbonio.files.Constants.Db;
import com.zextras.carbonio.files.dal.DatabaseManager;
import com.zextras.carbonio.files.dal.dao.ebean.FileVersion;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.FileVersionSort;
import com.zextras.carbonio.files.dal.repositories.interfaces.CollationRepository;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import io.ebean.Database;
import io.ebean.Query;

import java.util.*;
import java.util.stream.Collectors;

public class FileVersionRepositoryEbean implements FileVersionRepository {

  private DatabaseManager mDB;
  private CollationRepository collationRepository;

  @Inject
  public FileVersionRepositoryEbean(
    DatabaseManager databaseManagerFlyway,
    CollationRepository collationRepository
  ) {
    mDB = databaseManagerFlyway;
    this.collationRepository = collationRepository;
  }

  private Optional<FileVersion> getRealFileVersion(
    String nodeId,
    int version
  ) {
    return mDB.getEbeanDatabase()
      .find(FileVersion.class)
      .where()
      .eq(Constants.Db.FileVersion.NODE_ID, nodeId)
      .eq(Constants.Db.FileVersion.VERSION, version)
      .findOneOrEmpty();
  }

  @Override
  public Optional<FileVersion> getFileVersion(
    String nodeId,
    int version
  ) {
    return getRealFileVersion(nodeId, version);
  }

  @Override
  public Optional<FileVersion> createNewFileVersion(
    String nodeId,
    String lastEditorId,
    int version,
    String mimeType,
    long size,
    String digest,
    boolean autosave
  ) {
    Database db = mDB.getEbeanDatabase();
    if (!db.find(Node.class).where().idEq(nodeId).exists()) {
      return Optional.empty();
    }

    FileVersion fileVersion = new FileVersion(
      nodeId,
      lastEditorId,
      System.currentTimeMillis(),
      version,
      mimeType,
      size,
      digest,
      autosave
    );
    mDB.getEbeanDatabase().save(fileVersion);
    return getFileVersion(nodeId, version);
  }

  @Override
  public List<FileVersion> getFileVersions(String nodeId, List<FileVersionSort> sorts) {
    Query<FileVersion> query =
        mDB.getEbeanDatabase()
            .find(FileVersion.class)
            .where()
            .eq(Constants.Db.FileVersion.NODE_ID, nodeId)
            .query();

    sorts.forEach(sort -> sort.getOrderEbeanQuery(query, collationRepository.getValidCollateForQuery()));

    return query.findList();
  }

  @Override
  public List<FileVersion> getFileVersions(
    String nodeId,
    Collection<Integer> versions
  ) {

    return mDB.getEbeanDatabase()
      .find(FileVersion.class)
      .where()
      .eq(Constants.Db.FileVersion.NODE_ID, nodeId)
      .and()
      .in(Constants.Db.FileVersion.VERSION, versions)
      .findList();
  }

  @Override
  public Optional<FileVersion> getLastFileVersion(String nodeId) {

    return getFileVersions(nodeId, List.of(FileVersionSort.VERSION_DESC))
        .stream()
        .sorted(Comparator.comparingInt(FileVersion::getVersion).reversed())
        .findFirst();
  }

  @Override
  public FileVersion updateFileVersion(FileVersion fileVersion) {
    mDB.getEbeanDatabase().update(fileVersion);
    return fileVersion;
  }

  @Override
  public boolean deleteFileVersion(FileVersion fileVersion) {
    return mDB.getEbeanDatabase().delete(fileVersion);
  }

  public void deleteFileVersions(
    String nodeId,
    Collection<Integer> versions
  ) {
    mDB.getEbeanDatabase()
      .find(FileVersion.class)
      .where()
      .eq(Db.FileVersion.NODE_ID, nodeId)
      .and()
      .in(Db.FileVersion.VERSION, versions)
      .delete();
  }

  @Override
  public Map<String, List<FileVersion>> getFileVersionsRelatedToNodesHavingVersionsGreaterThan(
      int maxNumberOfVersions) {
    return mDB.getEbeanDatabase()
        .find(FileVersion.class)
        .fetch("node")
        .having()
        .gt("node.mCurrentVersion", maxNumberOfVersions)
        .orderBy()
        .asc(Db.FileVersion.VERSION)
        .setMapKey(Db.FileVersion.NODE_ID)
        .findList()
        .stream()
        .collect(Collectors.groupingBy(FileVersion::getNodeId));
  }
}
