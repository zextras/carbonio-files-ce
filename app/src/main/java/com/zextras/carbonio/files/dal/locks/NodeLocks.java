// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.locks;

import com.zextras.carbonio.files.Constants.Db.RootId;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.FlushModeType;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.hibernate.Session;

@ApplicationScoped
public class NodeLocks {

  public record NodePlace(String nodeId, String parentId, String ownerId) {}

  private static final String LOCK_TIMEOUT = "5s";

  private static final int LOCK_CHUNK = 1000;

  private static final Set<String> ROOTS = Set.of(RootId.LOCAL_ROOT, RootId.TRASH_ROOT);

  private static final String DEPTH =
      "length(coalesce(ancestor_ids, '')) - length(replace(coalesce(ancestor_ids, ''), ',', ''))";

  @Inject EntityManager entityManager;

  /**
   * Must be the first statement of the writing transaction: entities loaded before are detached,
   * reload them; every later read is fresh. Callable once per transaction.
   */
  @Transactional(TxType.MANDATORY)
  public Set<String> lock(Collection<LockKey> keys) {
    if (entityManager.unwrap(Session.class).isDirty()) {
      throw new IllegalStateException("Node locks must be taken before any write");
    }
    Object[] state =
        (Object[])
            entityManager
                .createNativeQuery(
                    "SELECT set_config('lock_timeout', :timeout, true),"
                        + " pg_current_xact_id_if_assigned() IS NULL")
                .setParameter("timeout", LOCK_TIMEOUT)
                .getSingleResult();
    if (!Boolean.TRUE.equals(state[1])) {
      throw new IllegalStateException("Node locks must be taken before any write");
    }
    keys.stream()
        .filter(key -> key.kind() == LockKey.Kind.CHILDREN && ROOTS.contains(key.nodeId()))
        .map(key -> "files-children/" + key.nodeId() + "/" + key.ownerId())
        .distinct()
        .sorted()
        .forEach(
            key ->
                entityManager
                    .createNativeQuery(
                        "SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(:key, 0))")
                    .setParameter("key", key)
                    .getSingleResult());
    List<String> rowIds =
        keys.stream().map(LockKey::nodeId).filter(id -> !ROOTS.contains(id)).distinct().toList();
    Set<String> locked = new HashSet<>();
    if (!rowIds.isEmpty()) {
      @SuppressWarnings("unchecked")
      List<String> rows =
          entityManager
              .createNativeQuery(
                  "SELECT trim(node_id) FROM node WHERE node_id IN (:ids) ORDER BY "
                      + DEPTH
                      + ", node_id FOR NO KEY UPDATE")
              .setParameter("ids", rowIds)
              .getResultList();
      locked.addAll(rows);
    }
    entityManager.clear();
    return locked;
  }

  /** Locks the folder children of the given folders; cascades call it before reading below them. */
  @Transactional(TxType.MANDATORY)
  public List<String> lockChildFolders(Collection<String> parentIds) {
    if (parentIds.isEmpty()) {
      return List.of();
    }
    List<String> sorted = parentIds.stream().map(String::trim).distinct().sorted().toList();
    List<String> result = new ArrayList<>();
    for (int from = 0; from < sorted.size(); from += LOCK_CHUNK) {
      @SuppressWarnings("unchecked")
      List<String> rows =
          entityManager
              .createNativeQuery(
                  "SELECT trim(node_id) FROM node WHERE folder_id IN (:parents) AND node_type ="
                      + " 'FOLDER' ORDER BY "
                      + DEPTH
                      + ", node_id FOR NO KEY UPDATE")
              .setParameter(
                  "parents", sorted.subList(from, Math.min(from + LOCK_CHUNK, sorted.size())))
              .getResultList();
      result.addAll(rows);
    }
    return result;
  }

