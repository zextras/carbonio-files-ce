// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.interfaces;

public interface CollationRepository {
  /**
   * Get the valid collation to use in the queries.
   * If the collation defined in the configuration is not valid, it tries the en_US utf8 collation.
   * If that is not valid either, it returns the default collation.
   * Used to make ordered queries consistent with the collation chosen by the admin instead of relying on the database
   * default collation (which is taken from the system locale and thus depends on the installation).
   *
   * @return the valid collation
   */
  String getValidCollation();
}
