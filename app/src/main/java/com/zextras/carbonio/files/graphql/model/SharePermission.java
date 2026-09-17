// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.model;

import org.eclipse.microprofile.graphql.Enum;

@Enum("SharePermission")
public enum SharePermission {
  READ_ONLY,
  READ_AND_WRITE,
  READ_AND_SHARE,
  READ_WRITE_AND_SHARE
}
