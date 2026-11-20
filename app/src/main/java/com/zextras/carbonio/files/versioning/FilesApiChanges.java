// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.versioning;

import com.zextras.carbonio.quarkus.extensions.apiversioning.ApiChange;
import com.zextras.carbonio.quarkus.extensions.apiversioning.ApiVersion;

@ApiChange(
    since = ApiVersion.CURRENT,
    converter = ArchiveNodeTypeConverter.class,
    inputUnaffected =
        "findNodes(type: OTHER) cannot be widened to OTHER and ARCHIVE without changing the"
            + " domain query, so an old client asking for OTHER no longer finds archives")
@ApiChange(since = ApiVersion.CURRENT, converter = UploadParentIdConverter.class)
@ApiChange(
    since = ApiVersion.CURRENT,
    description = "getConfigs accepts the optional argument names, older clients never send it")
final class FilesApiChanges {}
