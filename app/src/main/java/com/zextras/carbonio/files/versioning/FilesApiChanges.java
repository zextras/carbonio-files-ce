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
final class FilesApiChanges {}
