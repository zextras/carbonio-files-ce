// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.types.internal;

/**
 * The requester's permissions on a node, as exposed by the trusted {@code /internal} node
 * endpoints. Only the one flag the sole consumer (docs-connector) needs is exposed; see {@link
 * com.zextras.carbonio.files.graphql.types.Permissions} for the full ACL-derived set used by the
 * GraphQL layer.
 */
public record PermissionsDto(boolean canWriteFile) {}
