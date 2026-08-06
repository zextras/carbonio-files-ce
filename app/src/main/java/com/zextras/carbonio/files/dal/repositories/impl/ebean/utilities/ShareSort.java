// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities;

import com.zextras.carbonio.files.Constants;
import com.zextras.carbonio.files.dal.dao.ebean.Share;

/** Represents all the applicable sort types in a list of {@link Share}s. */
public enum ShareSort implements GenericSort {
  CREATION_ASC {
    @Override
    public String getName() {
      return Constants.Db.Share.CREATED_AT;
    }

    @Override
    public SortOrder getOrder() {
      return SortOrder.ASCENDING;
    }
  },

  CREATION_DESC {
    @Override
    public String getName() {
      return Constants.Db.Share.CREATED_AT;
    }

    @Override
    public SortOrder getOrder() {
      return SortOrder.DESCENDING;
    }
  },

  /** The order is based on the target user identifier and not on its email or display name. */
  TARGET_USER_ASC {
    @Override
    public String getName() {
      return Constants.Db.Share.SHARE_TARGET_UUID;
    }

    @Override
    public SortOrder getOrder() {
      return SortOrder.ASCENDING;
    }
  },

  /** The order is based on the target user identifier and not on its email or display name. */
  TARGET_USER_DESC {
    @Override
    public String getName() {
      return Constants.Db.Share.SHARE_TARGET_UUID;
    }

    @Override
    public SortOrder getOrder() {
      return SortOrder.DESCENDING;
    }
  },

  /** The order is ascending: this means that first are shown the shares with fewer permissions. */
  SHARE_PERMISSIONS_ASC {
    @Override
    public String getName() {
      return Constants.Db.Share.PERMISSIONS;
    }

    @Override
    public SortOrder getOrder() {
      return SortOrder.ASCENDING;
    }
  },

  /** The order is descending: this means that first are shown the shares with more permissions. */
  SHARE_PERMISSIONS_DESC {
    @Override
    public String getName() {
      return Constants.Db.Share.PERMISSIONS;
    }

    @Override
    public SortOrder getOrder() {
      return SortOrder.DESCENDING;
    }
  },

  EXPIRATION_ASC {
    @Override
    public String getName() {
      return Constants.Db.Share.EXPIRED_AT;
    }

    @Override
    public SortOrder getOrder() {
      return SortOrder.ASCENDING;
    }
  },

  EXPIRATION_DESC {
    @Override
    public String getName() {
      return Constants.Db.Share.EXPIRED_AT;
    }

    @Override
    public SortOrder getOrder() {
      return SortOrder.DESCENDING;
    }
  }
}
