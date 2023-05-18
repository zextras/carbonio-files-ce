// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.cache;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.zextras.carbonio.files.Files;
import com.zextras.carbonio.files.dal.dao.User;
import com.zextras.carbonio.files.dal.dao.ebean.FileVersion;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Initializes and handles all the caches necessary to store in memory objects for a future use.
 * <p>
 * Every cache must be created in the {@link CacheHandler} constructor and they must be stored into
 * the {@link ConcurrentHashMap}.
 * <p>
 * Every cache is created using the {@link CacheHandlerFactory}.
 */
@Singleton
public class CacheHandler {

  private Map<String, Cache>  caches;
  private CacheHandlerFactory cacheHandlerFactory;

  @Inject
  public CacheHandler(CacheHandlerFactory cacheHandlerFactory) {
    caches = new ConcurrentHashMap<>();
    this.cacheHandlerFactory = cacheHandlerFactory;

    /* Creation of the cache that will contain file version elements */
    caches.put(
      Files.Cache.FILE_VERSION,
      this.cacheHandlerFactory.createFileVersionCache(
        Files.Cache.FILE_VERSION,
        Files.Cache.DEFAULT_SIZE,
        Files.Cache.DEFAULT_ITEM_LIFETIME_IN_MILLIS
      )
    );

    /* Creation of the cache that will contain user elements */
    caches.put(
      Files.Cache.USER,
      this.cacheHandlerFactory.createUserCache(
        Files.Cache.USER,
        Files.Cache.DEFAULT_SIZE,
        Files.Cache.DEFAULT_ITEM_LIFETIME_IN_MILLIS
      )
    );
  }

  public Cache<User> getUserCache() {
    return caches.get(Files.Cache.USER);
  }

  public Cache<FileVersion> getFileVersionCache() {
    return caches.get(Files.Cache.FILE_VERSION);
  }
}
