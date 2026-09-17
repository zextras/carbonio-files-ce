// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.model;

import org.eclipse.microprofile.graphql.Enum;

@Enum("ShareSort")
public enum ShareSort {
  CREATION_ASC,
  CREATION_DESC,
  TARGET_USER_ASC,
  TARGET_USER_DESC,
  SHARE_PERMISSIONS_ASC,
  SHARE_PERMISSIONS_DESC,
  EXPIRATION_ASC,
  EXPIRATION_DESC
}
