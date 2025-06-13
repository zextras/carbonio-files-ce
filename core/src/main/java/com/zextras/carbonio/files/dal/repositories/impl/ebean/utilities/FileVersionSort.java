// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities;

import com.zextras.carbonio.files.Constants;
import com.zextras.carbonio.files.dal.dao.ebean.FileVersion;
import io.ebean.Query;

import java.util.Optional;

/**
 * Represents all the applicable sort types in a list of {@link FileVersion}s. Each of them implements the
 * {@link SortingEntityEbean#getOrderEbeanQuery(Query, Optional<String>)} method that returns a query with the related
 * sort applied. These implementations can be useful to concatenate multiple sorts to a single
 * {@link Query}.
 */
public enum FileVersionSort implements SortingEntityEbean<FileVersion> {
  VERSION_ASC {
    @Override
    public Query<FileVersion> getOrderEbeanQuery(Query<FileVersion> query, Optional<String> collate) {
      return query.orderBy().asc(Constants.Db.FileVersion.VERSION);
    }
  },

  VERSION_DESC {
    @Override
    public Query<FileVersion> getOrderEbeanQuery(Query<FileVersion> query, Optional<String> collate) {
      return query.orderBy().desc(Constants.Db.FileVersion.VERSION);
    }
  },

}