// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities;

import com.zextras.carbonio.files.Constants;
import com.zextras.carbonio.files.dal.dao.ebean.FileVersion;

/**
 * Represents all the applicable sort types in a list of {@link FileVersion}s.
 */
public enum FileVersionSort implements GenericSort {
  VERSION_ASC {
    @Override
    public String getName() {
      return Constants.Db.FileVersion.VERSION;
    }

    @Override
    public SortOrder getOrder() {
      return SortOrder.ASCENDING;
    }
  },

  VERSION_DESC {
    @Override
    public String getName() {
      return Constants.Db.FileVersion.VERSION;
    }

    @Override
    public SortOrder getOrder() {
      return SortOrder.DESCENDING;
    }
  }
}
