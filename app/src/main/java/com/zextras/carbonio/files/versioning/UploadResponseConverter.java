// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.versioning;

import com.zextras.carbonio.files.rest.types.UploadResponse;
import com.zextras.carbonio.files.rest.types.UploadResult;
import com.zextras.carbonio.quarkus.extensions.apiversioning.ApiConverter;
import com.zextras.carbonio.quarkus.extensions.apiversioning.ValueConverter;

/** {@code POST /upload} answers {@code {node_id, version}}; older clients read {@code nodeId}. */
@ApiConverter
public class UploadResponseConverter implements ValueConverter<UploadResult> {

  @Override
  public UploadResult convert(UploadResult value) {
    return value instanceof UploadResponse current ? LegacyUploadResponse.of(current) : value;
  }
}
