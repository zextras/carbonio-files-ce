// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities;

import io.ebean.Query;

import java.util.Optional;

interface SortingEntityEbean<T> {
    // "collate" is ignored for ordered queries that do not use a String field as sorting criteria
    Query<T> getOrderEbeanQuery(Query<T> query, Optional<String> collate);
}
