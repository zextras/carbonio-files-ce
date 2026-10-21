// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.exceptions;

/** Another overwrite of the same node is in progress (its lease has not expired): 423 Locked. */
public class OverwriteInProgressException extends RuntimeException {

  public OverwriteInProgressException(String message) {
    super(message);
  }
}
