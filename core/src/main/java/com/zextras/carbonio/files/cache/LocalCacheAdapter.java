// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * {@inheritDoc}
 * <p>
 * This implementation stores all the elements of <code>T</code> type locally in RAM.
 * <p>
 * This is an adapter for the <a href="https://github.com/ben-manes/caffeine">Caffeine cache</a>.
 */
public class LocalCacheAdapter<T> implements Cache<T> {

  private final String                                              cacheName;
  private final long                                                defaultCacheSize;
  private final long                                                defaultItemLifetimeInMillis;
  private       com.github.benmanes.caffeine.cache.Cache<String, T> cache;

  @Inject
  public LocalCacheAdapter(
    @Assisted String cacheName,
    @Assisted("defaultCacheSize") long defaultCacheSize,
    @Assisted("defaultItemLifetimeInMillis") long defaultItemLifetimeInMillis
  ) {
    this.cacheName = cacheName;
    this.defaultCacheSize = defaultCacheSize;
    this.defaultItemLifetimeInMillis = defaultItemLifetimeInMillis;

    /*
     * A functional interface of Ticker is passed to the method ticker():
     * The implementation returns the current timestamp of the System.currentTimeMillis() converted in nanoseconds.
     */
    cache = Caffeine
      .newBuilder()
      .maximumSize(defaultCacheSize)
      .expireAfterWrite(Duration.ofMillis(defaultItemLifetimeInMillis))
      .ticker(() -> System.currentTimeMillis() * 1000000)
      .build();
  }

  @Override
  public String getName() {
    return cacheName;
  }

  @Override
  public long getDefaultItemLifetimeInMillis() {
    return defaultItemLifetimeInMillis;
  }

  @Override
  public long getDefaultCacheSize() {
    return defaultCacheSize;
  }

  @Override
  public long size() {
    /*
     * The cleanUp is necessary to perform any pending maintenance operations and
     * having the correct size of the cache
     */
    cache.cleanUp();
    return cache.estimatedSize();
  }

  @Override
  public void add(
    String key,
    T value
  ) {
    cache.put(key, value);
  }

  @Override
  public void addAll(Map<String, T> elements) {
    elements.forEach(this::add);
  }

  @Override
  public Optional<T> get(String key) {
    return Optional.ofNullable(cache.getIfPresent(key));
  }

  @Override
  public Map<String, T> getAll(Collection<String> keys) {
    HashMap<String, T> result = new HashMap<>();
    keys.forEach(key -> get(key).ifPresent(element -> result.put(key, element)));
    return result;
  }

  @Override
  public boolean isPresent(String key) {
    return get(key).isPresent();
  }

  /**
   * {@inheritDoc}
   * <p>
   * This implementation adds the same element again in the cache if it already exists otherwise it
   * does nothing.
   *
   * @param key of the related element
   */
  @Override
  public void resetLifetime(String key) {
    get(key).ifPresent(ele -> add(key, ele));
  }

  @Override
  public boolean delete(String key) {
    if (isPresent(key)) {
      cache.cleanUp();
      cache.invalidate(key);
      return true;
    }
    return false;
  }

  @Override
  public void flushAll() {
    cache.cleanUp();
    cache.invalidateAll();
  }
}
