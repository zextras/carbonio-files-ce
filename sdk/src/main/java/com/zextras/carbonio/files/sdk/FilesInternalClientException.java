// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.sdk;

/**
 * Unchecked exception thrown by {@link FilesInternalClient} when a call to the {@code /internal}
 * REST surface fails, either at the transport level (connection refused, timeout, ...) or with a
 * non-2xx HTTP response. Wraps the underlying checked exception ({@code ApiException} for the
 * generated JSON client, {@code IOException}/{@code InterruptedException} for the raw streaming
 * helper) so {@link FilesInternalClient} callers deal with a single, unchecked exception type,
 * mirroring how the retired gRPC surface mapped every failure to a single {@code
 * StatusRuntimeException}.
 */
public class FilesInternalClientException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** The HTTP status code of the failed response, or {@code -1} if not known (transport-level
   *  failure: the request never got a response to read a status from). */
  private final int statusCode;

  public FilesInternalClientException(String message, int statusCode, Throwable cause) {
    super(message, cause);
    this.statusCode = statusCode;
  }

  public int getStatusCode() {
    return statusCode;
  }

  public boolean isNotFound() {
    return statusCode == 404;
  }

  public boolean isForbidden() {
    return statusCode == 403;
  }
}
