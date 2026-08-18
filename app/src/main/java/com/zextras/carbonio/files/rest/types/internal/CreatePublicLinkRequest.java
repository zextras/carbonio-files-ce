// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.types.internal;

/** Request body of {@code POST /internal/links}. */
public record CreatePublicLinkRequest(String userId, String nodeId) {}
