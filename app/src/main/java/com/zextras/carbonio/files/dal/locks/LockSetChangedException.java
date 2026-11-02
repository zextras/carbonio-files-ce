// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.locks;

public class LockSetChangedException extends RuntimeException {
  public LockSetChangedException(String message) {
    super(message);
  }
}
