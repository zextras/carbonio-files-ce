// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.locks;

import com.zextras.carbonio.files.Constants.Db.RootId;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
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

  static final String LOCK_TIMEOUT = "5s";

  private static final Set<String> ROOTS = Set.of(RootId.LOCAL_ROOT, RootId.TRASH_ROOT);

  private static final String DEPTH =
      "length(coalesce(ancestor_ids, '')) - length(replace(coalesce(ancestor_ids, ''), ',', ''))";

  @Inject EntityManager entityManager;

  /** Must be the first statement of the writing transaction: every later read is fresh. */
  @Transactional(TxType.MANDATORY)
  public Set<String> lock(Collection<LockKey> keys) {
    if (entityManager.unwrap(Session.class).isDirty()) {
      throw new IllegalStateException("Node locks must be taken before any write");
    }
    entityManager
        .createNativeQuery("SET LOCAL lock_timeout = '" + LOCK_TIMEOUT + "'")
        .executeUpdate();
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
    @SuppressWarnings("unchecked")
    List<String> rows =
        entityManager
            .createNativeQuery(
                "SELECT trim(node_id) FROM node WHERE folder_id IN (:parents) AND node_type ="
                    + " 'FOLDER' ORDER BY node_id FOR NO KEY UPDATE")
            .setParameter("parents", parentIds.stream().map(String::trim).toList())
            .getResultList();
    return rows;
  }

  /** Non-locking read used to predict a lock set; compare with the fresh state after locking. */
  public List<NodePlace> placesOf(Collection<String> nodeIds) {
    if (nodeIds.isEmpty()) {
      return List.of();
    }
    @SuppressWarnings("unchecked")
    List<Object[]> rows =
        entityManager
            .createNativeQuery(
                "SELECT trim(node_id), trim(folder_id), owner_id FROM node WHERE node_id IN (:ids)")
            .setParameter("ids", nodeIds.stream().map(String::trim).toList())
            .getResultList();
    return rows.stream()
        .map(row -> new NodePlace((String) row[0], (String) row[1], (String) row[2]))
        .toList();
  }

  /** Non-locking read of the trashed rows' original parents. */
  public Map<String, String> trashedParentsOf(Collection<String> nodeIds) {
    if (nodeIds.isEmpty()) {
      return Map.of();
    }
    @SuppressWarnings("unchecked")
    List<Object[]> rows =
        entityManager
            .createNativeQuery(
                "SELECT trim(node_id), trim(parent_id) FROM trashed WHERE node_id IN (:ids)")
            .setParameter("ids", nodeIds.stream().map(String::trim).toList())
            .getResultList();
    return rows.stream().collect(Collectors.toMap(row -> (String) row[0], row -> (String) row[1]));
  }
}
