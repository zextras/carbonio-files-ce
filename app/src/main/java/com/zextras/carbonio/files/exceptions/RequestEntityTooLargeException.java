// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.exceptions;

/**
 * Thrown when an upload to an external service (e.g. carbonio-mailbox) is rejected because of its
 * size. Ported from the legacy (core) checked {@code RequestEntityTooLargeException}; unchecked
 * here to match the other P4/P5 exception ports. Mapped to HTTP 413 by {@link
 * com.zextras.carbonio.files.rest.resources.BlobExceptionMapper}, same as the legacy {@code
 * ExceptionsHandler}.
 */
public class RequestEntityTooLargeException extends RuntimeException {

  public RequestEntityTooLargeException(String message) {
    super(message);
  }
}
