// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl.ebean;

import com.google.inject.Inject;
import com.zextras.carbonio.files.Files;
import com.zextras.carbonio.files.Files.Db;
import com.zextras.carbonio.files.cache.Cache;
import com.zextras.carbonio.files.cache.CacheHandler;
import com.zextras.carbonio.files.dal.EbeanDatabaseManager;
import com.zextras.carbonio.files.dal.dao.ebean.FileVersion;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import io.ebean.Database;
import io.ebean.annotation.Transactional;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public class FileVersionRepositoryEbean implements FileVersionRepository {

  private EbeanDatabaseManager         mDB;
  private Optional<Cache<FileVersion>> mFileVersionCache;

  @Inject
  public FileVersionRepositoryEbean(
    EbeanDatabaseManager ebeanDatabaseManager,
    CacheHandler cacheHandler
  ) {
    mDB = ebeanDatabaseManager;
    mFileVersionCache = cacheHandler
      .getCache(Files.Cache.FILE_VERSION)
      .map(cache -> mFileVersionCache = Optional.of(cache))
      .orElse(Optional.empty());
  }

  private String getFileVersionId(
    String nodeId,
    int version
  ) {
    return nodeId + "/" + version;
  }

  private Optional<FileVersion> getRealFileVersion(
    String nodeId,
    int version
  ) {
    return mDB.getEbeanDatabase()
      .find(FileVersion.class)
      .where()
      .eq(Files.Db.FileVersion.NODE_ID, nodeId)
      .eq(Files.Db.FileVersion.VERSION, version)
      .findOneOrEmpty();
  }

  @Override
  public Optional<FileVersion> getFileVersion(
    String nodeId,
    int version
  ) {
    String fileVersionId = getFileVersionId(nodeId, version);
    return mFileVersionCache.map(cache -> {
        return Optional.ofNullable(cache
          .get(fileVersionId)
          .orElseGet(() -> {
            Optional<FileVersion> dbFileVersion = getRealFileVersion(nodeId, version);
            dbFileVersion.ifPresent(fileVersion -> cache.add(fileVersionId, fileVersion));
            return dbFileVersion.orElse(null);
          })
        );
      })
      .orElse(getRealFileVersion(nodeId, version));
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
  public List<FileVersion> getFileVersions(String nodeId) {

    return mDB.getEbeanDatabase()
      .find(FileVersion.class)
      .where()
      .eq(Files.Db.FileVersion.NODE_ID, nodeId)
      .order()
      .desc(Db.FileVersion.VERSION)
      .findList();
  }

  @Override
  public List<FileVersion> getFileVersions(
    String nodeId,
    Collection<Integer> versions
  ) {

    return mDB.getEbeanDatabase()
      .find(FileVersion.class)
      .where()
      .eq(Files.Db.FileVersion.NODE_ID, nodeId)
      .and()
      .in(Files.Db.FileVersion.VERSION, versions)
      .findList();
  }

  @Override
  public Optional<FileVersion> getLastFileVersion(String nodeId) {

    return getFileVersions(nodeId)
      .stream()
      .sorted(Comparator.comparingInt(FileVersion::getVersion).reversed())
      .findFirst();
  }

  @Override
  @Transactional
  public FileVersion updateFileVersion(FileVersion fileVersion) {
    mDB.getEbeanDatabase().update(fileVersion);
    mFileVersionCache.ifPresent(cache -> {
      cache.add(getFileVersionId(fileVersion.getNodeId(), fileVersion.getVersion()), fileVersion);
    });
    return fileVersion;
  }

  @Override
  @Transactional
  public boolean deleteFileVersion(FileVersion fileVersion) {
    boolean deleted = mDB.getEbeanDatabase().delete(fileVersion);
    if (deleted) {
      mFileVersionCache.map(cache -> cache.delete(fileVersion.getNodeId()));
    }
    return deleted;
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
}
