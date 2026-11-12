// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl;

import com.zextras.carbonio.files.Constants.Db;
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.dal.dao.ebean.Share;
import com.zextras.carbonio.files.dal.dao.ebean.SharePK;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.ShareSort;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.SortOrder;
import com.zextras.carbonio.files.dal.repositories.interfaces.ShareRepository;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.hibernate.Session;
import org.hibernate.query.TypedParameterValue;
import org.hibernate.type.StandardBasicTypes;

/**
 * Panache implementation of {@link ShareRepository}. Replaces the old Ebean-based {@code
 * ShareRepositoryEbean}.
 */
@ApplicationScoped
public class ShareRepositoryImpl implements ShareRepository, PanacheRepositoryBase<Share, SharePK> {

  /** Maps a {@link ShareSort} DB column name to the entity property path used in HQL. */
  private static final Map<String, String> SORT_PROPERTIES_BY_COLUMN =
      Map.of(
          Db.Share.CREATED_AT, "createdAt",
          Db.Share.SHARE_TARGET_UUID, "composedPrimaryKey.mTargetUserId",
          Db.Share.PERMISSIONS, "permissions",
          Db.Share.EXPIRED_AT, "expiredAt");

  private static final String CASCADE_UPSERT_SQL =
      """
      WITH RECURSIVE subtree(node_id, node_type) AS (
          SELECT n.node_id, n.node_type
          FROM node n
          WHERE n.folder_id = CAST(:root AS CHARACTER(36))
            AND NOT EXISTS (SELECT 1 FROM trashed t WHERE t.node_id = n.node_id)
          UNION
          SELECT c.node_id, c.node_type
          FROM subtree s
          CROSS JOIN LATERAL (
              SELECT n.node_id, n.node_type
              FROM node n
              WHERE n.folder_id = s.node_id
                AND NOT EXISTS (SELECT 1 FROM trashed t WHERE t.node_id = n.node_id)
          ) c
          WHERE s.node_type = 'FOLDER'
      )
      INSERT INTO share (node_id, rights, timestamp, target_uuid, expire_date, direct,
                         created_via_link)
      SELECT s.node_id, CAST(:rights AS SMALLINT), CAST(:now AS BIGINT), :userId,
             CAST(:expire AS BIGINT), false, false
      FROM subtree s
      ON CONFLICT (node_id, target_uuid) DO UPDATE
      SET rights = EXCLUDED.rights,
          created_via_link = false,
          expire_date = COALESCE(EXCLUDED.expire_date, share.expire_date)
      WHERE share.direct = false
      RETURNING node_id
      """;

  private static final String CASCADE_DELETE_SQL =
      """
      WITH RECURSIVE subtree(node_id, node_type) AS (
          SELECT n.node_id, n.node_type
          FROM node n
          WHERE n.folder_id = CAST(:root AS CHARACTER(36))
          UNION
          SELECT n.node_id, n.node_type
          FROM trashed t
          INNER JOIN node n ON n.node_id = t.node_id
          WHERE t.parent_id = CAST(:root AS CHARACTER(36))
          UNION
          SELECT c.node_id, c.node_type
          FROM subtree s
          CROSS JOIN LATERAL (
              SELECT n.node_id, n.node_type FROM node n WHERE n.folder_id = s.node_id
              UNION ALL
              SELECT n.node_id, n.node_type
              FROM trashed t
              INNER JOIN node n ON n.node_id = t.node_id
              WHERE t.parent_id = s.node_id
          ) c
          WHERE s.node_type = 'FOLDER'
            AND NOT EXISTS (
                SELECT 1 FROM share d
                WHERE d.node_id = s.node_id AND d.target_uuid = :userId AND d.direct)
      )
      DELETE FROM share sh
      USING subtree s
      WHERE sh.node_id = s.node_id AND sh.target_uuid = :userId AND NOT sh.direct
      RETURNING sh.node_id
      """;

  private String buildOrderByClause(List<ShareSort> sorts) {
    if (sorts.isEmpty()) {
      return "";
    }
    return " order by "
        + sorts.stream()
            .map(
                sort ->
                    SORT_PROPERTIES_BY_COLUMN.get(sort.getName())
                        + (sort.getOrder() == SortOrder.DESCENDING ? " desc" : " asc"))
            .collect(Collectors.joining(", "));
  }

  @Override
  public Optional<Share> getShare(String nodeId, String userId) {
    return findByIdOptional(new SharePK(nodeId, userId));
  }

  @Override
  @Transactional
  public Optional<Share> upsertShare(
      String nodeId,
      String targetUserId,
      ACL permissions,
      Boolean direct,
      Boolean createdViaCollaborationLink,
      Optional<Long> expireTimestamp) {
    Optional<Share> existingShare = getShare(nodeId, targetUserId);
    if (existingShare.isPresent()) {
      Share share = existingShare.get();
      if (share.isDirect()) {
        // Converting a direct share to an inherited one is not allowed via upsert.
        return Optional.empty();
      }
      // Converting an inherited share to a direct one (if requested).
      if (direct) {
        share.setDirect(true);
      }
      share.setPermissions(permissions);
      share.setCreatedViaLink(createdViaCollaborationLink);
      expireTimestamp.ifPresent(share::setExpiredAt);
      return Optional.of(updateShare(share));
    }

    Share share =
        new Share(
            nodeId,
            targetUserId,
            permissions,
            System.currentTimeMillis(),
            direct,
            createdViaCollaborationLink,
            null);
    expireTimestamp.ifPresent(share::setExpiredAt);
    persist(share);
    return Optional.of(share);
  }

