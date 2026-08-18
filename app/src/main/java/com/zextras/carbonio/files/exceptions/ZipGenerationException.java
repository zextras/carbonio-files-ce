// SPDX-FileCopyrightText: 2023 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.exceptions;

public class ZipGenerationException extends RuntimeException {

  public ZipGenerationException(String message) {
    super(message);
  }

  public ZipGenerationException(Throwable throwable) {
    super(throwable);
  }

  public ZipGenerationException(String message, Throwable throwable) {
    super(message, throwable);
  }
}
