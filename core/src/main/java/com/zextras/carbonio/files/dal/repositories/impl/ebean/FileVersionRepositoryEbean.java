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
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public class FileVersionRepositoryEbean implements FileVersionRepository {

  private EbeanDatabaseManager mDB;
  private Cache<FileVersion>   fileVersionCache;

  @Inject
  public FileVersionRepositoryEbean(
    EbeanDatabaseManager ebeanDatabaseManager,
    CacheHandler cacheHandler
  ) {
    mDB = ebeanDatabaseManager;
    fileVersionCache = cacheHandler.getFileVersionCache();
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
    return Optional.ofNullable(fileVersionCache
      .get(fileVersionId)
      .orElseGet(() -> {
        Optional<FileVersion> dbFileVersion = getRealFileVersion(nodeId, version);
        dbFileVersion.ifPresent(fileVersion -> fileVersionCache.add(fileVersionId, fileVersion));
        return dbFileVersion.orElse(null);
      })
    );
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
  public List<FileVersion> getFileVersions(String nodeId, boolean asc) {

    List<FileVersion> fileVersions;

    if(asc){
      fileVersions = mDB.getEbeanDatabase()
          .find(FileVersion.class)
          .where()
          .eq(Files.Db.FileVersion.NODE_ID, nodeId)
          .order()
          .asc(Db.FileVersion.VERSION)
          .findList();
    }else {
      fileVersions = mDB.getEbeanDatabase()
          .find(FileVersion.class)
          .where()
          .eq(Files.Db.FileVersion.NODE_ID, nodeId)
          .order()
          .desc(Db.FileVersion.VERSION)
          .findList();
    }
    fileVersions.forEach(fileVersion ->
      fileVersionCache.add(
        getFileVersionId(fileVersion.getNodeId(), fileVersion.getVersion()),
        fileVersion
      )
    );

    return fileVersions;
  }

  @Override
  public List<FileVersion> getFileVersions(
    String nodeId,
    Collection<Integer> versions
  ) {

    List<FileVersion> fileVersions = mDB.getEbeanDatabase()
      .find(FileVersion.class)
      .where()
      .eq(Files.Db.FileVersion.NODE_ID, nodeId)
      .and()
      .in(Files.Db.FileVersion.VERSION, versions)
      .findList();

    fileVersions.forEach(fileVersion ->
      fileVersionCache.add(
        getFileVersionId(fileVersion.getNodeId(), fileVersion.getVersion()),
        fileVersion
      )
    );

    return fileVersions;
  }

  @Override
  public Optional<FileVersion> getLastFileVersion(String nodeId) {

    return getFileVersions(nodeId, false)
      .stream()
      .sorted(Comparator.comparingInt(FileVersion::getVersion).reversed())
      .findFirst();
  }

  @Override
  public FileVersion updateFileVersion(FileVersion fileVersion) {
    mDB.getEbeanDatabase().update(fileVersion);
    fileVersionCache.delete(getFileVersionId(fileVersion.getNodeId(), fileVersion.getVersion()));
    return fileVersion;
  }

  @Override
  public boolean deleteFileVersion(FileVersion fileVersion) {
    boolean deleted = mDB.getEbeanDatabase().delete(fileVersion);
    fileVersionCache.delete(getFileVersionId(fileVersion.getNodeId(), fileVersion.getVersion()));
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

    versions.forEach(version -> fileVersionCache.delete(getFileVersionId(nodeId, version)));
  }
}
