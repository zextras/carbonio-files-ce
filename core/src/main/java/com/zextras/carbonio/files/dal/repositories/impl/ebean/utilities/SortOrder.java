// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities;

public enum SortOrder implements  GenericSortOrder{
  ASCENDING{
    @Override
    public String getSymbol() {
      return ">";
    }
  },

  DESCENDING{
    @Override
    public String getSymbol() {
      return "<";
    }
  },

  EQUAL{
    @Override
    public String getSymbol() {
      return "=";
    }
  }
}
