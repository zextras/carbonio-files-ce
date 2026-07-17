// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.cache;

import com.zextras.carbonio.files.dal.dao.ebean.FileVersion;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Clock;

/**
 * Builds {@link Cache} instances for {@link CacheHandler}.
 *
 * <p>The legacy Guice version used a {@code FactoryModuleBuilder}-generated implementation of an
 * {@code @Assisted}-annotated interface; CDI has no equivalent auto-generated assisted-inject
 * factory, so this is a plain concrete bean with a factory method instead.
 */
@ApplicationScoped
public class CacheHandlerFactory {

  public LocalCacheAdapter<FileVersion> createFileVersionCache(
    String cacheName,
    long defaultCacheSize,
    long defaultItemLifetimeInMillis
  ) {
    return new LocalCacheAdapter<>(
      cacheName,
      defaultCacheSize,
      defaultItemLifetimeInMillis,
      Clock.systemUTC()
    );
  }
}
