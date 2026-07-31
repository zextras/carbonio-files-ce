// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities;

import com.zextras.carbonio.files.Constants;
import com.zextras.carbonio.files.dal.dao.ebean.Share;
import io.ebean.Query;
import java.util.Optional;

/**
 * Represents all the applicable sort types in a list of {@link Share}s. Each of them implements the
 * {@link SortingEntityEbean#getOrderEbeanQuery(Query, Optional<String>)} method that returns a
 * query with the related sort applied. These implementations can be useful to concatenate multiple
 * sorts to a single {@link Query}.
 */
public enum ShareSort implements SortingEntityEbean<Share> {
  CREATION_ASC {
    @Override
    public Query<Share> getOrderEbeanQuery(Query<Share> query, Optional<String> collate) {
      return query.order().asc(Constants.Db.Share.CREATED_AT);
    }
  },

  CREATION_DESC {
    @Override
    public Query<Share> getOrderEbeanQuery(Query<Share> query, Optional<String> collate) {
      return query.order().desc(Constants.Db.Share.CREATED_AT);
    }
  },

  /** The order is based on the target user identifier and not on its email or display name. */
  TARGET_USER_ASC {
    @Override
    public Query<Share> getOrderEbeanQuery(Query<Share> query, Optional<String> collate) {
      if (collate.isEmpty()) {
        return query.order().asc(Constants.Db.Share.SHARE_TARGET_UUID);
      }
      return query.order().asc(Constants.Db.Share.SHARE_TARGET_UUID, collate.get());
    }
  },

  /** The order is based on the target user identifier and not on its email or display name. */
  TARGET_USER_DESC {
    @Override
    public Query<Share> getOrderEbeanQuery(Query<Share> query, Optional<String> collate) {
      if (collate.isEmpty()) {
        return query.order().desc(Constants.Db.Share.SHARE_TARGET_UUID);
      }
      return query.order().desc(Constants.Db.Share.SHARE_TARGET_UUID, collate.get());
    }
  },

  /** The order is ascending: this means that first are shown the shares with fewer permissions. */
  SHARE_PERMISSIONS_ASC {
    @Override
    public Query<Share> getOrderEbeanQuery(Query<Share> query, Optional<String> collate) {
      return query.order().asc(Constants.Db.Share.PERMISSIONS);
    }
  },

  /** The order is descending: this means that first are shown the shares with more permissions. */
  SHARE_PERMISSIONS_DESC {
    @Override
    public Query<Share> getOrderEbeanQuery(Query<Share> query, Optional<String> collate) {
      return query.order().desc(Constants.Db.Share.PERMISSIONS);
    }
  },

  EXPIRATION_ASC {
    @Override
    public Query<Share> getOrderEbeanQuery(Query<Share> query, Optional<String> collate) {
      return query.order().asc(Constants.Db.Share.EXPIRED_AT);
    }
  },

  EXPIRATION_DESC {
    @Override
    public Query<Share> getOrderEbeanQuery(Query<Share> query, Optional<String> collate) {
      return query.order().desc(Constants.Db.Share.EXPIRED_AT);
    }
  },
}
