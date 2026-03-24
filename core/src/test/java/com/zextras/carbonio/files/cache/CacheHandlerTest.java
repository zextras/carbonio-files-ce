// SPDX-FileCopyrightText: 2023 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.cache;

import com.zextras.carbonio.files.Constants;
import com.zextras.carbonio.files.dal.dao.ebean.FileVersion;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class CacheHandlerTest {

  private CacheHandlerFactory cacheHandlerFactory;
  private LocalCacheAdapter<FileVersion> fileVersionCache;

  @BeforeEach
  void setUp() {
    fileVersionCache = Mockito.mock(LocalCacheAdapter.class);
    cacheHandlerFactory = Mockito.mock(CacheHandlerFactory.class);

    Mockito.when(cacheHandlerFactory.createFileVersionCache(
      Constants.Cache.FILE_VERSION,
      Constants.Cache.DEFAULT_SIZE,
      Constants.Cache.DEFAULT_ITEM_LIFETIME_IN_MILLIS
    )).thenReturn(fileVersionCache);
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
}
