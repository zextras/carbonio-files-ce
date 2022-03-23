// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities;

import com.zextras.carbonio.files.Files;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import io.ebean.Query;

/**
 * Represents all applicable sort types of a list of {@link Node}s. Each of them implements the
 * {@link SortingEntityEbean#getOrderEbeanQuery(Query)} method that returns a query with the related
 * sort applied. These implementations can be useful to concatenate multiple sorts to a single
 * {@link Query}.
 */
public enum NodeSort implements SortingEntityEbean<Node> {
  LAST_EDITOR_ASC {
    public Query<Node> getOrderEbeanQuery(Query<Node> query) {
      return query.order().asc(Files.Db.Node.EDITOR_ID);
    }
  },

  LAST_EDITOR_DESC {
    public Query<Node> getOrderEbeanQuery(Query<Node> query) {
      return query.order().desc(Files.Db.Node.EDITOR_ID);
    }
  },

  NAME_ASC {
    @Override
    public Query<Node> getOrderEbeanQuery(Query<Node> query) {
      return query.orderBy("LOWER(" + Files.Db.Node.NAME + ") ASC");
    }
  },

  NAME_DESC {
    public Query<Node> getOrderEbeanQuery(Query<Node> query) {
      return query.orderBy("LOWER(" + Files.Db.Node.NAME + ") DESC");
    }
  },

  OWNER_ASC {
    public Query<Node> getOrderEbeanQuery(Query<Node> query) {
      return query.order().asc(Files.Db.Node.OWNER_ID);
    }
  },

  OWNER_DESC {
    public Query<Node> getOrderEbeanQuery(Query<Node> query) {
      return query.order().desc(Files.Db.Node.OWNER_ID);
    }
  },

  /**
   * The order is: Root, Folder, File
   */
  TYPE_ASC {
    public Query<Node> getOrderEbeanQuery(Query<Node> query) {
      return query.order().asc(Files.Db.Node.CATEGORY);
    }
  },

  /**
   * The order is: File, Folder, Root
   */
  TYPE_DESC {
    public Query<Node> getOrderEbeanQuery(Query<Node> query) {
      return query.order().desc(Files.Db.Node.CATEGORY);
    }
  },

  UPDATED_AT_ASC {
    public Query<Node> getOrderEbeanQuery(Query<Node> query) {
      return query.order().asc(Files.Db.Node.UPDATED_AT);
    }
  },

  UPDATED_AT_DESC {
    public Query<Node> getOrderEbeanQuery(Query<Node> query) {
      return query.order().desc(Files.Db.Node.UPDATED_AT);
    }
  },

  CREATED_AT_ASC {
    public Query<Node> getOrderEbeanQuery(Query<Node> query) {
      return query.order().asc(Files.Db.Node.CREATED_AT);
    }
  },

  CREATED_AT_DESC {
    public Query<Node> getOrderEbeanQuery(Query<Node> query) {
      return query.order().desc(Files.Db.Node.CREATED_AT);
    }
  },

  SIZE_ASC {
    public Query<Node> getOrderEbeanQuery(Query<Node> query) {
      return query.order().asc(Files.Db.Node.SIZE);
    }
  },

  SIZE_DESC {
    public Query<Node> getOrderEbeanQuery(Query<Node> query) {
      return query.order().desc(Files.Db.Node.SIZE);
    }
  }
}
