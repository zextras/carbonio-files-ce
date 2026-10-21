// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl;

/**
 * The database clock in HQL: one time source for every Files instance, whatever their system clocks
 * say.
 */
final class DatabaseClock {

  /** Milliseconds since the epoch, read when the statement runs. */
  static final String NOW_MILLIS =
      "cast(cast(function('date_part', 'epoch', function('clock_timestamp')) as Double) * 1000"
          + " as Long)";

  private DatabaseClock() {}
}
