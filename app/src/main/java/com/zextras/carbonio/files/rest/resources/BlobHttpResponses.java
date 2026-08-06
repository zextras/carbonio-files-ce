// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.resources;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Shared transport-only helper for the streamed blob/ZIP HTTP responses used by {@link
 * BlobResource}, {@link PublicBlobResource} and {@link PreviewResource}. The actual byte streaming
 * now lives in {@link TransferStreaming} (which pumps to the Vert.x response on the dedicated
 * transfer pool); this class retains only the {@code Content-Disposition} builder those callers
 * share.
 */
final class BlobHttpResponses {

  private BlobHttpResponses() {}

  static String contentDisposition(String filename) {
    String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8);
    return String.format("attachment; filename*=UTF-8''%s", encoded);
  }
}
