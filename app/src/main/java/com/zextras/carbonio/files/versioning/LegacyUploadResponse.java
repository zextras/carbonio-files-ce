// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.versioning;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.zextras.carbonio.files.rest.types.UploadResponse;
import com.zextras.carbonio.files.rest.types.UploadResult;

/**
 * The {@code POST /upload} body for older clients: {@code nodeId}, {@code version} only above 1.
 */
@JsonInclude(Include.NON_NULL)
public record LegacyUploadResponse(String nodeId, Integer version) implements UploadResult {

  static LegacyUploadResponse of(UploadResponse current) {
    return new LegacyUploadResponse(
        current.nodeId(), current.version() > 1 ? current.version() : null);
  }
}
