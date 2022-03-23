// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.cache;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * Represents a simple parameterized cache to save objects for a specific time period and to
 * retrieve them if their lifetime is not expired.
 * <p>
 * In order to use this interface is useful to create a private object that contains:
 * <ul>
 *   <li>the element that must be memorized</li>
 *   <li>
 *     the time in milliseconds when the element is memorized so the cache can verify if the element is still valid
 *   </li>
 * </ul>
 *
 * @param <T> type of object to memorize
 */
public interface Cache<T> {

  /**
   * Gets the name of the current cache instance.
   *
   * @return a {@link String} containing the name of the current cache instance
   */
  String getName();

  /**
   * Gets the default number of element that the cache can contain.
   *
   * @return a {@link Long} containing the default cache size
   */
  long getDefaultCacheSize();

  /**
   * Gets the default lifetime applied to an element when it is added without a specific lifetime.
   * <p>
   * The default lifetime must be in milliseconds and must be specified during the creation of the
   * cache.
   *
   * @return a {@link Long} containing the default lifetime in milliseconds
   */
  long getDefaultItemLifetimeInMillis();

  /**
   * Gets the number of valid elements in the cache.
   *
   * @return a {@link Long} containing the number of the valid elements
   */
  long size();

  /**
   * Inserts a new element in the cache and applies the default lifetime.
   *
   * @param key of the element to insert
   * @param value of the <code>T</code> element that need to be inserted
   */
  void add(
    String key,
    T value
  );

  /**
   * Inserts a list of elements in the cache and applies the default lifetime.
   *
   * @param elements that needs to be inserted
   */
  void addAll(Map<String, T> elements);

  /**
   * Gets an element specified by its key if exists.
   *
   * @param key of the related element to retrieve
   *
   * @return an {@link Optional} containing the element requested if exists, otherwise, if it is not
   * present or its lifetime is expired, return an {@link Optional#empty()}
   */
  Optional<T> get(String key);

  /**
   * Gets a {@link Map} of {@link T}elements. Every element meets three requirements:
   * <ul>
   *   <li>It exists in the cache</li>
   *   <li>It is not expired</li>
   *   <li>Its key is contained in the {@link Collection<String>} specified in input</li>
   * </ul>
   *
   * @param keys of all the elements that must return
   *
   * @return a {@link Map} that contains all the requested <strong>valid</strong> {@link T} elements
   */
  Map<String, T> getAll(Collection<String> keys);

  /**
   * Checks if the cache has the element associated with the specified key.
   *
   * @param key of the related element to check
   *
   * @return true if the related element is present and valid, false otherwise
   */
  boolean isPresent(String key);

  /**
   * Resets the lifetime element.
   *
   * @param key of the related element
   */
  void resetLifetime(String key);

  /**
   * Deletes an element if exists and returns true otherwise return false.
   *
   * @param key of the element to delete
   *
   * @return true if the element exists and it was deleted correctly, false otherwise
   */
  boolean delete(String key);

  /**
   * Flushes the cache removing all the elements.
   */
  void flushAll();
}
