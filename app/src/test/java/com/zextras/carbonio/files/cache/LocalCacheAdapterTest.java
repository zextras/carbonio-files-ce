// SPDX-FileCopyrightText: 2023 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.cache;

import java.time.Clock;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class LocalCacheAdapterTest {

  private Cache<String> cache;
  private Clock fakeClock;

  @BeforeEach
  void initTest() {
    fakeClock = Mockito.mock(Clock.class);
    Mockito.when(fakeClock.millis()).thenReturn(50L);

    cache = new LocalCacheAdapter<>(
      "Testing",
      100,
      100,
      fakeClock
    );
  }

  @AfterEach
  void cleanUp() {
    cache.flushAll();
  }

  @Test
  void givenAllCachePropertiesTheConstructShouldCreateCacheObjectCorrectly() {
    // Given
    String cacheName = "fake-cache";
    long cacheSize = 50L;
    long itemLifetimeInMillis = 5L;

    // When
    Cache<String> cache = new LocalCacheAdapter<>(
      cacheName,
      cacheSize,
      itemLifetimeInMillis,
      fakeClock
    );

    // Then
    Assertions.assertThat(cache.getName()).isEqualTo(cacheName);
    Assertions.assertThat(cache.getDefaultCacheSize()).isEqualTo(cacheSize);
    Assertions.assertThat(cache.getDefaultItemLifetimeInMillis()).isEqualTo(itemLifetimeInMillis);
    Assertions.assertThat(cache.size()).isZero();
  }

  @Test
  void givenACacheAndANewItemTheAddShouldAddATheItemCorrectly() {
    // Given
    String item = "This is a testing string";

    // When
    cache.add("test", item);

    // Then
    Assertions.assertThat(cache.get("test")).contains("This is a testing string");
  }

  @Test
  void givenACacheAndACollectionOfNewItemsTheAddAllShouldAddATheItemsCorrectly() {
    // Given
    Map<String, String> items = new HashMap<>();
    items.put("one", "This is a testing string");
    items.put("two", "Another test");
    items.put("three", "T");

    // When
    cache.addAll(items);

    // Then
    Assertions.assertThat(cache.size()).isEqualTo(3);
    Assertions.assertThat(cache.get("one")).contains("This is a testing string");
    Assertions.assertThat(cache.get("two")).contains("Another test");
    Assertions.assertThat(cache.get("three")).contains("T");
  }

  @Test
  void givenACacheAndACollectionOfItemsTheGetAllShouldReturnASpecificCollectionOfItems() {
    // Given
    cache.add("one", "first item");
    cache.add("two", "second item");
    cache.add("three", "third item");

    List<String> keys = Arrays.asList("one", "three");

    // When
    Map<String, String> result = cache.getAll(keys);

    // Then
    Assertions
      .assertThat(result)
      .hasSize(2)
      .contains(Map.entry("one", "first item"), Map.entry("three", "third item"));
  }

  @Test
  void givenACacheContainingAnItemAndItsKeyTheDeleteShouldDeleteTheItemCorrectly() {
    // Given
    cache.add("one", "item");

    // When
    boolean isDeleted = cache.delete("one");

    // Then
    Assertions.assertThat(isDeleted).isTrue();
    Assertions.assertThat(cache.get("one")).isEmpty();
  }

  @Test
  void givenAnEmptyCacheAndAnItemKeyTheDeleteShouldReturnFalse() {
    // Given & When
    boolean isDeleted = cache.delete("one");

    // Then
    Assertions.assertThat(cache.get("one")).isEmpty();
    Assertions.assertThat(isDeleted).isFalse();
  }

  @Test
  void givenACacheContainingSomeItemsTheCacheDeletesTheExpiredOneWhenTheClockIsIncreased() {
    // Given
    cache.add("one", "first short-lived item");
    cache.add("two", "second short-lived item");

    // Adding 5 millis to the clock (so the first two elements have 1 millis left to live)
    Mockito.when(fakeClock.millis()).thenReturn(149L);

    cache.add("three", "first long-lived item");
    cache.add("four", "second long-lived item");

    // When
    // Adding 2 millis to the clock (so the first two elements are expired.
    // The other elements have 49 more millis left to live.
    Mockito.when(fakeClock.millis()).thenReturn(151L);

    // Then
    Assertions.assertThat(cache.size()).isEqualTo(2);
    Assertions.assertThat(cache.get("one")).isEmpty();
    Assertions.assertThat(cache.get("two")).isEmpty();
    Assertions.assertThat(cache.get("three")).contains("first long-lived item");
    Assertions.assertThat(cache.get("four")).contains("second long-lived item");
  }

  @Test
  void givenACacheWithAnItemTheResetLifeItemShouldResetTheLifetimeOfTheItemCorrectly() {
    // Given
    String item = "This is a testing string";
    cache.add("test", item);
    Mockito.when(fakeClock.millis()).thenReturn(149L);

    // When
    cache.resetLifetime("test");

    // Then
    // Without the resetLifetime the element should be expired
    // but with the reset it has 1 more millis to live
    Mockito.when(fakeClock.millis()).thenReturn(199L);
    Assertions.assertThat(cache.get("test")).isPresent();
  }

  @Test
  void givenACacheContainingSomeItemsTheFlushAllShouldClearTheCache() {
    // Given
    cache.add("one", "first short-lived item");
    cache.add("two", "second short-lived item");
    cache.add("three", "first long-lived item");
    cache.add("four", "second long-lived item");

    cache.flushAll();
    Assertions.assertThat(cache.size()).isZero();
  }
}
