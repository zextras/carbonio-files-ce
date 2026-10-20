// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.exceptions;

/** Another overwrite of the same version is still writing its blob: the caller retries later. */
public class VersionInUseException extends RuntimeException {

  public VersionInUseException(String message) {
    super(message);
  }
}
