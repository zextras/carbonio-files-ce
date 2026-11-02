// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

final class Chunks {

  static final int IN_LIST_LIMIT = 1000;

  private Chunks() {}

  static <T> List<List<T>> of(Collection<T> items) {
    List<T> all = items instanceof List<T> list ? list : new ArrayList<>(items);
    List<List<T>> chunks = new ArrayList<>();
    for (int from = 0; from < all.size(); from += IN_LIST_LIMIT) {
      chunks.add(all.subList(from, Math.min(from + IN_LIST_LIMIT, all.size())));
    }
    return chunks;
  }
}
