// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities;

import io.ebean.Query;

interface SortingEntityEbean<T> {

  Query<T> getOrderEbeanQuery(Query<T> query);
}
