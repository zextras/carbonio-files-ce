// SPDX-FileCopyrightText: 2023 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.exceptions;

public class MaxNumberOfFileVersionsException extends RuntimeException {

  public MaxNumberOfFileVersionsException(String message) {
    super(message);
  }

  public MaxNumberOfFileVersionsException(Throwable throwable) {
    super(throwable);
  }

  public MaxNumberOfFileVersionsException(String message, Throwable throwable) {
    super(message, throwable);
  }
}
