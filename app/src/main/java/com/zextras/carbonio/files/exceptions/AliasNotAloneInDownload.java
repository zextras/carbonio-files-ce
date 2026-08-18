// SPDX-FileCopyrightText: 2023 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.exceptions;

public class AliasNotAloneInDownload extends RuntimeException {

  public AliasNotAloneInDownload(String message) {
    super(message);
  }

  public AliasNotAloneInDownload(Throwable throwable) {
    super(throwable);
  }

  public AliasNotAloneInDownload(String message, Throwable throwable) {
    super(message, throwable);
  }
}
