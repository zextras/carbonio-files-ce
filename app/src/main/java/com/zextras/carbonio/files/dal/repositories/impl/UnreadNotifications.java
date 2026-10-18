// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl;

import java.util.Map;
import java.util.TreeMap;

/**
 * The unread notifications to add per user, collected over a whole transaction and applied once, in
 * sorted user order, after its commit.
 */
public record UnreadNotifications(Map<String, Integer> countsByUserId) {

  public UnreadNotifications() {
    this(new TreeMap<>());
  }
}
