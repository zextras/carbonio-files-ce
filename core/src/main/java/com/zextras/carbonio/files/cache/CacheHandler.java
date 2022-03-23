// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.cache;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.zextras.carbonio.files.Files;
import java.util.Map;
import java.util.Optional;
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

    /* Creation of the cache that will contain node elements */
    caches.put(
      Files.Cache.NODE,
      this.cacheHandlerFactory.createNodeCache(
        Files.Cache.NODE,
        Files.Cache.DEFAULT_SIZE,
        Files.Cache.DEFAULT_ITEM_LIFETIME_IN_MILLISEC
      )
    );

    /* Creation of the cache that will contain file version elements */
    caches.put(
      Files.Cache.FILE_VERSION,
      this.cacheHandlerFactory.createFileVersionCache(
        Files.Cache.FILE_VERSION,
        Files.Cache.DEFAULT_SIZE,
        Files.Cache.DEFAULT_ITEM_LIFETIME_IN_MILLISEC
      )
    );

    /* Creation of the cache that will contain share elements */
    caches.put(
      Files.Cache.SHARE,
      this.cacheHandlerFactory.createShareCache(
        Files.Cache.SHARE,
        Files.Cache.DEFAULT_SIZE,
        Files.Cache.DEFAULT_ITEM_LIFETIME_IN_MILLISEC
      )
    );
  }

  public Optional<Cache> getCache(String name) {
    return (caches.containsKey(name))
      ? Optional.of(caches.get(name))
      : Optional.empty();
  }
}
