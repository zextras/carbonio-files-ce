// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities;

import com.zextras.carbonio.files.Files;
import com.zextras.carbonio.files.Files.Db;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import io.ebean.Query;
import javax.swing.*;

/**
 * Represents all applicable sort types of a list of {@link Node}s. Each of them implements the
 * {@link SortingEntityEbean#getOrderEbeanQuery(Query)} method that returns a query with the related
 * sort applied. These implementations can be useful to concatenate multiple sorts to a single
 * {@link Query}.
 */
public enum NodeSort implements SortingEntityEbean<Node>, GenericSort {
  LAST_EDITOR_ASC {
    @Override
    public String getName() {
      return Db.Node.EDITOR_ID;
    }

    @Override
    public SortOrder getOrder() {
      return SortOrder.ASCENDING;
    }

    public Query<Node> getOrderEbeanQuery(Query<Node> query) {
      return query.order().asc("t0." + Files.Db.Node.EDITOR_ID);
    }
  },

  LAST_EDITOR_DESC {
    @Override
    public String getName() {
      return Db.Node.EDITOR_ID;
    }

    @Override
    public SortOrder getOrder() {
      return SortOrder.DESCENDING;
    }

    public Query<Node> getOrderEbeanQuery(Query<Node> query) {
      return query.order().desc("t0." + Files.Db.Node.EDITOR_ID);
    }
  },

  NAME_ASC {
    @Override
    public String getName() {
      return Db.Node.NAME;
    }

    @Override
    public SortOrder getOrder() {
      return SortOrder.ASCENDING;
    }

    @Override
    public Query<Node> getOrderEbeanQuery(Query<Node> query) {
      return query.order().asc("t0." + Files.Db.Node.NAME);
    }
  },

  NAME_DESC {
    @Override
    public String getName() {
      return Db.Node.NAME;
    }

    @Override
    public SortOrder getOrder() {
      return SortOrder.DESCENDING;
    }

    public Query<Node> getOrderEbeanQuery(Query<Node> query) {
      return query.order().desc("t0." + Files.Db.Node.NAME);
    }
  },

  OWNER_ASC {
    @Override
    public String getName() {
      return Db.Node.OWNER_ID;
    }

    @Override
    public SortOrder getOrder() {
      return SortOrder.ASCENDING;
    }

    public Query<Node> getOrderEbeanQuery(Query<Node> query) {
      return query.order().asc("t0." + Files.Db.Node.OWNER_ID);
    }
  },

  OWNER_DESC {
    @Override
    public String getName() {
      return Db.Node.OWNER_ID;
    }

    @Override
    public SortOrder getOrder() {
      return SortOrder.DESCENDING;
    }

    public Query<Node> getOrderEbeanQuery(Query<Node> query) {
      return query.order().desc("t0." + Files.Db.Node.OWNER_ID);
    }
  },

  /** The order is: Root, Folder, File */
  TYPE_ASC {
    @Override
    public String getName() {
      return Db.Node.CATEGORY;
    }

    @Override
    public SortOrder getOrder() {
      return SortOrder.ASCENDING;
    }

    public Query<Node> getOrderEbeanQuery(Query<Node> query) {
      return query.order().asc("t0." + Files.Db.Node.CATEGORY);
    }
  },

  /** The order is: File, Folder, Root */
  TYPE_DESC {
    @Override
    public String getName() {
      return Db.Node.CATEGORY;
    }

    @Override
    public SortOrder getOrder() {
      return SortOrder.DESCENDING;
    }

    public Query<Node> getOrderEbeanQuery(Query<Node> query) {
      return query.order().desc("t0." + Files.Db.Node.CATEGORY);
    }
  },

  UPDATED_AT_ASC {
    @Override
    public String getName() {
      return Db.Node.UPDATED_AT;
    }

    @Override
    public SortOrder getOrder() {
      return SortOrder.ASCENDING;
    }

    public Query<Node> getOrderEbeanQuery(Query<Node> query) {
      return query.order().asc("t0." + Files.Db.Node.UPDATED_AT);
    }
  },

  UPDATED_AT_DESC {
    @Override
    public String getName() {
      return Db.Node.UPDATED_AT;
    }

    @Override
    public SortOrder getOrder() {
      return SortOrder.DESCENDING;
    }

    public Query<Node> getOrderEbeanQuery(Query<Node> query) {
      return query.order().desc("t0." + Files.Db.Node.UPDATED_AT);
    }
  },

  CREATED_AT_ASC {
    @Override
    public String getName() {
      return Db.Node.CREATED_AT;
    }

    @Override
    public SortOrder getOrder() {
      return SortOrder.ASCENDING;
    }

    public Query<Node> getOrderEbeanQuery(Query<Node> query) {
      return query.order().asc("t0." + Files.Db.Node.CREATED_AT);
    }
  },

  CREATED_AT_DESC {
    @Override
    public String getName() {
      return Db.Node.CREATED_AT;
    }

    @Override
    public SortOrder getOrder() {
      return SortOrder.DESCENDING;
    }

    public Query<Node> getOrderEbeanQuery(Query<Node> query) {
      return query.order().desc("t0." + Files.Db.Node.CREATED_AT);
    }
  },

  SIZE_ASC {
    @Override
    public String getName() {
      return Db.Node.SIZE;
    }

    @Override
    public SortOrder getOrder() {
      return SortOrder.ASCENDING;
    }

    public Query<Node> getOrderEbeanQuery(Query<Node> query) {
      return query.order().asc("t0." + Files.Db.Node.SIZE);
    }
  },

  SIZE_DESC {
    @Override
    public String getName() {
      return Db.Node.SIZE;
    }

    @Override
    public SortOrder getOrder() {
      return SortOrder.DESCENDING;
    }

    public Query<Node> getOrderEbeanQuery(Query<Node> query) {
      return query.order().desc("t0." + Files.Db.Node.SIZE);
    }
  }
}
