// SPDX-FileCopyrightText: 2023 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.exceptions;

public class FileTypeMismatchException extends RuntimeException {

  public FileTypeMismatchException(String message) {
    super(message);
  }

  public FileTypeMismatchException(Throwable throwable) {
    super(throwable);
  }

  public FileTypeMismatchException(String message, Throwable throwable) {
    super(message, throwable);
  }
}
