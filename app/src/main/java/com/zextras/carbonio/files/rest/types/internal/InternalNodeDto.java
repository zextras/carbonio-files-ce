// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.types.internal;

/**
 * The trusted-caller REST representation of a {@link
 * com.zextras.carbonio.files.dal.dao.ebean.Node}, returned by {@code GET
 * /internal/accounts/{userId}/nodes/{nodeId}}.
 *
 * <p>{@code extension}/{@code mimeType}/{@code size}/{@code version} are {@code null} for folders
 * (and roots): only files carry a resolved {@link
 * com.zextras.carbonio.files.dal.dao.ebean.FileVersion}.
 *
 * <p><strong>{@code updatedAt} is the resolved {@link
 * com.zextras.carbonio.files.dal.dao.ebean.FileVersion#getUpdatedAt()} for files, NOT {@link
 * com.zextras.carbonio.files.dal.dao.ebean.Node#getUpdatedAt()}</strong> — this replicates the
 * GraphQL {@code NodeDataFetcher#convertNodeToDataFetcherResult} behaviour, where the file
 * version's map entry silently overrides the node's under the same {@code updated_at} key. For
 * folders/roots (no file version to override it) it is simply {@code Node#getUpdatedAt()}.
 */
public record InternalNodeDto(
    String id,
    String name,
    String extension,
    String mimeType,
    Long size,
    Integer version,
    long updatedAt,
    OwnerDto owner,
    ParentDto parent,
    PermissionsDto permissions) {}
