// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.acceptance.seam;

import com.zextras.carbonio.files.utilities.http.HttpRequest;
import com.zextras.carbonio.files.utilities.http.HttpResponse;

/**
 * Neutral facade over a running Files instance. The ONLY seam that acceptance-test bodies see.
 *
 * <p>Today the sole implementation, {@code GuiceNettyFilesTestApp} (in {@code seam.impl}), wraps
 * the existing Guice+Netty {@code Simulator}. At Quarkus-rewrite time a sibling
 * {@code QuarkusFilesTestApp} implementation is added in the same {@code seam.impl} package and
 * the acceptance-test bodies are not touched.
 */
public interface FilesTestApp extends AutoCloseable {

  /** Send a request over the app's HTTP contract and get the response. Transport-agnostic. */
  HttpResponse send(HttpRequest request);

  /** Multipart/form send (replaces TestUtils.sendFormRequest). */
  HttpResponse sendForm(HttpRequest request);

  /**
   * Binary/streamed upload send, for the upload routes ({@code /upload}, {@code /upload-version},
   * {@code /internal/upload}, {@code /upload-to}) whose body {@code BlobController} reads as a raw
   * byte stream rather than aggregated JSON/form text. Build the request with {@code
   * HttpRequest#ofUpload}.
   */
  HttpResponse upload(HttpRequest request);

  /** Seed + inspect state without touching the DI container or the ORM directly. */
  TestDataAccess backdoor();

  /** Configure external-dependency fakes (Storages/Preview/DocsConnector/UM) neutrally. */
  Mocks mocks();

  @Override
  void close();
}
