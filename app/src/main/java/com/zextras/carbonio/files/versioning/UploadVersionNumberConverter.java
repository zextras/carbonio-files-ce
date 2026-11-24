// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.versioning;

import com.zextras.carbonio.files.rest.types.UploadVersionResponse;
import com.zextras.carbonio.quarkus.extensions.apiversioning.ApiConverter;
import com.zextras.carbonio.quarkus.extensions.apiversioning.ValueConverter;

/** Upload answers always carry {@code version}; older clients got it only above 1. */
@ApiConverter
public class UploadVersionNumberConverter implements ValueConverter<UploadVersionResponse> {

  @Override
  public UploadVersionResponse convert(UploadVersionResponse value) {
    if (!Integer.valueOf(1).equals(value.getVersion())) {
      return value;
    }
    UploadVersionResponse legacy = new UploadVersionResponse();
    legacy.setNodeId(value.getNodeId());
    return legacy;
  }
}
