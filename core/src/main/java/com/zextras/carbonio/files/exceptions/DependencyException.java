// SPDX-FileCopyrightText: 2023 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.exceptions;

public class DependencyException extends RuntimeException {

  public DependencyException(String message) {
    super(message);
  }

  public DependencyException(Throwable throwable) {
    super(throwable);
  }

  public DependencyException(String message, Throwable throwable) {
    super(message, throwable);
  }
}
