// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.utilities;

import java.nio.charset.StandardCharsets;

/**
 * Builds {@code Content-Disposition} header values for filenames that may contain non-ASCII
 * characters (e.g. Cyrillic, CJK, emoji).
 *
 * <p>The value uses the RFC 6266 / RFC 8187 {@code filename*=UTF-8''<pct-encoded>} form only, which
 * is always pure ASCII. This is mandatory when the value is set on a {@code
 * java.net.http.HttpClient} request: that client rejects any header value containing a char &gt;
 * 0xFF (raw non-ASCII), so the filename must never reach the header value unencoded. {@code
 * URLEncoder.encode} must NOT be used here: it emits {@code +} for space, which RFC 8187 reads as a
 * literal {@code +}, not a space.
 */
public final class ContentDispositionUtils {

  private static final char[] HEX = "0123456789ABCDEF".toCharArray();

  // RFC 8187 attr-char: ALPHA / DIGIT / "!" "#" "$" "&" "+" "-" "." "^" "_" "`" "|" "~"
  private static final String ATTR_CHAR_SYMBOLS = "!#$&+-.^_`|~";

  private ContentDispositionUtils() {}

  public static String attachment(String filename) {
    return "attachment; filename*=UTF-8''" + encodeRfc8187(filename);
  }

  static String encodeRfc8187(String filename) {
    byte[] bytes = filename.getBytes(StandardCharsets.UTF_8);
    StringBuilder encoded = new StringBuilder(bytes.length * 3);
    for (byte b : bytes) {
      int c = b & 0xFF;
      if ((c >= 'A' && c <= 'Z')
          || (c >= 'a' && c <= 'z')
          || (c >= '0' && c <= '9')
          || ATTR_CHAR_SYMBOLS.indexOf(c) >= 0) {
        encoded.append((char) c);
      } else {
        encoded.append('%').append(HEX[c >> 4]).append(HEX[c & 0x0F]);
      }
    }
    return encoded.toString();
  }
}
