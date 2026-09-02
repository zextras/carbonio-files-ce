// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.utilities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.net.URI;
import java.net.http.HttpRequest;
import org.junit.jupiter.api.Test;

class ContentDispositionUtilsTest {

  @Test
  void attachmentEncodesNonAsciiFilenameAsRfc8187() {
    String header = ContentDispositionUtils.attachment("Привет, мир!.odt");

    assertThat(header)
        .isEqualTo(
            "attachment;"
                + " filename*=UTF-8''%D0%9F%D1%80%D0%B8%D0%B2%D0%B5%D1%82%2C%20%D0%BC%D0%B8%D1%80!.odt");
  }

  @Test
  void attachmentEncodesSpaceAsPercent20NotPlus() {
    String header = ContentDispositionUtils.attachment("a b.txt");

    assertThat(header).isEqualTo("attachment; filename*=UTF-8''a%20b.txt").doesNotContain("+");
  }

  @Test
  void attachmentProducesHeaderValueAcceptedByTheJdkHttpClient() {
    // Regression guard: the JDK HttpClient rejects any header value with a char > 0xFF. A non-ASCII
    // filename must therefore never reach the header value raw (see MailboxHttpClient / upload-to).
    String header = ContentDispositionUtils.attachment("файл 文件 🎉.txt");

    assertThat(header.chars().allMatch(c -> c >= 0x20 && c <= 0x7E)).isTrue();
    assertThatCode(
            () ->
                HttpRequest.newBuilder()
                    .uri(URI.create("http://mailbox.invalid/service/upload?fmt=raw"))
                    .header("Content-Disposition", header)
                    .POST(HttpRequest.BodyPublishers.ofString("x"))
                    .build())
        .doesNotThrowAnyException();
  }
}
