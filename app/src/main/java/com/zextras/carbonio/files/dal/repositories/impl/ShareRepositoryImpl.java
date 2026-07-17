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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

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
  @Transactional
  public void upsertShareBulk(
      List<String> nodeIds,
      String targetUserId,
      ACL permissions,
      Boolean direct,
      Boolean createdViaCollaborationLink,
      Optional<Long> expireTimestamp) {
    nodeIds.forEach(
        nodeId ->
            upsertShare(
                nodeId, targetUserId, permissions, direct, createdViaCollaborationLink,
                expireTimestamp));
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
    list("composedPrimaryKey.mNodeId in ?1 and composedPrimaryKey.mTargetUserId = ?2", nodeIds, targetUserId)
        .forEach(this::delete);
  }

  @Override
  @Transactional
  public void deleteSharesBulk(List<String> nodeIds) {
    list("composedPrimaryKey.mNodeId in ?1", nodeIds).forEach(this::delete);
  }

  @Override
  public List<Share> getShares(List<String> nodeIds, String targetUserId) {
    return list("composedPrimaryKey.mNodeId in ?1 and composedPrimaryKey.mTargetUserId = ?2", nodeIds, targetUserId);
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
