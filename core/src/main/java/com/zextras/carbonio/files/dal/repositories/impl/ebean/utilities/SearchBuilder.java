// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities;

import com.zextras.carbonio.files.Files;
import com.zextras.carbonio.files.Files.Db;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.NodeType;
import io.ebean.Database;
import io.ebean.Query;
import java.util.List;

/**
 * This class is used to create and add parameters to a search query with ebean. All methods must
 * always return the SearchBuilder itself to easy composition of multiple params if necessary.
 */
public class SearchBuilder {

  Query<Node> query;
  Database db;
  String userId;

  public SearchBuilder(Database db, String userId) {
    this.db = db;
    this.userId = userId;
    this.query =
        this.db
            .find(Node.class)
            .fetchLazy("mShares")
            .fetchLazy("mCustomAttributes")
            .where()
            .or()
            .eq("mShares.mComposedPrimaryKey.mTargetUserId", userId)
            .eq("mOwnerId", userId)
            .endOr()
            .not()
            .eq("mNodeCategory", 0)
            .endNot()
            .query();
  }

  /**
   * This method is used to search for keywords, every keyword must be present in the name or
   * description of the node. Every keyword <strong>must</strong> be present in the node for it to
   * be returned, every <code>where</code> clause is added in AND to the final query generated by
   * ebean.
   *
   * @param keywords is a {@link List} of keyword to search.
   * @return the {@link SearchBuilder} for adding other options if necessary.
   */
  public SearchBuilder setKeywords(List<String> keywords) {
    if (keywords.size() > 0) {
      keywords.forEach(
          keyword -> {
            this.query
                .where()
                .or()
                .contains("LOWER(mName)", keyword.toLowerCase())
                .contains("LOWER(mDescription)", keyword.toLowerCase())
                .endOr();
          });
    }
    return this;
  }

  /**
   * This method adds the flag condition to the search.
   *
   * @param flagged is a {@link Boolean } used to search a node flagged or not.
   * @return the {@link SearchBuilder} for adding other options if necessary.
   */
  public SearchBuilder setFlagged(Boolean flagged) {
    if (flagged) {
      this.query
          .where()
          .eq("mCustomAttributes.mFlag", true)
          .eq("mCustomAttributes.mCompositeId.mUserId", userId);
    } else {
      this.query
          .where()
          .or()
          .and()
          .eq("mCustomAttributes.mFlag", false)
          .eq("mCustomAttributes.mCompositeId.mUserId", userId)
          .endAnd()
          .and()
          .eq("mCustomAttributes.mFlag", null)
          .eq("mCustomAttributes.mCompositeId.mUserId", null)
          .endAnd()
          .endOr();
    }
    return this;
  }

  /**
   * This method is used to limit the search only to a specific folder. If the cascade parameter is
   * true it will search on the folder and the whole subtree, otherwise it will search only the
   * direct children of the folder.
   *
   * @param folderId is a {@link String} representing the id of the folder.
   * @param cascade is a {@link Boolean} used to limit the search only on the folder itself or on
   *     the whole subtree.
   * @return the {@link SearchBuilder} for adding other options if necessary.
   */
  public SearchBuilder setFolderId(String folderId, Boolean cascade) {
    if (cascade) {
      this.query.where().contains(Db.Node.ANCESTOR_IDS, folderId);
    } else {
      this.query.where().eq(Db.Node.PARENT_ID, folderId);
    }
    return this;
  }

  /**
   * This method is used to manage the <strong>shared with me</strong> condition on a search. If the
   * flag is true it will search only nodes for which there's a search where the user is the target.
   * If the flag is false it will search only nodes "not shared with me" meaning for which I'm the
   * owner.
   *
   * @param userId is a {@link String} representing the id of the user for which to search.
   * @param sharedWithMe is a {@link Boolean} representing the flag used for choosing how to filter
   *     the nodes.
   * @return the {@link SearchBuilder} for adding other options if necessary.
   */
  public SearchBuilder setSharedWithMe(String userId, Boolean sharedWithMe) {
    if (sharedWithMe) {
      this.query.where().eq("mShares.mComposedPrimaryKey.mTargetUserId", userId);
    } else {
      setOwner(userId);
    }
    return this;
  }

  /**
   * This method is used to manage the <strong>shared with me</strong> condition on a search. This
   * condition always searches on nodes for which I'm the owner. If the flag is true it will search
   * only nodes for which there's also a share with someone else. If the flag is false it will
   * search only nodes not shared with anyone.
   *
   * @param sharedByMe is a {@link Boolean} representing the flag used for choosing how to filter
   *     the nodes.
   * @return the {@link SearchBuilder} for adding other options if necessary.
   */
  public SearchBuilder setSharedByMe(Boolean sharedByMe) {
    setOwner(userId);
    if (sharedByMe) {
      this.query.where().isNotNull("mShares.mPermissions");
    } else {
      this.query.where().isNull("mShares.mPermissions");
    }
    return this;
  }

  public SearchBuilder setDirectShare(Boolean directShare) {
    this.query.where().eq("mShares.mDirect", directShare);

    return this;
  }

  /**
   * This method is used to set the skip on the query for starting from a specific offset.
   *
   * @param skip is an {@link Integer} representing how many nodes to skip.
   * @return the {@link SearchBuilder} for adding other options if necessary.
   */
  public SearchBuilder setSkip(Integer skip) {
    this.query.setFirstRow(skip);
    return this;
  }

  /**
   * This method is used to set the limit of how many nodes to return.
   *
   * @param limit is an {@link Integer} of the number of nodes to return.
   * @return the {@link SearchBuilder} for adding other options if necessary.
   */
  public SearchBuilder setLimit(Integer limit) {
    this.query.setMaxRows(limit);
    return this;
  }

  /**
   * This method is used to only return the nodes of a certain owner.
   *
   * @param ownerId is a {@link String} representing the id of the owner.
   * @return the {@link SearchBuilder} for adding other options if necessary.
   */
  public SearchBuilder setOwner(String ownerId) {
    this.query.where().eq("owner_id", ownerId).query();
    return this;
  }

  /**
   * This method is used to add a sort to the query.
   *
   * @param order is a {@link NodeSort} to add.
   * @return the {@link SearchBuilder} for adding other options if necessary.
   */
  public SearchBuilder setSort(NodeSort order) {
    if (order.equals(NodeSort.TYPE_ASC)) {
      this.query.orderBy().asc("mNodeCategory");
    } else {
      order.getOrderEbeanQuery(this.query);
    }

    return this;
  }

  public SearchBuilder setKeyset(String keyset) {
    this.query.where().and().raw(keyset).endAnd();
    return this;
  }

  /**
   * Allows to set the {@link Files.Db.Node#TYPE} attribute in the <code>where</code>clause of the
   * query.
   *
   * @param nodeType is a {@link NodeType} representing the node type that needs to be searched.
   * @return the {@link SearchBuilder} for adding other options if necessary.
   */
  public SearchBuilder setNodeType(NodeType nodeType) {
    this.query.where().eq(Db.Node.TYPE, nodeType);
    return this;
  }

  /**
   * This method is used at the end of the build to start the search and return the found nodes.
   *
   * @return the {@link Query} on where to invoke the find functions.
   */
  public Query<Node> build() {
    return this.query;
  }
}
