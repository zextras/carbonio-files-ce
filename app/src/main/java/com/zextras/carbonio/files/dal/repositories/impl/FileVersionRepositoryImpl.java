// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl;

import com.zextras.carbonio.files.cache.Cache;
import com.zextras.carbonio.files.cache.CacheHandler;
import com.zextras.carbonio.files.dal.dao.ebean.FileVersion;
import com.zextras.carbonio.files.dal.dao.ebean.FileVersionPK;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.FileVersionSort;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.SortOrder;
import com.zextras.carbonio.files.dal.repositories.interfaces.FileVersionRepository;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Panache implementation of {@link FileVersionRepository}. Replaces the old Ebean-based {@code
 * FileVersionRepositoryEbean}.
 *
 * <p>P5: re-wires the read-through/write-through/evict {@code CacheHandler}-backed {@link Cache}
 * that the P2c port had deferred (former {@code TODO(P5)} sites). The cache is a pure
 * optimization: every path below still hits the database first/last as needed, so correctness
 * never depends on the cache being populated, warm, or even present.
 */
@ApplicationScoped
public class FileVersionRepositoryImpl
    implements FileVersionRepository, PanacheRepositoryBase<FileVersion, FileVersionPK> {

  @Inject CacheHandler cacheHandler;

  private static String cacheKey(String nodeId, int version) {
    return nodeId + "/" + version;
  }

  @Override
  public Optional<FileVersion> getFileVersion(String nodeId, int version) {
    Cache<FileVersion> cache = cacheHandler.getFileVersionCache();
    String key = cacheKey(nodeId, version);
    return cache.get(key).or(() -> {
      Optional<FileVersion> dbFileVersion = findByIdOptional(new FileVersionPK(nodeId, version));
      dbFileVersion.ifPresent(fileVersion -> cache.add(key, fileVersion));
      return dbFileVersion;
    });
  }

  @Override
  @Transactional
  public Optional<FileVersion> createNewFileVersion(
      String nodeId,
      String lastEditorId,
      int version,
      String mimeType,
      long size,
      String digest,
      boolean autosave) {
    if (getEntityManager().find(Node.class, nodeId) == null) {
      return Optional.empty();
    }

    FileVersion fileVersion =
        new FileVersion(
            nodeId, lastEditorId, System.currentTimeMillis(), version, mimeType, size, digest,
            autosave);
    persist(fileVersion);
    return Optional.of(fileVersion);
  }

  @Override
  public List<FileVersion> getFileVersions(String nodeId, List<FileVersionSort> sorts) {
    String orderBy =
        sorts.isEmpty()
            ? ""
            : " order by mVersion "
                + (sorts.get(sorts.size() - 1).getOrder() == SortOrder.DESCENDING ? "desc" : "asc");
    List<FileVersion> fileVersions = list("mComposedId.mNodeId = ?1" + orderBy, nodeId);
    populateCache(fileVersions);
    return fileVersions;
  }

  @Override
  public List<FileVersion> getFileVersions(String nodeId, Collection<Integer> versions) {
    List<FileVersion> fileVersions =
        list("mComposedId.mNodeId = ?1 and mVersion in ?2", nodeId, versions);
    populateCache(fileVersions);
    return fileVersions;
  }

  /** Write-through: mirrors every DB-fetched {@link FileVersion} into the cache. */
  private void populateCache(List<FileVersion> fileVersions) {
    Cache<FileVersion> cache = cacheHandler.getFileVersionCache();
    fileVersions.forEach(
        fileVersion -> cache.add(cacheKey(fileVersion.getNodeId(), fileVersion.getVersion()), fileVersion));
  }

  @Override
  public Optional<FileVersion> getLastFileVersion(String nodeId) {
    return find("mComposedId.mNodeId = ?1 order by mVersion desc", nodeId).firstResultOptional();
  }

  @Override
  @Transactional
  public FileVersion updateFileVersion(FileVersion fileVersion) {
    FileVersion merged = getEntityManager().merge(fileVersion);
    cacheHandler.getFileVersionCache().delete(cacheKey(fileVersion.getNodeId(), fileVersion.getVersion()));
    return merged;
  }

  @Override
  @Transactional
  public boolean deleteFileVersion(FileVersion fileVersion) {
    Optional<FileVersion> managed =
        findByIdOptional(new FileVersionPK(fileVersion.getNodeId(), fileVersion.getVersion()));
    managed.ifPresent(this::delete);
    cacheHandler.getFileVersionCache().delete(cacheKey(fileVersion.getNodeId(), fileVersion.getVersion()));
    return managed.isPresent();
  }

  @Override
  @Transactional
  public void deleteFileVersions(String nodeId, Collection<Integer> versions) {
    // Fetch-then-remove (rather than a bulk "delete ... where ... in" JPQL statement) so entities
    // already managed in the current persistence context are correctly evicted; JPA bulk
    // statements bypass the first-level cache and would otherwise leave stale managed instances
    // behind for the rest of the transaction.
    list("mComposedId.mNodeId = ?1 and mVersion in ?2", nodeId, versions).forEach(this::delete);
    Cache<FileVersion> cache = cacheHandler.getFileVersionCache();
    versions.forEach(version -> cache.delete(cacheKey(nodeId, version)));
  }

  @Override
  public Map<String, List<FileVersion>> getFileVersionsRelatedToNodesHavingVersionsGreaterThan(
      int maxNumberOfVersions) {
    // No ORM association from FileVersion to Node is used here on purpose (see the P5a comment on
    // the removed FileVersion.node field): a correlated subquery on the shared node id column
    // gives the identical result set without the Hibernate flush-time pitfall that association
    // carried.
    List<FileVersion> fileVersions =
        getEntityManager()
            .createQuery(
                "select fv from FileVersion fv where fv.mComposedId.mNodeId in"
                    + " (select n.mId from Node n where n.mCurrentVersion > :max)"
                    + " order by fv.mVersion asc",
                FileVersion.class)
            .setParameter("max", maxNumberOfVersions)
            .getResultList();

    return fileVersions.stream()
        .collect(
            Collectors.groupingBy(FileVersion::getNodeId, LinkedHashMap::new, Collectors.toList()));
  }
}
