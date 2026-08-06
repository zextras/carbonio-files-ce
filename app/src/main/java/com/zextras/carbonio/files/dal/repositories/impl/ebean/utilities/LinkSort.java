// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities;

import com.zextras.carbonio.files.Constants.Db;
import com.zextras.carbonio.files.dal.dao.ebean.Link;

/** Represents all applicable sort types of a list of {@link Link}s. */
public enum LinkSort implements GenericSort {
  CREATED_AT_ASC {
    @Override
    public String getName() {
      return Db.Link.CREATED_AT;
    }

    @Override
    public SortOrder getOrder() {
      return SortOrder.ASCENDING;
    }
  },

  CREATED_AT_DESC {
    @Override
    public String getName() {
      return Db.Link.CREATED_AT;
    }

    @Override
    public SortOrder getOrder() {
      return SortOrder.DESCENDING;
    }
  }
}
