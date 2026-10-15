// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl;

public class InvalidPageTokenException extends IllegalArgumentException {

  public InvalidPageTokenException(String message) {
    super(message);
  }

  public InvalidPageTokenException(String message, Throwable cause) {
    super(message, cause);
  }
}