  /**
   * Locks every child, of any type, of the given folders; cascades call it before reading below
   * them.
   */
  @Transactional(TxType.MANDATORY)
  public List<String> lockChildren(Collection<String> parentIds) {
    if (parentIds.isEmpty()) {
      return List.of();
    }
    List<String> sorted = parentIds.stream().map(String::trim).distinct().sorted().toList();
    List<String> result = new ArrayList<>();
    for (int from = 0; from < sorted.size(); from += LOCK_CHUNK) {
      @SuppressWarnings("unchecked")
      List<String> rows =
          entityManager
              .createNativeQuery(
                  "SELECT trim(node_id) FROM node WHERE folder_id IN (:parents) ORDER BY "
                      + DEPTH
                      + ", node_id FOR NO KEY UPDATE")
              .setParameter(
                  "parents", sorted.subList(from, Math.min(from + LOCK_CHUNK, sorted.size())))
              .getResultList();
      result.addAll(rows);
    }
    return result;
  }

  /** Locks the given node rows mid-transaction, ordered depth then id; returns the locked ids. */
  @Transactional(TxType.MANDATORY)
  public List<String> lockRows(Collection<String> nodeIds) {
    if (nodeIds.isEmpty()) {
      return List.of();
    }
    List<String> sorted = nodeIds.stream().map(String::trim).distinct().sorted().toList();
    List<String> result = new ArrayList<>();
    for (int from = 0; from < sorted.size(); from += LOCK_CHUNK) {
      @SuppressWarnings("unchecked")
      List<String> rows =
          entityManager
              .createNativeQuery(
                  "SELECT trim(node_id) FROM node WHERE node_id IN (:ids) ORDER BY "
                      + DEPTH
                      + ", node_id FOR NO KEY UPDATE")
              .setParameter("ids", sorted.subList(from, Math.min(from + LOCK_CHUNK, sorted.size())))
              .getResultList();
      result.addAll(rows);
    }
    return result;
  }

  /**
   * Non-locking read used to predict a lock set; compare with the fresh state after locking. Needs
   * an active transaction or request context like any repository read.
   */
  public List<NodePlace> placesOf(Collection<String> nodeIds) {
    if (nodeIds.isEmpty()) {
      return List.of();
    }
    @SuppressWarnings("unchecked")
    List<Object[]> rows =
        entityManager
            .createNativeQuery(
                "SELECT trim(node_id), trim(folder_id), owner_id FROM node WHERE node_id IN (:ids)")
            .setFlushMode(FlushModeType.COMMIT)
            .setParameter("ids", nodeIds.stream().map(String::trim).toList())
            .getResultList();
    return rows.stream()
        .map(row -> new NodePlace((String) row[0], (String) row[1], (String) row[2]))
        .toList();
  }

  /**
   * Non-locking read of the trashed rows' original parents. Needs an active transaction or request
   * context like any repository read.
   */
  public Map<String, String> trashedParentsOf(Collection<String> nodeIds) {
    if (nodeIds.isEmpty()) {
      return Map.of();
    }
    @SuppressWarnings("unchecked")
    List<Object[]> rows =
        entityManager
            .createNativeQuery(
                "SELECT trim(node_id), trim(parent_id) FROM trashed WHERE node_id IN (:ids)")
            .setFlushMode(FlushModeType.COMMIT)
            .setParameter("ids", nodeIds.stream().map(String::trim).toList())
            .getResultList();
    return rows.stream().collect(Collectors.toMap(row -> (String) row[0], row -> (String) row[1]));
  }

  /**
   * Non-locking read: the ids among {@code parentIds} that cannot receive a restored node, because
   * the row does not exist or sits in the trash.
   */
  public Set<String> unusableRestoreParents(Collection<String> parentIds) {
    if (parentIds.isEmpty()) {
      return Set.of();
    }
    List<String> ids = parentIds.stream().map(String::trim).toList();
    @SuppressWarnings("unchecked")
    List<String> usable =
        entityManager
            .createNativeQuery(
                "SELECT trim(node_id) FROM node WHERE node_id IN (:ids) AND node_id <> 'TRASH_ROOT'"
                    + " AND coalesce(folder_id, '') <> 'TRASH_ROOT' AND position('TRASH_ROOT' in"
                    + " coalesce(ancestor_ids, '')) = 0")
            .setFlushMode(FlushModeType.COMMIT)
            .setParameter("ids", ids)
            .getResultList();
    Set<String> unusable = new HashSet<>(ids);
    unusable.removeAll(usable);
    return unusable;
  }
}
