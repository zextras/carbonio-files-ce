// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.types.internal;

/**
 * Request body of {@code POST /internal/folders}. The acting user is carried in the body (not the
 * path) per the trusted-caller convention for POST/DELETE endpoints on this surface.
 */
public record CreateFolderRequest(String userId, String destinationId, String name) {}
