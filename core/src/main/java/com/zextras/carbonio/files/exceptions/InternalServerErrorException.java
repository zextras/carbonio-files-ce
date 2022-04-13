// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.exceptions;

public class InternalServerErrorException extends Exception {

  public InternalServerErrorException(String message) {
    super(message);
  }

  public InternalServerErrorException(Throwable cause) {
    super(cause);
  }
}
