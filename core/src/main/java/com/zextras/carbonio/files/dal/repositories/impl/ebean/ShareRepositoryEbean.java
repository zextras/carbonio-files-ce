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
import com.zextras.carbonio.files.dal.dao.ebean.ACL;
import com.zextras.carbonio.files.dal.dao.ebean.Share;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities.ShareSort;
import com.zextras.carbonio.files.dal.repositories.interfaces.ShareRepository;
import io.ebean.Query;
import io.ebean.Transaction;
import io.ebean.annotation.Transactional;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class ShareRepositoryEbean implements ShareRepository {

  private final EbeanDatabaseManager   mDB;
  private       Optional<Cache<Share>> mShareCache;

  @Inject
  public ShareRepositoryEbean(
    EbeanDatabaseManager ebeanDatabaseManager,
    CacheHandler cacheHandler
  ) {
    mDB = ebeanDatabaseManager;
    mShareCache = cacheHandler
      .getCache(Files.Cache.NODE)
      .map(cache -> mShareCache = Optional.of(cache))
      .orElse(Optional.empty());
  }

  private String getShareId(
    String nodeId,
    String userId
  ) {
    return nodeId + "/" + userId;
  }

  /**
   * Directly retrieves from database a {@link Share} given the node and user ids.
   *
   * @param nodeId is a {@link String} representing the id of the node on the requested share.
   * @param userId is a {@link String} representing the id of the user on the requested share.
   *
   * @return an {@link Optional} of {@link Share} if it exists, an {@link Optional#empty()}
   * otherwise.
   */
  private Optional<Share> getRealShare(
    String nodeId,
    String userId
  ) {
    Optional<Share> share = mDB.getEbeanDatabase()
      .find(Share.class)
      .where()
      .eq(Files.Db.Share.NODE_ID, nodeId)
      .eq(Files.Db.Share.SHARE_TARGET_UUID, userId)
      .findOneOrEmpty();
    return share;
  }

  @Transactional
  public Optional<Share> getShare(
    String nodeId,
    String userId
  ) {
    return mShareCache.map(cache -> {
        return Optional.ofNullable(
          cache
            .get(getShareId(nodeId, userId))
            .orElseGet(() -> {
              Optional<Share> dbShare = getRealShare(nodeId, userId);
              dbShare.ifPresent(share -> cache.add(getShareId(nodeId, userId), share));
              return dbShare.orElse(null);
            })
        );
      })
      .orElse(getRealShare(nodeId, userId));
  }

  public Optional<Share> upsertShare(
    String nodeId,
    String targetUserId,
    ACL permissions,
    Boolean direct,
    Optional<Long> expireTimestamp
  ) {
    Optional<Share> targetShare = getShare(nodeId, targetUserId);
    if (targetShare.isPresent()) {
      if (!targetShare.get().isDirect()) {
        //We're converting an inherited share to a direct share
        Share updateShare = targetShare.get();
        if (direct) {
          updateShare.setDirect(true);
        }
        updateShare.setPermissions(permissions);
        expireTimestamp.ifPresent(updateShare::setExpiredAt);
        mShareCache.ifPresent(cache -> {
          cache.add(getShareId(nodeId, targetUserId), updateShare);
        });
        return Optional.of(updateShare(updateShare));
      } else {
        // We're trying to convert a direct to an inherited share
        return Optional.empty();
      }
    } else {
      final Share share = new Share(
        nodeId,
        targetUserId,
        permissions,
        System.currentTimeMillis(),
        direct
      );
      expireTimestamp.ifPresent(share::setExpiredAt);
      mDB.getEbeanDatabase().save(share);
      mShareCache.ifPresent(cache -> {
        cache.add(getShareId(nodeId, targetUserId), share);
      });
      return Optional.of(share);
    }
  }

  public void upsertShareBulk(
    List<String> nodeIds,
    String targetUserId,
    ACL permissions,
    Boolean direct,
    Optional<Long> expireTimestamp
  ) {
    try (Transaction transaction = mDB.getEbeanDatabase().beginTransaction()) {

      // use JDBC batch
      transaction.setBatchMode(true);
      transaction.setBatchSize(50);

      // these go to batch buffer
      nodeIds.forEach(nodeId -> {
        upsertShare(nodeId, targetUserId, permissions, direct, expireTimestamp);
      });
      // flush batch and commit
      transaction.commit();
    }
  }

  public Share updateShare(Share share) {
    mDB.getEbeanDatabase().update(share);
    mShareCache.ifPresent(cache -> {
      cache.add(getShareId(share.getNodeId(), share.getTargetUserId()), share);
    });
    return share;
  }

  public boolean deleteShare(
    String nodeId,
    String targetUserId
  ) {
    return getShare(nodeId, targetUserId).map(share -> {
        boolean deleted = mDB.getEbeanDatabase().delete(share);
        if (deleted) {
          mShareCache.map(cache -> {
            return cache.delete(getShareId(nodeId, targetUserId));
          });
        }
        return deleted;
      })
      .orElse(false);
  }

  public void deleteSharesBulk(
    List<String> nodeIds,
    String targetUserId
  ) {
    try (Transaction transaction = mDB.getEbeanDatabase().beginTransaction()) {

      // use JDBC batch
      transaction.setBatchMode(true);
      transaction.setBatchSize(50);

      // these go to batch buffer
      nodeIds.forEach(nodeId -> {
        deleteShare(nodeId, targetUserId);
      });
      // flush batch and commit
      transaction.commit();
    }
  }

  public void deleteSharesBulk(
    List<String> nodeIds
  ) {
    mDB.getEbeanDatabase()
      .find(Share.class)
      .where()
      .in(Files.Db.Share.NODE_ID, nodeIds)
      .delete();

    // TODO Uniform the delete behaviour with other delete method when we implement unique shareId
    mShareCache.ifPresent(Cache::flushAll);
  }

  public List<Share> getShares(
    List<String> nodeIds,
    String targetUserId
  ) {
    List<Share> shares = mDB.getEbeanDatabase()
      .find(Share.class)
      .where()
      .in(Files.Db.Share.NODE_ID, nodeIds)
      .eq(Files.Db.Share.SHARE_TARGET_UUID, targetUserId)
      .findList();

    mShareCache.ifPresent(cache -> {
      shares.forEach(
        share -> cache.add(getShareId(share.getNodeId(), share.getTargetUserId()), share));
    });
    return shares;
  }

  public List<Share> getShares(
    String nodeId,
    List<String> targetUserIds
  ) {
    Query<Share> query = mDB.getEbeanDatabase()
      .find(Share.class)
      .where()
      .eq(Files.Db.Share.NODE_ID, nodeId)
      .query();

    if (!targetUserIds.isEmpty()) {
      query.where().in(Files.Db.Share.SHARE_TARGET_UUID, targetUserIds);
    }

    List<Share> shares = query.findList();

    mShareCache.ifPresent(cache -> {
      shares.forEach(
        share -> cache.add(getShareId(share.getNodeId(), share.getTargetUserId()), share));
    });
    return shares;
  }

  public List<Share> getShares(List<String> nodeIds) {
    return mDB.getEbeanDatabase()
      .find(Share.class)
      .where()
      .in(Db.Share.NODE_ID, nodeIds)
      .findList();
  }

  public List<String> getSharesUsersIds(
    String nodeId,
    List<ShareSort> sorts
  ) {
    Query<Share> query = mDB.getEbeanDatabase()
      .createQuery(Share.class)
      .where()
      .eq(Files.Db.Share.NODE_ID, nodeId)
      .query();

    sorts.forEach(sort -> sort.getOrderEbeanQuery(query));
    return query.findList()
      .stream()
      .map(Share::getTargetUserId)
      .collect(Collectors.toList());
  }
}
