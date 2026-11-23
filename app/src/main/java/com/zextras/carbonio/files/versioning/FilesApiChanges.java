// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.versioning;

import com.zextras.carbonio.quarkus.extensions.apiversioning.ApiChange;
import com.zextras.carbonio.quarkus.extensions.apiversioning.ApiVersion;

@ApiChange(
    since = ApiVersion.CURRENT,
    description =
        "Introduce API versioning: declared statuses and error codes, /versions and the version"
            + " header in the contract docs")
@ApiChange(since = ApiVersion.CURRENT, converter = UploadDestinationIdConverter.class)
@ApiChange(since = ApiVersion.CURRENT, converter = UploadResponseConverter.class)
@ApiChange(
    since = ApiVersion.CURRENT,
    description = "POST /upload/check: pre-flight of an upload (Filename, DestinationId, FileSize)")
@ApiChange(
    since = ApiVersion.CURRENT,
    description = "Deprecate the getAccountByEmail query: use getAccountsByEmail")
final class FilesApiChanges {}
