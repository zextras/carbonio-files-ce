// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.types;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(name = "UploadResponse")
public record UploadResponse(
    @JsonProperty("node_id") @Schema(required = true) String nodeId,
    @JsonProperty("version") @Schema(required = true) int version)
    implements UploadResult {}
