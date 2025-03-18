// SPDX-FileCopyrightText: 2025 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.exceptions;

public class InvalidTokenSignatureException extends RuntimeException {

  public InvalidTokenSignatureException(String message) {
    super(message);
  }

  public InvalidTokenSignatureException(Throwable throwable) {
    super(throwable);
  }

  public InvalidTokenSignatureException(String message, Throwable throwable) {
    super(message, throwable);
  }
}
