// SPDX-FileCopyrightText: 2025 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.exceptions;

public class InvalidTokenSignException extends RuntimeException {

  public InvalidTokenSignException(String message) {
    super(message);
  }

  public InvalidTokenSignException(Throwable throwable) {
    super(throwable);
  }

  public InvalidTokenSignException(String message, Throwable throwable) {
    super(message, throwable);
  }
}