  @Override
  public void flush() {
    getEntityManager().flush();
  }

  @Override
  @Transactional
  public Share updateShare(Share share) {
    return getEntityManager().merge(share);
  }

  @Override
  @Transactional
  public boolean deleteShare(String nodeId, String targetUserId) {
    Optional<Share> managed = findByIdOptional(new SharePK(nodeId, targetUserId));
    managed.ifPresent(this::delete);
    return managed.isPresent();
  }

  @Override
  @Transactional
  public void deleteSharesBulk(List<String> nodeIds, String targetUserId) {
    // Fetch-then-remove (rather than a bulk "delete ... where ... in" JPQL statement) so entities
    // already managed in the current persistence context are correctly evicted; JPA bulk
    // statements bypass the first-level cache and would otherwise leave stale managed instances
    // behind for the rest of the transaction.
    list(
            "composedPrimaryKey.mNodeId in ?1 and composedPrimaryKey.mTargetUserId = ?2",
            nodeIds,
            targetUserId)
        .forEach(this::delete);
  }

  @Override
  @Transactional
  public void deleteSharesBulk(List<String> nodeIds) {
    list("composedPrimaryKey.mNodeId in ?1", nodeIds).forEach(this::delete);
  }

  @Override
  @Transactional
  public void cascadeUpsertInheritedShares(
      String nodeId, String targetUserId, ACL permissions, Optional<Long> expireTimestamp) {
    flush();
    List<String> affected =
        getEntityManager()
            .createNativeQuery(CASCADE_UPSERT_SQL, String.class)
            .setParameter("root", nodeId)
            .setParameter("userId", targetUserId)
            .setParameter("rights", permissions.encode())
            .setParameter("now", System.currentTimeMillis())
            .setParameter(
                "expire",
                new TypedParameterValue<>(StandardBasicTypes.LONG, expireTimestamp.orElse(null)))
            .getResultList();
    detachShares(affected, targetUserId);
  }

  @Override
  @Transactional
  public void cascadeDeleteInheritedShares(String nodeId, String targetUserId) {
    flush();
    List<String> affected =
        getEntityManager()
            .createNativeQuery(CASCADE_DELETE_SQL, String.class)
            .setParameter("root", nodeId)
            .setParameter("userId", targetUserId)
            .getResultList();
    detachShares(affected, targetUserId);
  }

  private void detachShares(List<String> affectedNodeIds, String targetUserId) {
    if (affectedNodeIds.isEmpty()) {
      return;
    }
    Set<String> affected = new HashSet<>();
    affectedNodeIds.forEach(id -> affected.add(id.trim()));
    Session session = getEntityManager().unwrap(Session.class);
    if (session.getStatistics().getEntityCount() == 0) {
      return;
    }
    session.getManagedEntities(Share.class).stream()
        .filter(
            share ->
                targetUserId.equals(share.getTargetUserId())
                    && affected.contains(share.getNodeId().trim()))
        .forEach(session::detach);
  }

  @Override
  public Optional<ACL> getSharePermissions(String nodeId, String userId) {
    return Optional.ofNullable(getSharePermissions(List.of(nodeId), userId).get(nodeId.trim()));
  }

  @Override
  public Map<String, ACL> getSharePermissions(List<String> nodeIds, String userId) {
    return getEntityManager()
        .createQuery(
            "select s.composedPrimaryKey.mNodeId, s.permissions from Share s "
                + "where s.composedPrimaryKey.mNodeId in :nodeIds "
                + "and s.composedPrimaryKey.mTargetUserId = :userId",
            Object[].class)
        .setParameter("nodeIds", nodeIds)
        .setParameter("userId", userId)
        .getResultList()
        .stream()
        .collect(
            Collectors.toMap(row -> ((String) row[0]).trim(), row -> ACL.decode((Short) row[1])));
  }

  @Override
  public List<Share> getShares(List<String> nodeIds, String targetUserId) {
    return list(
        "composedPrimaryKey.mNodeId in ?1 and composedPrimaryKey.mTargetUserId = ?2",
        nodeIds,
        targetUserId);
  }

  @Override
  public List<Share> getShares(String nodeId, List<String> targetUserIds) {
    if (targetUserIds.isEmpty()) {
      return list("composedPrimaryKey.mNodeId = ?1", nodeId);
    }
    return list(
        "composedPrimaryKey.mNodeId = ?1 and composedPrimaryKey.mTargetUserId in ?2",
        nodeId,
        targetUserIds);
  }

  @Override
  public List<Share> getShares(List<String> nodeIds) {
    // Single batch query (used by the GraphQL DataLoader); grouping per input nodeId, if needed,
    // is left to the caller since the interface declares a flat List<Share> here.
    return list("composedPrimaryKey.mNodeId in ?1", nodeIds);
  }

  @Override
  public List<String> getSharesUsersIds(String nodeId, List<ShareSort> sorts) {
    String query = "composedPrimaryKey.mNodeId = ?1" + buildOrderByClause(sorts);
    return list(query, nodeId).stream().map(Share::getTargetUserId).toList();
  }
}
