// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.cache;

import com.google.inject.assistedinject.Assisted;
import com.zextras.carbonio.files.dal.dao.ebean.FileVersion;
import com.zextras.carbonio.files.dal.dao.ebean.Node;
import com.zextras.carbonio.files.dal.dao.ebean.Share;

public interface CacheHandlerFactory {

  LocalCacheAdapter<Node> createNodeCache(
    String cacheName,
    @Assisted("defaultCacheSize") long defaultCacheSize,
    @Assisted("defaultItemLifetimeInMillis") long defaultItemLifetimeInMillis
  );

  LocalCacheAdapter<FileVersion> createFileVersionCache(
    String cacheName,
    @Assisted("defaultCacheSize") long defaultCacheSize,
    @Assisted("defaultItemLifetimeInMillis") long defaultItemLifetimeInMillis
  );

  LocalCacheAdapter<Share> createShareCache(
    String cacheName,
    @Assisted("defaultCacheSize") long defaultCacheSize,
    @Assisted("defaultItemLifetimeInMillis") long defaultItemLifetimeInMillis
  );
}
