// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.versioning;

import com.zextras.carbonio.quarkus.extensions.apiversioning.ApiConverter;
import com.zextras.carbonio.quarkus.extensions.apiversioning.ApiVersion;

@ApiConverter(
    since = ApiVersion.CURRENT,
    description = "getConfigs accepts the optional argument names, older clients never send it")
public class GetConfigsNamesArgument {}
