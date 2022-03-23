// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities;

import com.zextras.carbonio.files.Files.Db;
import com.zextras.carbonio.files.dal.dao.ebean.Link;
import io.ebean.Query;

/**
 * Represents all applicable sort types of a list of {@link Link}s. Each of them implements the
 * {@link SortingEntityEbean#getOrderEbeanQuery(Query)} method that returns a query with the related
 * sort applied. These implementations can be useful to concatenate multiple sorts to a single
 * {@link Query}.
 */
public enum LinkSort implements SortingEntityEbean<Link> {
  CREATED_AT_ASC {
    @Override
    public Query<Link> getOrderEbeanQuery(Query<Link> query) {
      return query.order().asc(Db.Link.CREATED_AT);
    }
  },

  CREATED_AT_DESC {
    @Override
    public Query<Link> getOrderEbeanQuery(Query<Link> query) {
      return query.order().desc(Db.Link.CREATED_AT);
    }
  };
}
