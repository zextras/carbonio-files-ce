// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.dao;

/**
 * Represents the type of a user account. This is a local domain type that replaces the old {@code
 * com.zextras.carbonio.usermanagement.enumerations.UserType} from the HTTP SDK.
 */
public enum UserType {
  INTERNAL,
  GUEST
}
