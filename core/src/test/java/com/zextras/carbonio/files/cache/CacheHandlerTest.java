// SPDX-FileCopyrightText: 2023 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.cache;

import com.zextras.carbonio.files.Files;
import com.zextras.carbonio.files.dal.dao.User;
import com.zextras.carbonio.files.dal.dao.ebean.FileVersion;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class CacheHandlerTest {

  private CacheHandlerFactory cacheHandlerFactory;
  private LocalCacheAdapter<FileVersion> fileVersionCache;
  private LocalCacheAdapter<User> userCache;

  @BeforeEach
  void setUp() {
    fileVersionCache = Mockito.mock(LocalCacheAdapter.class);
    userCache = Mockito.mock(LocalCacheAdapter.class);
    cacheHandlerFactory = Mockito.mock(CacheHandlerFactory.class);

    Mockito.when(cacheHandlerFactory.createFileVersionCache(
      Files.Cache.FILE_VERSION,
      Files.Cache.DEFAULT_SIZE,
      Files.Cache.DEFAULT_ITEM_LIFETIME_IN_MILLIS
    )).thenReturn(fileVersionCache);

    Mockito.when(cacheHandlerFactory.createUserCache(
      Files.Cache.USER,
      Files.Cache.DEFAULT_SIZE,
      Files.Cache.DEFAULT_ITEM_LIFETIME_IN_MILLIS
    )).thenReturn(userCache);
  }

  @Test
  void givenACacheHandlerTheGetFileVersionCacheShouldReturnTheFileVersionCache() {
    // Given
    CacheHandler cacheHandler = new CacheHandler(cacheHandlerFactory);

    // When
    Cache<FileVersion> fileVersionCache = cacheHandler.getFileVersionCache();

    // Then
    Assertions.assertThat(fileVersionCache.size()).isZero();
  }

  @Test
  void givenACacheHandlerTheGetUserCacheShouldReturnTheUserCache() {
    // Given
    CacheHandler cacheHandler = new CacheHandler(cacheHandlerFactory);

    // When
    Cache<User> userCache = cacheHandler.getUserCache();

    // Then
    Assertions.assertThat(userCache.size()).isZero();
  }
}
