// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.resources;

import com.zextras.carbonio.files.exceptions.RequestEntityTooLargeException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Shared per-route body-size enforcement for the small, fully-buffered JSON/form endpoints (the
 * {@code download-multiple} family — see {@link BlobResource#downloadMultiple}/{@link
 * BlobResource#checkDownloadMultiple}/{@link PublicBlobResource#downloadPublicMultiple}/{@link
 * PublicBlobResource#checkDownloadPublicMultiple}). Restores the legacy Netty {@code
 * HttpObjectAggregator(1048576)} 1MB cap (see {@code core/.../HttpRoutingHandler#channelRead0},
 * lines ~110-119/152-167), which the Quarkus port dropped entirely.
 *
 * <p>Deliberately bounds the number of bytes actually READ from the entity {@link InputStream}
 * rather than trusting the {@code Content-Length} header: {@code quarkus.http.limits.max-body-size}
 * is left blank on purpose (see {@code application.properties}) so large uploads can still stream,
 * meaning there is no framework-level fallback cap either, and a Content-Length-only check would be
 * bypassable by chunked transfer-encoding (the exact same bypass class as the missing-Content-Length
 * upload-size hole this same hardening pass restores — see {@code BlobResource
 * #isRequestSizeOverLimit}).
 */
final class RequestBodyLimits {

  private RequestBodyLimits() {}

  /** Legacy parity: {@code new HttpObjectAggregator(1048576)}, i.e. exactly 1 MiB. */
  static final long DOWNLOAD_MULTIPLE_MAX_BODY_BYTES = 1_048_576L;

  /**
   * Reads {@code in} fully as UTF-8, throwing {@link RequestEntityTooLargeException} the moment
   * more than {@code maxBytes} bytes have actually been read — independent of whatever (if
   * anything) the {@code Content-Length} header claimed.
   */
  static String readBoundedUtf8(InputStream in, long maxBytes) {
    try {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      byte[] chunk = new byte[8192];
      long total = 0;
      int read;
      while ((read = in.read(chunk)) != -1) {
        total += read;
        if (total > maxBytes) {
          throw new RequestEntityTooLargeException(
              "Request body exceeds the maximum allowed size of " + maxBytes + " bytes");
        }
        buffer.write(chunk, 0, read);
      }
      return buffer.toString(StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new RuntimeException("Failed to read the request body", e);
    }
  }

  /**
   * Minimal {@code application/x-www-form-urlencoded} decoder: splits on {@code &} then the first
   * {@code =}, URL-decoding each key/value pair (UTF-8, {@code +} as space) exactly like {@code
   * @FormParam} does. Used so the body-size bound above can be enforced on the raw bytes BEFORE
   * any form parsing is attempted, in place of the framework's own {@code @FormParam} binding
   * (which buffers the whole body itself, with no bound we control).
   */
  static Map<String, String> parseFormUrlEncoded(String rawBody) {
    Map<String, String> fields = new HashMap<>();
    if (rawBody == null || rawBody.isEmpty()) {
      return fields;
    }
    for (String pair : rawBody.split("&")) {
      if (pair.isEmpty()) {
        continue;
      }
      int eq = pair.indexOf('=');
      String key = eq >= 0 ? pair.substring(0, eq) : pair;
      String value = eq >= 0 ? pair.substring(eq + 1) : "";
      fields.put(
          URLDecoder.decode(key, StandardCharsets.UTF_8),
          URLDecoder.decode(value, StandardCharsets.UTF_8));
    }
    return fields;
  }
}
