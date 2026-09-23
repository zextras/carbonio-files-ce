// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.model;

import org.eclipse.microprofile.graphql.Enum;

@Enum("NodeSort")
public enum NodeSort {
  LAST_EDITOR_ASC,
  LAST_EDITOR_DESC,
  NAME_ASC,
  NAME_DESC,
  OWNER_ASC,
  OWNER_DESC,
  TYPE_ASC,
  TYPE_DESC,
  UPDATED_AT_ASC,
  UPDATED_AT_DESC,
  SIZE_ASC,
  SIZE_DESC
}
