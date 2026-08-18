// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.types.internal;

/** The id of a newly created node, returned by {@code POST /internal/folders}. */
public record InternalNodeIdDto(String nodeId) {}
